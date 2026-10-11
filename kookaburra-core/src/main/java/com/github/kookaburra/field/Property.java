package com.github.kookaburra.field;

import com.github.kookaburra.annotation.Transient;
import com.github.kookaburra.mapping.ValueType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * A persistent Java field of an entity or embeddable class.
 *
 * @param owner       class the property was looked up on (may be a subclass of the declaring class)
 * @param name        Java field name
 * @param javaField   reflective field
 * @param type        value type
 * @param elementType element class for {@link ValueType#MULTI}, else {@code null}
 * @param getter      {@code (Object) -> Object} raw field getter
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Property(Class<?> owner, String name, java.lang.reflect.Field javaField, ValueType type,
                       Class<?> elementType, MethodHandle getter) {

    private static final MethodType OBJECT_GETTER = MethodType.methodType(Object.class, Object.class);

    /** Finds a persistent field by name in {@code owner} or its superclasses. */
    public static Optional<Property> find(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Field f;
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }
            if (!isPersistent(f)) {
                return Optional.empty();
            }
            return Optional.of(create(owner, f));
        }
        return Optional.empty();
    }

    public static boolean isPersistent(java.lang.reflect.Field f) {
        int m = f.getModifiers();
        return !Modifier.isStatic(m) && !Modifier.isTransient(m) && !f.isSynthetic()
                && !f.isAnnotationPresent(Transient.class);
    }

    private static Property create(Class<?> owner, java.lang.reflect.Field f) {
        ValueType type = ValueType.of(f.getType());
        Class<?> element = null;
        if (type == ValueType.MULTI) {
            element = typeArg(f.getGenericType(), 0, 1);
            if (element != null && ValueType.of(element).isScalar()) {
                type = ValueType.MULTI;
            } else if (element != null && ValueType.of(element) == ValueType.EMBEDDED) {
                type = ValueType.EMBEDDED_LIST;
            } else {
                type = ValueType.OBJECT;
                element = null;
            }
        } else if (type == ValueType.MAP) {
            Class<?> k = typeArg(f.getGenericType(), 0, 2);
            Class<?> v = typeArg(f.getGenericType(), 1, 2);
            if (k == null || v == null || !ValueType.of(k).isScalar() || !ValueType.of(v).isScalar()) {
                type = ValueType.OBJECT;
            }
        }
        MethodHandle getter;
        try {
            var lookup = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup());
            getter = lookup.unreflectGetter(f).asType(OBJECT_GETTER);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access " + f + "; is its package open to com.github.kookaburra?", e);
        }
        return new Property(owner, f.getName(), f, type, element, getter);
    }

    /** The {@code i}-th type argument if it is a plain class and the type has {@code n} arguments. */
    static Class<?> typeArg(Type generic, int i, int n) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == n
                && p.getActualTypeArguments()[i] instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** Declared Java class of the value. */
    public Class<?> javaType() {
        return javaField.getType();
    }

    /** Reads the raw (not normalized) value. */
    public Object read(Object target) {
        try {
            return getter.invokeExact(target);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
