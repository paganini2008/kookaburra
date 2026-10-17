package com.github.kookaburra.constraint;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Unique;

import java.util.List;

/**
 * Entities with unsupported constraint declarations.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class Bad {

    private Bad() {
    }

    @Entity
    static class UniqueList {
        @Id long oid;
        @Unique List<String> tags;
    }

    @Entity
    static class KeyOnString {
        @Id long oid;
        @ForeignKey String owner;
    }

    @Embeddable
    static class Line {
        @ForeignKey Ref<UniqueList> item;
    }

    @Entity
    static class InList {
        @Id long oid;
        List<Line> lines;
    }

    @Entity
    @Unique
    static class ClassWithoutFields {
        @Id long oid;
        String a;
    }

    @Entity
    @Unique(fields = {"a", "missing"})
    static class UnknownField {
        @Id long oid;
        String a;
    }

    @Entity
    @Unique(fields = {"a", "tags"})
    static class ListInCombination {
        @Id long oid;
        String a;
        List<String> tags;
    }

    @Entity
    @Unique(fields = {"a", "a"})
    static class Twice {
        @Id long oid;
        String a;
    }

    @Entity
    static class FieldsOnField {
        @Id long oid;
        @Unique(fields = {"a"}) String a;
    }
}
