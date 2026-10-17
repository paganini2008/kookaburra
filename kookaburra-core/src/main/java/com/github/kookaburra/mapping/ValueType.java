package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Storage-level type of a property value, see design §3.3.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public enum ValueType {
    BOOL, INT32, INT64, FLOAT64,
    /** {@link BigDecimal}, compared by numeric value; stored without trailing zeros (see {@link #decimal}). */
    DECIMAL,
    STRING, ENUM, DATE, DATETIME, INSTANT, REF,
    /** {@code List} / {@code Set} of a scalar type; one row has many values. */
    MULTI,
    /** {@link Embeddable} value object, flattened into paths. */
    EMBEDDED,
    /** {@code List} / {@code Set} of {@link Embeddable} values; row only. */
    EMBEDDED_LIST,
    /** {@code Map} with scalar keys and values; row only. */
    MAP,
    /** {@code byte[]}; row only. */
    BYTES,
    /** Any other {@code Serializable} value, stored with Java serialization; row only. */
    OBJECT;

    /** Maps a declared Java type to its value type, ignoring generics (see {@code JavaTypes} for full analysis). */
    public static ValueType of(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return BOOL;
        if (type == int.class || type == Integer.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class
                || type == char.class || type == Character.class) return INT32;
        if (type == long.class || type == Long.class) return INT64;
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return FLOAT64;
        if (type == BigDecimal.class) return DECIMAL;
        if (type == String.class) return STRING;
        if (type.isEnum()) return ENUM;
        if (type == LocalDate.class) return DATE;
        if (type == LocalDateTime.class) return DATETIME;
        if (type == Instant.class) return INSTANT;
        if (type == Ref.class) return REF;
        if (type == byte[].class) return BYTES;
        if (Collection.class.isAssignableFrom(type)) return MULTI;
        if (Map.class.isAssignableFrom(type)) return MAP;
        if (type.isAnnotationPresent(Embeddable.class)) return EMBEDDED;
        return OBJECT;
    }

    /** True for types that can be dictionary-encoded and compared. */
    public boolean isScalar() {
        return ordinal() <= REF.ordinal();
    }

    /** True if a column can be built: scalars and multi-valued scalars. */
    public boolean isIndexable() {
        return isScalar() || this == MULTI;
    }

    /**
     * Normalizes a Java value to its canonical representation: narrow integers and chars become
     * {@link Integer}, {@code float} becomes {@link Double}, a {@link BigDecimal} loses its trailing zeros
     * ({@link #decimal}). Other values are returned unchanged.
     */
    public static Object normalize(Object value) {
        if (value instanceof BigDecimal d) return decimal(d);
        if (value instanceof Short s) return (int) s;
        if (value instanceof Byte b) return (int) b;
        if (value instanceof Character c) return (int) c;
        if (value instanceof Float f) return (double) f;
        return value;
    }

    /**
     * Canonical form of a decimal: numerically equal values are equal ({@code 1.50} and {@code 1.5} both become
     * {@code 1.5}, like {@link BigDecimal#compareTo}), and never in exponent form ({@code 100}, not {@code 1E+2}).
     */
    public static BigDecimal decimal(BigDecimal d) {
        if (d.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /**
     * Total order over non-null canonical values of one scalar type. Matches {@code compareTo} of the
     * Java types (so {@code Double.compare} for doubles and UTF-16 order for strings); refs by OID.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (a instanceof Ref<?> ra && b instanceof Ref<?> rb) {
            return Long.compare(ra.oid(), rb.oid());
        }
        return ((Comparable) a).compareTo(b);
    }
}
