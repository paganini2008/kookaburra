package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;

/**
 * Conversion of scalar query literals and Java values to their canonical stored form (see {@link Record}).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Canonical {

    private Canonical() {
    }

    /**
     * Canonical form of a scalar Java value: narrow ints → Integer, float → Double, enum → name,
     * {@link Ref} → OID. Other values are returned unchanged.
     */
    public static Object scalar(Object v) {
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Ref<?> r) return r.oid();
        return ValueType.normalize(v);
    }

    /**
     * Total order over canonical values of one scalar type, consistent with Java semantics of the
     * original values. Enums compare by position in {@code type}'s constant list (declaration order).
     */
    public static int compare(TypeDef type, Object a, Object b) {
        if (type.kind() == ValueType.ENUM) {
            return Integer.compare(type.constantIndex((String) a), type.constantIndex((String) b));
        }
        return ValueType.compare(a, b);
    }
}
