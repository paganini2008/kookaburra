package com.github.kookaburra.mapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an entity or embeddable class to / from canonical {@code Object[]} values.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class BeanMapper {

    /** One mapped Java field. */
    record Slot(String name, Field field, MethodHandle getter, MethodHandle setter, FieldDef def,
                Class<?> element, Class<?> key, BeanMapper nested) {
    }

    private final Class<?> type;
    private final MethodHandle ctor;
    private final List<FieldDef> fields;
    private final Slot[] slots;
    private final Map<String, Slot> byName = new LinkedHashMap<>();

    BeanMapper(Class<?> type, List<FieldDef> fields) {
        this.type = type;
        this.fields = fields;
        this.ctor = constructor(type);
        List<Field> javaFields = JavaTypes.persistentFields(type);
        this.slots = new Slot[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            FieldDef def = fields.get(i);
            Field f = javaFields.stream().filter(x -> x.getName().equals(def.name())).findFirst()
                    .orElseThrow(() -> new SchemaException("no field " + def.name() + " in " + type.getName()));
            slots[i] = slot(f, def);
            byName.put(def.name(), slots[i]);
        }
    }

    private static Slot slot(Field f, FieldDef def) {
        MethodHandle get;
        MethodHandle set;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup());
            get = lookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
            set = lookup.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, Object.class));
        } catch (IllegalAccessException e) {
            throw new SchemaException("cannot access " + f, e);
        }
        TypeDef t = def.type();
        Class<?> element = null;
        Class<?> key = null;
        BeanMapper nested = null;
        switch (t.kind()) {
            case MULTI -> element = JavaTypes.typeArg(f.getGenericType(), 0, 1);
            case EMBEDDED -> nested = new BeanMapper(f.getType(), t.fields());
            case EMBEDDED_LIST -> {
                element = JavaTypes.typeArg(f.getGenericType(), 0, 1);
                nested = new BeanMapper(element, t.element().fields());
            }
            case MAP -> {
                key = JavaTypes.typeArg(f.getGenericType(), 0, 2);
                element = JavaTypes.typeArg(f.getGenericType(), 1, 2);
            }
            default -> {
            }
        }
        return new Slot(f.getName(), f, get, set, def, element, key, nested);
    }

    private static MethodHandle constructor(Class<?> type) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            return lookup.findConstructor(type, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SchemaException(type.getName() + " needs a no-argument constructor", e);
        }
    }

    Class<?> type() {
        return type;
    }

    /** Values in the order of this mapper's current fields. */
    Object[] toCanonical(Object bean) {
        Object[] values = new Object[slots.length];
        for (int i = 0; i < slots.length; i++) {
            Slot s = slots[i];
            values[i] = Convert.toCanonical(get(s, bean), s.def().type(), s.nested(), type.getSimpleName() + "." + s.name());
        }
        return values;
    }

    /** New instance from values written with {@code srcFields} (possibly an older layout). */
    Object toJava(Object[] values, List<FieldDef> srcFields) {
        Object bean = newInstance();
        fill(bean, values, srcFields);
        return bean;
    }

    void fill(Object bean, Object[] values, List<FieldDef> srcFields) {
        for (int i = 0; i < srcFields.size(); i++) {
            FieldDef src = srcFields.get(i);
            Slot s = byName.get(src.name());
            Object v = values[i];
            if (s == null || v == null) {
                continue;     // removed field, or null: keep the constructor's default
            }
            set(s, bean, Convert.toJava(v, src.type(), s.field().getType(), s.element(), s.key(), s.nested()));
        }
    }

    Object newInstance() {
        try {
            return ctor.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static Object get(Slot s, Object bean) {
        try {
            return s.getter().invokeExact(bean);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void set(Slot s, Object bean, Object value) {
        try {
            s.setter().invokeExact(bean, value);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    List<FieldDef> fields() {
        return fields;
    }
}
