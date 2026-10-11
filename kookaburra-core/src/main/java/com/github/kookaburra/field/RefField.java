package com.github.kookaburra.field;

import com.github.kookaburra.Ref;
import com.github.kookaburra.query.Pred;
import com.github.kookaburra.query.QueryStream;

import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reference to another entity.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class RefField<T, R> extends AbstractField<T, Ref<R>> {

    RefField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    /** Referenced entity class, from the declared {@code Ref<R>} type argument. */
    public Class<?> target() {
        if (leaf().javaField().getGenericType() instanceof ParameterizedType p
                && p.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        throw new IllegalStateException(this + " must declare Ref<TargetClass>");
    }

    public Pred<T> eq(Ref<R> ref) {
        return new Pred.Eq<>(this, ref);
    }

    public Pred<T> eq(long oid) {
        return eq(Ref.of(oid));
    }

    @SafeVarargs
    public final Pred<T> in(Ref<R>... refs) {
        return new Pred.In<>(this, new LinkedHashSet<>(Arrays.asList(refs)));
    }

    /** The reference points at an object selected by {@code source} (semi-join; the sub-query only yields OIDs). */
    public Pred<T> in(QueryStream<R> source) {
        return new Pred.RefIn<>(this, source);
    }

    /** The referenced object matches {@code target} (evaluated as a sub-query). */
    public Pred<T> matches(Pred<R> target) {
        return new Pred.RefMatch<>(this, target);
    }
}
