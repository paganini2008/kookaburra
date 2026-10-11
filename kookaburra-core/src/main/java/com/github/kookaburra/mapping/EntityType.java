package com.github.kookaburra.mapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * A registered Java entity class: converts objects to / from {@link Record}s and accesses
 * {@code @Id} / {@code @Version}.
 *
 * @param <T> entity class
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class EntityType<T> {

    private final Class<T> type;
    private final ClassDef def;
    private final BeanMapper mapper;
    private final MethodHandle idGet;
    private final MethodHandle idSet;
    private final MethodHandle versionGet;
    private final MethodHandle versionSet;

    EntityType(Class<T> type, ClassDef def) {
        this.type = type;
        this.def = def;
        this.mapper = new BeanMapper(type, def.current().fields());
        MethodHandle[] id = accessors(type, def.idField());
        this.idGet = id[0];
        this.idSet = id[1];
        MethodHandle[] v = def.versionField() == null ? new MethodHandle[2] : accessors(type, def.versionField());
        this.versionGet = v[0];
        this.versionSet = v[1];
    }

    private static MethodHandle[] accessors(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                return new MethodHandle[]{
                        lookup.unreflectGetter(f).asType(MethodType.methodType(long.class, Object.class)),
                        lookup.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, long.class))};
            } catch (NoSuchFieldException e) {
                // keep looking in superclasses
            } catch (IllegalAccessException e) {
                throw new SchemaException("cannot access " + name + " of " + type.getName(), e);
            }
        }
        throw new SchemaException("no field " + name + " in " + type.getName());
    }

    public Class<T> type() {
        return type;
    }

    public ClassDef def() {
        return def;
    }

    public int classId() {
        return def.id();
    }

    public String collection() {
        return def.collection();
    }

    /** Converts with the current layout. */
    public Record toRecord(T entity) {
        return new Record(def.id(), def.current(), mapper.toCanonical(entity));
    }

    /** Creates an object from a record of this class (any layout version) and sets id / version. */
    public T fromRecord(Record record, long oid, long version) {
        if (record.classId() != def.id()) {
            throw new IllegalArgumentException("record of class " + record.classId() + " is not a " + type.getName());
        }
        T obj = type.cast(mapper.toJava(record.values(), record.layout().fields()));
        setId(obj, oid);
        setVersion(obj, version);
        return obj;
    }

    public long id(T entity) {
        try {
            return (long) idGet.invokeExact((Object) entity);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setId(T entity, long oid) {
        try {
            idSet.invokeExact((Object) entity, oid);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Version, or 0 when the class has no {@code @Version} field. */
    public long version(T entity) {
        if (versionGet == null) {
            return 0;
        }
        try {
            return (long) versionGet.invokeExact((Object) entity);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean hasVersion() {
        return versionGet != null;
    }

    public void setVersion(T entity, long version) {
        if (versionSet == null) {
            return;
        }
        try {
            versionSet.invokeExact((Object) entity, version);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    @Override
    public String toString() {
        return "EntityType(" + type.getName() + ", id=" + def.id() + ")";
    }
}
