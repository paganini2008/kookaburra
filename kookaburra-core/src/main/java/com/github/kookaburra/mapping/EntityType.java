package com.github.kookaburra.mapping;

import com.github.kookaburra.LifecycleEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** Lifecycle callback methods per event (superclass methods first); null when the class has none. */
    private final Map<LifecycleEvent, List<MethodHandle>> callbacks;

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
        this.callbacks = callbacks(type);
    }

    /** Annotated no-argument lifecycle methods of the class hierarchy (design §3.3). */
    private static Map<LifecycleEvent, List<MethodHandle>> callbacks(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(0, c);
        }
        Map<LifecycleEvent, List<MethodHandle>> out = new EnumMap<>(LifecycleEvent.class);
        Set<String> overridden = new HashSet<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (Method m : chain.get(i).getDeclaredMethods()) {
                for (LifecycleEvent e : LifecycleEvent.values()) {
                    if (!m.isAnnotationPresent(e.annotation())) {
                        continue;
                    }
                    if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) {
                        throw new SchemaException("@" + e.annotation().getSimpleName() + " method " + m
                                + " must be a non-static method without parameters");
                    }
                    // an overriding method replaces the overridden one (called once, like JPA)
                    if (!Modifier.isPrivate(m.getModifiers()) && !overridden.add(e + ":" + m.getName())) {
                        continue;
                    }
                    try {
                        MethodHandle h = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup()).unreflect(m)
                                .asType(MethodType.methodType(void.class, Object.class));
                        out.computeIfAbsent(e, k -> new ArrayList<>()).add(0, h);
                    } catch (IllegalAccessException ex) {
                        throw new SchemaException("cannot access " + m, ex);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Calls the entity's own methods for {@code event} (superclass methods first). */
    public void callback(LifecycleEvent event, T entity) {
        List<MethodHandle> hs = callbacks == null ? null : callbacks.get(event);
        if (hs == null) {
            return;
        }
        for (MethodHandle h : hs) {
            try {
                h.invokeExact((Object) entity);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
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
