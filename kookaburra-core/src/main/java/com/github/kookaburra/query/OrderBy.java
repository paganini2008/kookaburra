package com.github.kookaburra.query;

/**
 * Fluent sort key of a {@link QueryStream}: {@code .orderBy(Person::getAge).desc().orderBy(Person::getName).asc()}.
 * Keys apply in call order; nulls sort last for {@code asc}, first for {@code desc} (as {@code sorted(...)}).
 *
 * @param <T> entity type
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class OrderBy<T> {

    private final QueryStream<T> stream;
    private final Order<T> asc;
    private final Order<T> desc;

    OrderBy(QueryStream<T> stream, Order<T> asc, Order<T> desc) {
        this.stream = stream;
        this.asc = asc;
        this.desc = desc;
    }

    public QueryStream<T> asc() {
        return stream.thenSorted(asc);
    }

    public QueryStream<T> desc() {
        return stream.thenSorted(desc);
    }

    /** Ascending with nulls first. */
    public QueryStream<T> ascNullsFirst() {
        return stream.thenSorted(asc.nullsFirst());
    }

    /** Descending with nulls last. */
    public QueryStream<T> descNullsLast() {
        return stream.thenSorted(desc.nullsLast());
    }
}
