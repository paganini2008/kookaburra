package com.github.kookaburra.mapping;

import com.github.kookaburra.Config;
import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Renamed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * References inside collections keep their target class in the schema ({@code List<Ref<Member>>} is
 * {@code LIST(REF(GenericRefTest$Member))}, not {@code REF(Object)}): lists, sets and map values; exported code shows the generic
 * type; a foreign key over a list checks the class of what it references; a database stored with the old
 * {@code REF(Object)} reads under the new type with one more layout. In memory and on disk.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
class GenericRefTest {

    @TempDir
    Path dir;

    @Entity
    public static class Member {
        @Id long oid;
        String name;

        public Member() {
        }

        Member(String name) {
            this.name = name;
        }
    }

    @Entity
    public static class Other {
        @Id long oid;
    }

    @Entity
    public static class Team {
        @Id long oid;
        @ForeignKey List<Ref<Member>> members;
        Set<Ref<Member>> fans;
        Map<String, Ref<Member>> roles;

        public Team() {
        }
    }

    /** Version 1 as stored before the fix: the element target unknown. */
    @Entity
    public static class Board {
        @Id long oid;
        List<Ref<?>> seats;

        public Board() {
        }
    }

    @Entity
    @Renamed("com.github.kookaburra.mapping.GenericRefTest$Board")
    public static class Board2 {
        @Id long oid;
        List<Ref<Member>> seats;

        public Board2() {
        }
    }

    private static TypeDef type(ObjectDB db, Class<?> c, String field) {
        return db.dynamic(c.getName()).def().current().fields().stream().filter(f -> f.name().equals(field))
                .findFirst().orElseThrow().type();
    }

    private void check(ObjectDB db) {
        Member ann = new Member("Ann");
        long a = db.insert(ann);
        Team t = new Team();
        t.members = List.of(Ref.of(a));
        t.fans = Set.of(Ref.of(a));
        t.roles = Map.of("lead", Ref.of(a));
        db.insert(t);
        String member = Member.class.getName();
        assertThat(type(db, Team.class, "members").toString()).isEqualTo("LIST(REF(GenericRefTest$Member))");
        assertThat(type(db, Team.class, "members").element().name()).isEqualTo(member);
        assertThat(type(db, Team.class, "fans").toString()).isEqualTo("SET(REF(GenericRefTest$Member))");
        assertThat(type(db, Team.class, "roles").toString()).isEqualTo("MAP(STRING, REF(GenericRefTest$Member))");

        String code = db.exportSchema((String) null).files().get(0).source();
        assertThat(code).contains("private List<Ref<Member>> members;", "private Set<Ref<Member>> fans;",
                "private Map<String, Ref<Member>> roles;");

        long other = db.insert(new Other());
        Team bad = new Team();
        bad.members = List.of(Ref.of(other));                                   // not a Member
        assertThatThrownBy(() -> db.insert(bad)).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("is not a " + member);
        assertThat(db.from(Team.class).first().orElseThrow().members.get(0).oid()).isEqualTo(a);
    }

    @Test
    void inMemory() {
        try (ObjectDB db = ObjectDB.inMemory(Config.defaults())) {
            check(db);
        }
    }

    @Test
    void onDiskAndOldSchema() {
        Path p = dir.resolve("db");
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {
            check(db);
            long m = db.from(Member.class).first().orElseThrow().oid;
            Board b = new Board();
            b.seats = List.of(Ref.of(m));
            db.insert(b);
            db.flush();
            assertThat(type(db, Board.class, "seats").toString()).isEqualTo("LIST(REF(Object))");
        }
        try (ObjectDB db = ObjectDB.open(p, Config.defaults())) {                   // reopen: types kept
            assertThat(type(db, Team.class, "members").toString()).isEqualTo("LIST(REF(GenericRefTest$Member))");
            Board2 b = db.from(Board2.class).first().orElseThrow();                  // the class as it is now
            assertThat(b.seats).hasSize(1);
            ClassDef def = db.dynamic(Board2.class.getName()).def();
            assertThat(def.layouts()).hasSize(2);                                    // one more layout, data kept
            assertThat(type(db, Board2.class, "seats").toString()).isEqualTo("LIST(REF(GenericRefTest$Member))");
            assertThat(db.get(Member.class, b.seats.get(0).oid()).name).isEqualTo("Ann");
        }
    }
}
