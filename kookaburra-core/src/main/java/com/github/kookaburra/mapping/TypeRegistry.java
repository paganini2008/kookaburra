package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.Comment;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Renamed;
import com.github.kookaburra.annotation.Version;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registers entity classes into the {@link Schema}, assigning class ids and layout versions, and caches
 * their {@link EntityType}s. Schema changes are passed to a listener (for persistence) before they
 * become visible.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class TypeRegistry {

    private volatile Schema schema;
    private final Map<Class<?>, EntityType<?>> byClass = new ConcurrentHashMap<>();
    private final Map<Integer, EntityType<?>> byId = new ConcurrentHashMap<>();
    private final ClassLoader loader;
    private final Consumer<Schema> listener;

    public TypeRegistry(Schema initial, ClassLoader loader, Consumer<Schema> listener) {
        this.schema = Objects.requireNonNull(initial);
        this.loader = loader;
        this.listener = listener;
    }

    public Schema schema() {
        return schema;
    }

    /** Returns the entity type of {@code cls}, registering it (and its entity superclasses) if needed. */
    @SuppressWarnings("unchecked")
    public <T> EntityType<T> entity(Class<T> cls) {
        EntityType<?> t = byClass.get(cls);
        return t != null ? (EntityType<T>) t : register(cls);
    }

    /** Entity type for a stored class id, loading its Java class by name; null if it is not on the classpath. */
    public EntityType<?> entity(int classId) {
        EntityType<?> t = byId.get(classId);
        if (t != null) {
            return t;
        }
        String name = schema.byId(classId).name();
        try {
            return entity(Class.forName(name, true, loader != null ? loader : getClass().getClassLoader()));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private synchronized <T> EntityType<T> register(Class<T> cls) {
        @SuppressWarnings("unchecked")
        EntityType<T> existing = (EntityType<T>) byClass.get(cls);
        if (existing != null) {
            return existing;
        }
        if (!cls.isAnnotationPresent(Entity.class)) {
            throw new SchemaException(cls.getName() + " is not an @Entity");
        }
        if (cls.isInterface() || cls.isRecord() || cls.isEnum()) {
            throw new SchemaException(cls.getName() + ": @Entity must be a class (records are not supported yet)");
        }
        Class<?> parent = entitySuperclass(cls);
        String collection = cls.getName();
        if (parent != null) {
            collection = entity(parent).collection();
        }
        String idField = systemField(cls, Id.class, true);
        String versionField = systemField(cls, Version.class, false);
        List<FieldDef> fields = JavaTypes.fieldDefs(cls);
        Comment commentAnn = cls.getAnnotation(Comment.class);
        String classComment = commentAnn == null ? null : commentAnn.value();
        List<UniqueDef> uniques = JavaTypes.uniques(cls, fields);
        fields = JavaTypes.indexFor(fields, uniques);

        Schema s = schema;
        ClassDef def = s.byName(cls.getName());
        if (def != null && !def.name().equals(cls.getName())) {
            throw new SchemaException(cls.getName() + " is a former name of " + def.name() + "; both classes cannot be stored");
        }
        if (def == null) {
            def = renamedClass(s, cls);
        }
        if (def != null) {
            def = renameFields(cls, def, fields);
        }
        if (def == null) {
            def = new ClassDef(s.nextClassId(), cls.getName(), parent == null ? null : parent.getName(), collection,
                    idField, versionField, List.of(new Layout(0, fields)), uniques, List.of(), classComment);
        } else {
            String parentName = parent == null ? null : parent.getName();
            ClassDef storedParent = def.parent() == null ? null : s.byName(def.parent());
            if (!(storedParent == null ? parentName == null : storedParent.name().equals(parentName))) {
                throw new SchemaException(cls.getName() + ": entity superclass changed from " + def.parent());
            }
            if (parentName != null && !parentName.equals(def.parent())) {      // the parent was renamed
                def = new ClassDef(def.id(), def.name(), parentName, def.collection(), def.idField(), def.versionField(),
                        def.layouts(), def.uniques(), def.formerNames(), def.comment());
            }
            if (!def.current().fields().equals(fields)) {
                if (withoutComments(def.current().fields()).equals(withoutComments(fields))) {
                    // only comments changed: no new layout, the current one takes the new comments
                    List<Layout> layouts = new ArrayList<>(def.layouts());
                    layouts.set(layouts.size() - 1, new Layout(def.current().id(), fields));
                    def = def.withLayouts(layouts);
                } else {
                    checkCompatible(cls, def.current().fields(), fields);
                    def = def.withLayout(fields);
                }
            }
            if (!Objects.equals(def.comment(), classComment)) {
                def = def.withComment(classComment);
            }
            if (!Objects.equals(def.idField(), idField) || !Objects.equals(def.versionField(), versionField)) {
                def = new ClassDef(def.id(), def.name(), def.parent(), def.collection(), idField, versionField, def.layouts(),
                        def.uniques(), def.formerNames(), def.comment());
            }
            if (!def.uniques().equals(uniques)) {
                def = def.withUniques(uniques);
            }
        }
        if (def != s.byName(cls.getName())) {
            Schema next = s.with(def);
            if (listener != null) {
                listener.accept(next);
            }
            schema = next;
        }
        EntityType<T> type = new EntityType<>(cls, def);
        byClass.put(cls, type);
        byId.put(def.id(), type);
        return type;
    }

    /** Fields without their comments (any depth): what decides whether a new layout is needed. */
    static List<FieldDef> withoutComments(List<FieldDef> fields) {
        List<FieldDef> out = new ArrayList<>();
        for (FieldDef f : fields) {
            out.add(new FieldDef(f.name(), withoutComments(f.type()), f.index(), f.unique(), f.onDelete(), null));
        }
        return out;
    }

    private static TypeDef withoutComments(TypeDef t) {
        if (t == null) {
            return null;
        }
        return switch (t.kind()) {
            case EMBEDDED -> TypeDef.embedded(t.name(), withoutComments(t.fields()));
            case EMBEDDED_LIST -> TypeDef.embeddedList(withoutComments(t.element()), t.set());
            case MAP -> TypeDef.map(t.key(), withoutComments(t.element()));
            default -> t;
        };
    }

    /** The stored class {@code cls} was called before ({@code @Renamed} on the class), renamed; null if none. */
    private static ClassDef renamedClass(Schema s, Class<?> cls) {
        Renamed r = cls.getAnnotation(Renamed.class);
        if (r == null) {
            return null;
        }
        for (String former : r.value()) {
            ClassDef old = s.byName(former);
            if (old != null) {
                if (s.byName(cls.getName()) != null) {
                    throw new SchemaException(cls.getName() + ": cannot take over " + former + ", the name is stored already");
                }
                return old.renamedTo(cls.getName());
            }
        }
        return null;
    }

    /**
     * Applies {@code @Renamed} on fields of the class and of its embeddables: in every stored layout the former name
     * becomes the new one (rows are decoded by position, so old rows read under the new name). Renames are keyed by
     * the field's path ({@code ""} for the entity, {@code "home"} for the embeddable in {@code home}), so an
     * embeddable class may change its name too. Fails when both names are stored.
     */
    private ClassDef renameFields(Class<?> cls, ClassDef def, List<FieldDef> fields) {
        Map<String, Map<String, String>> renames = new LinkedHashMap<>();
        collectRenames(cls, "", renames);
        embeddedRenames(fields, "", renames, 0);
        if (renames.isEmpty()) {
            return def;
        }
        List<Layout> layouts = new ArrayList<>();
        boolean changed = false;
        for (Layout l : def.layouts()) {
            List<FieldDef> renamed = renameIn(cls, l.fields(), "", renames);
            changed |= !renamed.equals(l.fields());
            layouts.add(new Layout(l.id(), renamed));
        }
        return changed ? def.withLayouts(layouts) : def;
    }

    /** {@code @Renamed} fields of {@code c} (and its non-entity superclasses) under {@code path}. */
    private static void collectRenames(Class<?> c, String path, Map<String, Map<String, String>> out) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                Renamed r = f.getAnnotation(Renamed.class);
                if (r != null) {
                    for (String former : r.value()) {
                        out.computeIfAbsent(path, x -> new LinkedHashMap<>()).put(former, f.getName());
                    }
                }
            }
        }
    }

    /** Renames declared in the embeddables of the current fields (any depth, lists and maps of them included). */
    private void embeddedRenames(List<FieldDef> fields, String prefix, Map<String, Map<String, String>> out, int depth) {
        if (depth > 16) {
            return;
        }
        for (FieldDef f : fields) {
            TypeDef t = f.type();
            while (t != null && (t.kind() == ValueType.EMBEDDED_LIST || t.kind() == ValueType.MAP)) {
                t = t.element();
            }
            if (t != null && t.kind() == ValueType.EMBEDDED && t.name() != null) {
                String path = prefix + f.name();
                try {
                    collectRenames(Class.forName(t.name(), false, loader != null ? loader : getClass().getClassLoader()), path, out);
                } catch (ClassNotFoundException ignored) {
                    // a stored embeddable without its class: nothing to rename
                }
                embeddedRenames(t.fields(), path + ".", out, depth + 1);
            }
        }
    }

    private static List<FieldDef> renameIn(Class<?> cls, List<FieldDef> fields, String path, Map<String, Map<String, String>> all) {
        Map<String, String> renames = all.get(path);
        List<FieldDef> out = new ArrayList<>();
        for (FieldDef f : fields) {
            String to = renames == null ? null : renames.get(f.name());
            if (to != null && fields.stream().anyMatch(o -> o.name().equals(to))) {
                throw new SchemaException(cls.getName() + ": cannot rename " + f.name() + " to " + to
                        + ", both are stored; migrate the data explicitly");
            }
            String name = to != null ? to : f.name();
            String sub = path.isEmpty() ? name : path + "." + name;
            out.add(new FieldDef(name, renameType(cls, f.type(), sub, all), f.index(), f.unique(), f.onDelete(), f.comment()));
        }
        return out;
    }

    private static TypeDef renameType(Class<?> cls, TypeDef t, String path, Map<String, Map<String, String>> all) {
        if (t == null) {
            return null;
        }
        return switch (t.kind()) {
            case EMBEDDED -> TypeDef.embedded(t.name(), renameIn(cls, t.fields(), path, all));
            case EMBEDDED_LIST -> TypeDef.embeddedList(renameType(cls, t.element(), path, all), t.set());
            case MAP -> TypeDef.map(t.key(), renameType(cls, t.element(), path, all));
            default -> t;
        };
    }

    private static Class<?> entitySuperclass(Class<?> cls) {
        for (Class<?> c = cls.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.isAnnotationPresent(Entity.class)) {
                return c;
            }
        }
        return null;
    }

    private static String systemField(Class<?> cls, Class<? extends Annotation> ann, boolean required) {
        String found = null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(ann)) {
                    if (f.getType() != long.class) {
                        throw new SchemaException("@" + ann.getSimpleName() + " field " + f + " must be a long");
                    }
                    if (found != null) {
                        throw new SchemaException(cls.getName() + " has more than one @" + ann.getSimpleName());
                    }
                    found = f.getName();
                }
            }
        }
        if (found == null && required) {
            throw new SchemaException(cls.getName() + " needs a long @Id field");
        }
        return found;
    }

    private static void checkCompatible(Class<?> cls, List<FieldDef> old, List<FieldDef> neu) {
        for (FieldDef o : old) {
            for (FieldDef n : neu) {
                if (n.name().equals(o.name()) && !n.type().canRead(o.type())) {
                    throw new SchemaException(cls.getName() + "." + n.name() + ": stored type " + o.type()
                            + " cannot be read as " + n.type() + "; migrate the data explicitly");
                }
            }
        }
    }
}
