package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
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
            defs.add(new FieldDef(f.getName(), t, index));
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
                    return TypeDef.embeddedList(describe(e, e, stack, where), set);
                }
                return object(raw, where);
            }
            case MAP: {
                Class<?> k = typeArg(generic, 0, 2);
                Class<?> v = typeArg(generic, 1, 2);
                if (k != null && v != null && ValueType.of(k).isScalar() && ValueType.of(v).isScalar()) {
                    return TypeDef.map(describe(k, k, stack, where), describe(v, v, stack, where));
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
