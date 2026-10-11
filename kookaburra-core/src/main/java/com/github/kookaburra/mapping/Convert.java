package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Java value ⇄ canonical value conversions (see {@link Record}).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Convert {

    private Convert() {
    }

    // ---------------------------------------------------------------- Java → canonical

    static Object toCanonical(Object v, TypeDef def, BeanMapper nested, String where) {
        if (v == null) {
            return null;
        }
        switch (def.kind()) {
            case MULTI: {
                List<Object> out = new ArrayList<>();
                for (Object e : (Collection<?>) v) {
                    out.add(scalar(requireElement(e, where), def.element(), where));
                }
                return out;
            }
            case EMBEDDED:
                return nested.toCanonical(v);
            case EMBEDDED_LIST: {
                List<Object> out = new ArrayList<>();
                for (Object e : (Collection<?>) v) {
                    out.add(nested.toCanonical(requireElement(e, where)));
                }
                return out;
            }
            case MAP: {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    Object val = e.getValue() == null ? null : scalar(e.getValue(), def.element(), where);
                    out.put(scalar(requireElement(e.getKey(), where), def.key(), where), val);
                }
                return out;
            }
            case BYTES:
                return v;
            case OBJECT:
                return serialize(v, where);
            default:
                return scalar(v, def, where);
        }
    }

    private static Object requireElement(Object e, String where) {
        if (e == null) {
            throw new IllegalArgumentException(where + ": null elements / keys are not supported");
        }
        return e;
    }

    private static Object scalar(Object v, TypeDef def, String where) {
        Object c = Canonical.scalar(v);
        if (def.kind() == ValueType.ENUM && def.constantIndex((String) c) < 0) {
            throw new IllegalArgumentException(where + ": unknown enum constant " + c);
        }
        return c;
    }

    static byte[] serialize(Object v, String where) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(v);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException(where + ": cannot serialize " + v.getClass().getName(), e);
        }
    }

    // ---------------------------------------------------------------- canonical → Java

    /**
     * Converts a canonical value written with {@code src} to the Java type of a field.
     *
     * @param raw     declared class of the target
     * @param element element / map value class (for collections and maps), else null
     * @param key     map key class, else null
     * @param nested  mapper for embedded targets, else null
     */
    static Object toJava(Object c, TypeDef src, Class<?> raw, Class<?> element, Class<?> key, BeanMapper nested) {
        if (c == null) {
            return null;
        }
        switch (src.kind()) {
            case MULTI: {
                Collection<Object> out = newCollection(raw);
                for (Object e : (List<?>) c) {
                    out.add(scalarToJava(e, element));
                }
                return out;
            }
            case EMBEDDED:
                return nested.toJava((Object[]) c, src.fields());
            case EMBEDDED_LIST: {
                Collection<Object> out = newCollection(raw);
                for (Object e : (List<?>) c) {
                    out.add(nested.toJava((Object[]) e, src.element().fields()));
                }
                return out;
            }
            case MAP: {
                Map<Object, Object> out = newMap(raw);
                for (Map.Entry<?, ?> e : ((Map<?, ?>) c).entrySet()) {
                    out.put(scalarToJava(e.getKey(), key), e.getValue() == null ? null : scalarToJava(e.getValue(), element));
                }
                return out;
            }
            case BYTES:
                return c;
            case OBJECT:
                return deserialize((byte[]) c, raw.getClassLoader());
            default:
                return scalarToJava(c, raw);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object scalarToJava(Object c, Class<?> t) {
        if (t == int.class || t == Integer.class) return ((Number) c).intValue();
        if (t == long.class || t == Long.class) return ((Number) c).longValue();
        if (t == double.class || t == Double.class) return ((Number) c).doubleValue();
        if (t == float.class || t == Float.class) return ((Number) c).floatValue();
        if (t == short.class || t == Short.class) return ((Number) c).shortValue();
        if (t == byte.class || t == Byte.class) return ((Number) c).byteValue();
        if (t == char.class || t == Character.class) return (char) ((Number) c).intValue();
        if (t == Ref.class) return Ref.of((Long) c);
        if (t.isEnum()) return Enum.valueOf((Class) t, (String) c);
        if (t == boolean.class || t == Boolean.class || t == String.class || t == LocalDate.class
                || t == LocalDateTime.class || t == Instant.class || t == Object.class) {
            return c;
        }
        throw new SchemaException("cannot convert " + c.getClass().getSimpleName() + " to " + t.getName());
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(Class<?> raw) {
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            if (SortedSet.class.isAssignableFrom(raw)) return new TreeSet<>();
            if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>();
            return new ArrayList<>();
        }
        return (Collection<Object>) instantiate(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(Class<?> raw) {
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            return SortedMap.class.isAssignableFrom(raw) ? new TreeMap<>() : new LinkedHashMap<>();
        }
        return (Map<Object, Object>) instantiate(raw);
    }

    private static Object instantiate(Class<?> raw) {
        try {
            return raw.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SchemaException("cannot instantiate " + raw.getName(), e);
        }
    }

    static Object deserialize(byte[] bytes, ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        try (InputStream in = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(in) {
                 @Override
                 protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                     try {
                         return Class.forName(desc.getName(), false, cl);
                     } catch (ClassNotFoundException e) {
                         return super.resolveClass(desc);
                     }
                 }
             }) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new SchemaException("cannot deserialize stored object", e);
        }
    }
}
