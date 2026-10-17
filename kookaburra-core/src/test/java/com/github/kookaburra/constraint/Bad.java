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
}
