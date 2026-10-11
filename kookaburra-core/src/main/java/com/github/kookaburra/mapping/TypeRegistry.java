package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Version;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
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

        Schema s = schema;
        ClassDef def = s.byName(cls.getName());
        if (def == null) {
            def = new ClassDef(s.nextClassId(), cls.getName(), parent == null ? null : parent.getName(), collection,
                    idField, versionField, List.of(new Layout(0, fields)));
        } else {
            if (!Objects.equals(def.parent(), parent == null ? null : parent.getName())) {
                throw new SchemaException(cls.getName() + ": entity superclass changed from " + def.parent());
            }
            if (!def.current().fields().equals(fields)) {
                checkCompatible(cls, def.current().fields(), fields);
                def = def.withLayout(fields);
            }
            if (!Objects.equals(def.idField(), idField) || !Objects.equals(def.versionField(), versionField)) {
                def = new ClassDef(def.id(), def.name(), def.parent(), def.collection(), idField, versionField, def.layouts());
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
