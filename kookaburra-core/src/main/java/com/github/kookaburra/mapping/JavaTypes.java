package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Version;
import com.github.kookaburra.field.Property;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives stored types ({@link TypeDef}, {@link FieldDef}) from Java classes.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class JavaTypes {

    private JavaTypes() {
    }

    /** Persistent fields of a class hierarchy, superclass fields first. Excludes {@code @Id} / {@code @Version}. */
    static List<Field> persistentFields(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(0, c);
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                if (!Property.isPersistent(f) || f.isAnnotationPresent(Id.class) || f.isAnnotationPresent(Version.class)) {
                    continue;
                }
                if (fields.containsKey(f.getName())) {
                    throw new SchemaException("Field '" + f.getName() + "' of " + c.getName() + " hides a superclass field");
                }
                if (Modifier.isFinal(f.getModifiers())) {
                    throw new SchemaException("Persistent field " + f + " must not be final");
                }
                fields.put(f.getName(), f);
            }
        }
        return List.copyOf(fields.values());
    }

    /** Stored field definitions for a class (entity or embeddable). */
    static List<FieldDef> fieldDefs(Class<?> type) {
        return fieldDefs(type, new ArrayDeque<>());
    }

    private static List<FieldDef> fieldDefs(Class<?> type, Deque<Class<?>> stack) {
        List<FieldDef> defs = new ArrayList<>();
        for (Field f : persistentFields(type)) {
            String where = type.getName() + "." + f.getName();
            TypeDef t = describe(f.getType(), f.getGenericType(), stack, where);
            IndexKind index = null;
            Index ann = f.getAnnotation(Index.class);
            if (ann != null) {
                if (!t.kind().isIndexable()) {
                    throw new SchemaException("@Index on " + where + " of type " + t
                            + " is not supported (index scalar or List/Set-of-scalar fields)");
                }
                if (ann.value() == IndexKind.BSI) {
                    throw new SchemaException("@Index(BSI) on " + where + " is not implemented yet");
                }
                index = ann.value();
            }
            Unique ua = f.getAnnotation(Unique.class);
            if (ua != null && ua.fields().length > 0) {
                throw new SchemaException("@Unique(fields = ...) belongs on the class, not on the field " + where);
            }
            boolean unique = ua != null;
            if (unique && (!t.kind().isScalar() || t.kind() == ValueType.MULTI)) {
                throw new SchemaException("@Unique on " + where + " of type " + t + " is not supported (unique scalar fields)");
            }
            OnDelete onDelete = null;
            ForeignKey fk = f.getAnnotation(ForeignKey.class);
            if (fk != null) {
                if (t.scalarType().kind() != ValueType.REF) {
                    throw new SchemaException("@ForeignKey on " + where + " of type " + t
                            + " is not supported (Ref<T> or a List / Set of Ref<T>)");
                }
                onDelete = fk.onDelete();
            }
            if ((unique || onDelete != null) && index == null) {
                index = IndexKind.AUTO;                    // constraints are checked through the index
            }
            defs.add(new FieldDef(f.getName(), t, index, unique, onDelete));
        }
        return defs;
    }

    static TypeDef describe(Class<?> raw, Type generic, Deque<Class<?>> stack, String where) {
        ValueType vt = ValueType.of(raw);
        switch (vt) {
            case ENUM:
                return TypeDef.enumOf(raw.getName(),
                        Arrays.stream(raw.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
            case REF: {
                Class<?> target = typeArg(generic, 0, 1);
                return TypeDef.ref(target == null ? Object.class.getName() : target.getName());
            }
            case BYTES:
                return TypeDef.bytes();
            case MULTI: {
                Class<?> e = typeArg(generic, 0, 1);
                boolean set = Set.class.isAssignableFrom(raw);
                if (e != null && ValueType.of(e).isScalar()) {
                    return TypeDef.multi(describe(e, e, stack, where), set);
                }
                if (e != null && ValueType.of(e) == ValueType.EMBEDDED) {
                    return TypeDef.embeddedList(unconstrained(describe(e, e, stack, where), where), set);
                }
                return object(raw, where);
            }
            case MAP: {
                Class<?> k = typeArg(generic, 0, 2);
                Class<?> v = typeArg(generic, 1, 2);
                if (k != null && v != null && ValueType.of(k).isScalar()
                        && (ValueType.of(v).isScalar() || ValueType.of(v) == ValueType.EMBEDDED)) {
                    return TypeDef.map(describe(k, k, stack, where), unconstrained(describe(v, v, stack, where), where));
                }
                return object(raw, where);
            }
            case EMBEDDED: {
                if (stack.contains(raw)) {
                    throw new SchemaException("Recursive @Embeddable " + raw.getName() + " at " + where);
                }
                stack.push(raw);
                try {
                    return TypeDef.embedded(raw.getName(), fieldDefs(raw, stack));
                } finally {
                    stack.pop();
                }
            }
            case OBJECT:
                return object(raw, where);
            default:
                return TypeDef.scalar(vt);
        }
    }

    /**
     * Class-level {@code @Unique(fields = …)} of {@code type} (not inherited: a superclass declares its own). Paths
     * must name scalar fields of {@code fields} (dotted through embedded values).
     */
    static List<UniqueDef> uniques(Class<?> type, List<FieldDef> fields) {
        List<UniqueDef> out = new ArrayList<>();
        for (Unique u : type.getDeclaredAnnotationsByType(Unique.class)) {
            if (u.fields().length == 0) {
                throw new SchemaException("@Unique on class " + type.getName() + " needs fields = {...}");
            }
            for (String path : u.fields()) {
                FieldDef f = fieldAt(fields, path);
                if (f == null) {
                    throw new SchemaException("@Unique on " + type.getName() + ": no field '" + path + "'");
                }
                if (!f.type().kind().isScalar()) {
                    throw new SchemaException("@Unique on " + type.getName() + ": field '" + path + "' of type " + f.type()
                            + " is not supported (scalar fields only)");
                }
            }
            if (new HashSet<>(Arrays.asList(u.fields())).size() != u.fields().length) {
                throw new SchemaException("@Unique on " + type.getName() + ": a field is listed twice");
            }
            out.add(new UniqueDef(u.name(), List.of(u.fields())));
        }
        return out;
    }

    /** Fields with the first path of every constraint indexed when none of its paths is (lookups go through it). */
    static List<FieldDef> indexFor(List<FieldDef> fields, List<UniqueDef> uniques) {
        List<FieldDef> out = fields;
        for (UniqueDef u : uniques) {
            boolean indexed = false;
            for (String p : u.paths()) {
                indexed |= fieldAt(out, p).index() != null;
            }
            if (!indexed) {
                out = withIndex(out, u.paths().get(0));
            }
        }
        return out;
    }

    private static List<FieldDef> withIndex(List<FieldDef> fields, String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        List<FieldDef> out = new ArrayList<>(fields);
        for (int i = 0; i < out.size(); i++) {
            FieldDef f = out.get(i);
            if (!f.name().equals(head)) {
                continue;
            }
            if (dot < 0) {
                out.set(i, new FieldDef(f.name(), f.type(), IndexKind.AUTO, f.unique(), f.onDelete()));
            } else {
                TypeDef t = f.type();
                TypeDef nested = TypeDef.embedded(t.name(), withIndex(t.fields(), path.substring(dot + 1)));
                out.set(i, new FieldDef(f.name(), nested, f.index(), f.unique(), f.onDelete()));
            }
        }
        return out;
    }

    /** Field at a dotted path through embedded values, or null. */
    static FieldDef fieldAt(List<FieldDef> fields, String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        for (FieldDef f : fields) {
            if (f.name().equals(head)) {
                if (dot < 0) {
                    return f;
                }
                return f.type().kind() == ValueType.EMBEDDED ? fieldAt(f.type().fields(), path.substring(dot + 1)) : null;
            }
        }
        return null;
    }

    /** Element types of embedded lists / maps have no column paths, so they cannot carry constraints. */
    private static TypeDef unconstrained(TypeDef t, String where) {
        for (FieldDef f : t.fields()) {
            if (f.unique() || f.onDelete() != null) {
                throw new SchemaException("@" + (f.unique() ? "Unique" : "ForeignKey") + " on " + t.name() + "." + f.name()
                        + " is not supported inside a list or map of embedded objects (" + where + ")");
            }
            unconstrained(f.type(), where);
        }
        return t;
    }

    private static TypeDef object(Class<?> raw, String where) {
        if (raw.isPrimitive() || !(Serializable.class.isAssignableFrom(raw) || raw.isInterface())) {
            throw new SchemaException(where + ": type " + raw.getName()
                    + " is not storable (make it @Embeddable or Serializable)");
        }
        return TypeDef.object(raw.getName());
    }

    static Class<?> typeArg(Type generic, int i, int n) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == n
                && p.getActualTypeArguments()[i] instanceof Class<?> c) {
            return c;
        }
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == n
                && p.getActualTypeArguments()[i] instanceof ParameterizedType pt
                && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** Whether the class is an {@code @Embeddable}. */
    static boolean isEmbeddable(Class<?> c) {
        return c.isAnnotationPresent(Embeddable.class);
    }
}
