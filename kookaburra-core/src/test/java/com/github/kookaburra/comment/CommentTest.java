package com.github.kookaburra.comment;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.annotation.Comment;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Renamed;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Comment} on classes, fields and embeddable fields: kept in the schema across a flush and a reopen, readable
 * from the class definition; changing only comments keeps the stored layout (no new schema version), and data
 * written before stays readable.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class CommentTest {

    @TempDir
    Path dir;

    @Embeddable
    public static class Addr {
        @Comment("city or town") String city;

        public Addr() {
        }

        Addr(String city) {
            this.city = city;
        }
    }

    @Entity
    @Comment("a shop customer")
    public static class Buyer {
        @Id long oid;
        @Index @Comment("full name") String name;
        int age;
        Addr addr;

        public Buyer() {
        }

        Buyer(String name, int age, String city) {
            this.name = name;
            this.age = age;
            this.addr = new Addr(city);
        }
    }

    /** Buyer with other comments only (renamed so both versions exist in one JVM). */
    @Entity
    @Renamed("com.github.kookaburra.comment.CommentTest$Buyer")
    @Comment("a customer of the shop")
    public static class Buyer2 {
        @Id long oid;
        @Index @Comment("given and family name") String name;
        @Comment("in years") int age;
        Addr addr;

        public Buyer2() {
        }
    }

    private static FieldDef field(ClassDef c, String name) {
        return c.current().fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertV1(ClassDef c) {
        assertThat(c.comment()).isEqualTo("a shop customer");
        assertThat(field(c, "name").comment()).isEqualTo("full name");
        assertThat(field(c, "age").comment()).isNull();
        assertThat(field(c, "addr").type().fields().get(0).comment()).isEqualTo("city or town");
    }

    @Test
    void inMemory() {
        try (ObjectDB db = ObjectDB.inMemory(Config.defaults())) {
            db.insert(new Buyer("Ann", 30, "Perth"));
            assertV1(db.dynamic(Buyer.class.getName()).def());
        }
    }

    @Test
    void persistedAndChangedWithoutNewLayout() {
        Path p = dir.resolve("db");
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {
            db.insert(new Buyer("Ann", 30, "Perth"));
            db.flush();
            db.insert(new Buyer("Bob", 40, "Sydney"));                       // stays in the WAL
        }
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {
            ClassDef c = db.dynamic(Buyer.class.getName()).def();
            assertV1(c);
            assertThat(c.layouts()).hasSize(1);
            assertThat(db.from(Buyer.class).count()).isEqualTo(2);
        }
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {
            assertThat(db.from(Buyer2.class).count()).isEqualTo(2);           // registers the new comments
            ClassDef c = db.dynamic(Buyer2.class.getName()).def();
            assertThat(c.layouts()).hasSize(1);                                // comments only: same layout
            assertThat(c.comment()).isEqualTo("a customer of the shop");
            assertThat(field(c, "name").comment()).isEqualTo("given and family name");
            assertThat(field(c, "age").comment()).isEqualTo("in years");
            assertThat(db.oql("SELECT b.name FROM Buyer2 b WHERE b.addr.city = 'Perth'").list()).containsExactly("Ann");
        }
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {
            ClassDef c = db.dynamic(Buyer2.class.getName()).def();
            assertThat(c.layouts()).hasSize(1);
            assertThat(field(c, "age").comment()).isEqualTo("in years");
            assertThat(c.comment()).isEqualTo("a customer of the shop");
        }
    }
}
