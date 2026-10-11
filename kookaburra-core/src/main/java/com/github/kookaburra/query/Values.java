package com.github.kookaburra.query;

import com.github.kookaburra.Ref;
import com.github.kookaburra.field.AbstractField;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.ListField;
import com.github.kookaburra.mapping.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical value → Java value of a field (enum constants, {@link Ref}s, element conversion).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Values {

    private Values() {
    }

    /** True if the field's value can be produced from canonical column / record values. */
    static boolean canonical(Field<?, ?> f) {
        return f.type().isScalar() || f.type() == ValueType.MULTI;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object toJava(Field<?, ?> f, Object c) {
        if (c == null) {
            return null;
        }
        if (f.type() == ValueType.MULTI) {
            Class<?> element = f instanceof ListField<?, ?> l ? l.elementType() : Object.class;
            List<Object> out = new ArrayList<>();
            for (Object e : (List<?>) c) {
                out.add(scalar(ValueType.of(element), element, e));
            }
            return out;
        }
        Class<?> javaType = f instanceof AbstractField<?, ?> a ? a.javaType() : Object.class;
        return scalar(f.type(), javaType, c);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object scalar(ValueType type, Class<?> javaType, Object c) {
        if (type == ValueType.ENUM && javaType.isEnum()) {
            return Enum.valueOf((Class) javaType, (String) c);
        }
        if (type == ValueType.REF && c instanceof Long oid) {
            return Ref.of(oid);
        }
        return c;
    }
}
