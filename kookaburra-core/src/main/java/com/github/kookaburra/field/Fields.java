package com.github.kookaburra.field;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.mapping.ValueType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Field registry and factory. Fields are interned per {@code (entity class, path)}, so the
 * generated metamodel and getter references resolve to the same instances (design §9.2).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Fields {

    private static final ClassValue<ConcurrentHashMap<String, AbstractField<?, ?>>> REGISTRY =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, AbstractField<?, ?>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private Fields() {
    }

    // ---------------------------------------------------------------- by path

    /** Returns the field for a dotted path of an {@code @Entity} class. */
    @SuppressWarnings("unchecked")
    public static <T> AbstractField<T, ?> of(Class<T> entity, String path) {
        return (AbstractField<T, ?>) REGISTRY.get(entity).computeIfAbsent(path, p -> create(entity, p));
    }

    public static <T> IntField<T> intField(Class<T> entity, String path) {
        return expect(of(entity, path), IntField.class);
    }

    public static <T> LongField<T> longField(Class<T> entity, String path) {
        return expect(of(entity, path), LongField.class);
    }

    public static <T> DoubleField<T> doubleField(Class<T> entity, String path) {
        return expect(of(entity, path), DoubleField.class);
    }

    public static <T> BoolField<T> boolField(Class<T> entity, String path) {
        return expect(of(entity, path), BoolField.class);
    }

    public static <T> StringField<T> stringField(Class<T> entity, String path) {
        return expect(of(entity, path), StringField.class);
    }

    public static <T, E extends Enum<E>> EnumField<T, E> enumField(Class<T> entity, String path) {
        return expect(of(entity, path), EnumField.class);
    }

    public static <T, V extends Comparable<? super V>> ComparableField<T, V> comparableField(
            Class<T> entity, String path) {
        return expect(of(entity, path), ComparableField.class);
    }

    public static <T, E> ListField<T, E> listField(Class<T> entity, String path) {
        return expect(of(entity, path), ListField.class);
    }

    public static <T, R> RefField<T, R> refField(Class<T> entity, String path) {
        return expect(of(entity, path), RefField.class);
    }

    public static <T, V> ObjectField<T, V> objectField(Class<T> entity, String path) {
        return expect(of(entity, path), ObjectField.class);
    }

    // ---------------------------------------------------------------- by getter reference

    public static <T> IntField<T> of(IntGetter<T> getter) {
        return expect(resolve(getter), IntField.class);
    }

    public static <T> LongField<T> of(LongGetter<T> getter) {
        return expect(resolve(getter), LongField.class);
    }

    public static <T> DoubleField<T> of(DoubleGetter<T> getter) {
        return expect(resolve(getter), DoubleField.class);
    }

    public static <T> BoolField<T> of(BoolGetter<T> getter) {
        return expect(resolve(getter), BoolField.class);
    }

    public static <T> StringField<T> of(StringGetter<T> getter) {
        return expect(resolve(getter), StringField.class);
    }

    public static <T, V extends Comparable<? super V>> ComparableField<T, V> of(ComparableGetter<T, V> getter) {
        return expect(resolve(getter), ComparableField.class);
    }

    public static <T, E> ListField<T, E> of(CollectionGetter<T, E> getter) {
        return expect(resolve(getter), ListField.class);
    }

    public static <T, R> RefField<T, R> of(RefGetter<T, R> getter) {
        return expect(resolve(getter), RefField.class);
    }

    public static <T, V> ObjectField<T, V> of(Getter<T, V> getter) {
        return expect(resolve(getter), ObjectField.class);
    }

    // ---------------------------------------------------------------- internals

    @SuppressWarnings("unchecked")
    private static <T> AbstractField<T, ?> resolve(Serializable getter) {
        GetterResolver.PropertyRef ref = GetterResolver.resolve(getter);
        return of((Class<T>) ref.owner(), ref.property());
    }

    @SuppressWarnings("unchecked")
    static <F> F expect(AbstractField<?, ?> field, Class<?> kind) {
        if (!kind.isInstance(field)) {
            throw new IllegalArgumentException(field + " is a " + field.type() + " field ("
                    + field.getClass().getSimpleName() + "), not a " + kind.getSimpleName());
        }
        return (F) field;
    }

    private static <T> AbstractField<T, ?> create(Class<T> entity, String path) {
        if (!entity.isAnnotationPresent(Entity.class)) {
            throw new IllegalArgumentException(entity.getName() + " is not an @Entity");
        }
        String[] parts = path.split("\\.", -1);
        List<Property> chain = new ArrayList<>(parts.length);
        Class<?> owner = entity;
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            Class<?> current = owner;
            Property p = Property.find(current, name).orElseThrow(() -> new IllegalArgumentException(
                    "No persistent field '" + name + "' in " + current.getName()));
            chain.add(p);
            if (i < parts.length - 1) {
                if (p.type() != ValueType.EMBEDDED) {
                    throw new IllegalArgumentException("'" + name + "' in " + current.getName()
                            + " is not @Embeddable; cannot use path " + path);
                }
                owner = p.javaType();
            }
        }
        return switch (chain.get(chain.size() - 1).type()) {
            case BOOL -> new BoolField<>(entity, path, chain);
            case INT32 -> new IntField<>(entity, path, chain);
            case INT64 -> new LongField<>(entity, path, chain);
            case FLOAT64 -> new DoubleField<>(entity, path, chain);
            case STRING -> new StringField<>(entity, path, chain);
            case ENUM -> new EnumField<>(entity, path, chain);
            case DATE, DATETIME, INSTANT -> new ComparableField<>(entity, path, chain);
            case REF -> new RefField<>(entity, path, chain);
            case MULTI -> new ListField<>(entity, path, chain);
            case EMBEDDED, EMBEDDED_LIST, MAP, BYTES, OBJECT -> new ObjectField<>(entity, path, chain);
        };
    }
}
