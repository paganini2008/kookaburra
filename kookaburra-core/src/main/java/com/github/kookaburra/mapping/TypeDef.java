package com.github.kookaburra.mapping;

import java.util.List;
import java.util.Objects;

/**
 * Self-describing type of a stored value (design §3.4). Recursive for collections, maps and embeddables.
 *
 * @param kind      value type
 * @param name      ENUM: enum class; REF: target class; EMBEDDED: embeddable class; OBJECT: Java type; else null
 * @param constants ENUM constants in declaration order (append-only across versions), else empty
 * @param element   MULTI / EMBEDDED_LIST element, MAP value type, else null
 * @param key       MAP key type, else null
 * @param fields    EMBEDDED fields, else empty
 * @param set       MULTI / EMBEDDED_LIST: declared as a {@code Set}
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record TypeDef(ValueType kind, String name, List<String> constants, TypeDef element, TypeDef key,
                      List<FieldDef> fields, boolean set) {

    public TypeDef {
        Objects.requireNonNull(kind, "kind");
        constants = constants == null ? List.of() : List.copyOf(constants);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static TypeDef scalar(ValueType kind) {
        if (!kind.isScalar() || kind == ValueType.ENUM || kind == ValueType.REF) {
            throw new IllegalArgumentException("not a plain scalar: " + kind);
        }
        return new TypeDef(kind, null, null, null, null, null, false);
    }

    public static TypeDef enumOf(String enumClass, List<String> constants) {
        return new TypeDef(ValueType.ENUM, enumClass, constants, null, null, null, false);
    }

    public static TypeDef ref(String target) {
        return new TypeDef(ValueType.REF, target, null, null, null, null, false);
    }

    public static TypeDef multi(TypeDef element, boolean set) {
        return new TypeDef(ValueType.MULTI, null, null, element, null, null, set);
    }

    public static TypeDef embedded(String embeddable, List<FieldDef> fields) {
        return new TypeDef(ValueType.EMBEDDED, embeddable, null, null, null, fields, false);
    }

    public static TypeDef embeddedList(TypeDef element, boolean set) {
        return new TypeDef(ValueType.EMBEDDED_LIST, null, null, element, null, null, set);
    }

    public static TypeDef map(TypeDef key, TypeDef value) {
        return new TypeDef(ValueType.MAP, null, null, value, key, null, false);
    }

    public static TypeDef bytes() {
        return new TypeDef(ValueType.BYTES, null, null, null, null, null, false);
    }

    public static TypeDef object(String javaType) {
        return new TypeDef(ValueType.OBJECT, javaType, null, null, null, null, false);
    }

    /** The scalar type values are compared by: the element type for MULTI, else this. */
    public TypeDef scalarType() {
        return kind == ValueType.MULTI ? element : this;
    }

    /** Index of an enum constant, or -1. */
    public int constantIndex(String constant) {
        return constants.indexOf(constant);
    }

    /** Field of an EMBEDDED type by name, or null. */
    public FieldDef field(String fieldName) {
        for (FieldDef f : fields) {
            if (f.name().equals(fieldName)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Whether values written with {@code older} can be read as {@code this}: same kind, or widening
     * INT32 → INT64; enum constants only appended; embedded fields compatible by name.
     */
    public boolean canRead(TypeDef older) {
        if (older.kind != kind) {
            return older.kind == ValueType.INT32 && kind == ValueType.INT64;
        }
        return switch (kind) {
            case ENUM -> constants.size() >= older.constants.size()
                    && constants.subList(0, older.constants.size()).equals(older.constants);
            case MULTI, EMBEDDED_LIST -> element.canRead(older.element);
            case MAP -> key.canRead(older.key) && element.canRead(older.element);
            case EMBEDDED -> older.fields.stream().allMatch(o -> {
                FieldDef n = field(o.name());
                return n == null || n.type().canRead(o.type());
            });
            default -> true;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case ENUM -> "ENUM(" + String.join(", ", constants) + ")";
            case REF -> "REF(" + simple(name) + ")";
            case MULTI -> (set ? "SET(" : "LIST(") + element + ")";
            case EMBEDDED -> simple(name) + fields;
            case EMBEDDED_LIST -> (set ? "SET(" : "LIST(") + element + ")";
            case MAP -> "MAP(" + key + ", " + element + ")";
            case OBJECT -> "OBJECT(" + name + ")";
            default -> kind.name();
        };
    }

    private static String simple(String className) {
        return className == null ? "?" : className.substring(className.lastIndexOf('.') + 1);
    }
}
