package com.github.kookaburra.query;

import com.github.kookaburra.TestDbs;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Company;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.github.kookaburra.query.Q.or;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chained query style: {@code query(X).where(X::getY).eq(v).orderBy(X::getZ).desc().list()}, typed and dynamic,
 * equivalent to {@code from(X).filter(Q.where(...)...)}.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class FluentQueryTest {

    private final ObjectDB db = TestDbs.create();
    private long acme;

    @BeforeEach
    void data() {
        Company c = new Company();
        c.setIndustry("AI");
        acme = db.insert(c);
        db.insert(new Person("Ann", "Sydney", 30).score(90).weight(60.5).active(true).level(Level.HIGH)
                .tags(List.of("a", "b")).employer(Ref.of(acme)).birthday(LocalDate.of(1990, 1, 1)));
        db.insert(new Person("Bob", "Perth", 40).score(70).weight(80.0).active(false).level(Level.LOW).tags(List.of("b")));
        db.insert(new Person("Cid", "Sydney", 25).weight(72.0).active(true).level(Level.MEDIUM));
        db.insert(new Person("Dee", null, 35).score(50).weight(55.0).active(false));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private List<String> names(QueryStream<Person> q) {
        return q.list().stream().map(Person::getName).toList();
    }

    @Test
    void conditionsChainAndMatchTheFilterStyle() {
        assertThat(names(db.query(Person.class).where(Person::getCity).eq("Sydney").orderBy(Person::getName).asc()))
                .containsExactly("Ann", "Cid");
        assertThat(names(db.query(Person.class).where(Person::getCity).eq("Sydney").where(Person::getAge).gt(26)))
                .isEqualTo(names(db.from(Person.class).filter(where(Person::getCity).eq("Sydney"))
                        .filter(where(Person::getAge).gt(26))));
        assertThat(names(db.query(Person.class).where(Person::getAge).ge(35).orderBy(Person::getAge).asc())).containsExactly("Dee", "Bob");
        assertThat(names(db.query(Person.class).where(Person::getAge).lt(30))).containsExactly("Cid");
        assertThat(names(db.query(Person.class).where(Person::getAge).le(30).orderBy(Person::getAge).desc())).containsExactly("Ann", "Cid");
        assertThat(names(db.query(Person.class).where(Person::getAge).between(30, 35).orderBy(Person::getAge).asc())).containsExactly("Ann", "Dee");
        assertThat(names(db.query(Person.class).where(Person::getName).notEq("Ann").orderBy(Person::getName).asc()))
                .containsExactly("Bob", "Cid", "Dee");
        assertThat(names(db.query(Person.class).where(Person::getName).in("Bob", "Dee").orderBy(Person::getName).desc()))
                .containsExactly("Dee", "Bob");
        assertThat(names(db.query(Person.class).where(Person::getName).in(List.of("Cid")))).containsExactly("Cid");
        assertThat(names(db.query(Person.class).where(Person::getName).startsWith("D"))).containsExactly("Dee");
        assertThat(names(db.query(Person.class).where(Person::getCity).isNull())).containsExactly("Dee");
        assertThat(db.query(Person.class).where(Person::getCity).isNotNull().count()).isEqualTo(3);
        assertThat(db.query(Person.class).where(Person::isActive).isTrue().count()).isEqualTo(2);
        assertThat(db.query(Person.class).where(Person::isActive).isFalse().count()).isEqualTo(2);
        assertThat(names(db.query(Person.class).where(Person::getTags).contains("a"))).containsExactly("Ann");
        assertThat(names(db.query(Person.class).where(Person::getEmployer).eq(acme))).containsExactly("Ann");
        assertThat(db.query(Person.class).where(Person::getEmployer).notEq(acme).count()).isEqualTo(3);
        assertThat(db.query(Person.class).where(Person::getEmployer).in(acme, 999L).count()).isEqualTo(1);
        assertThat(names(db.query(Person.class).where(Person::getBalance).eq(0L).where(Person::getWeight).gt(70.0)
                .orderBy(Person::getWeight).descNullsLast())).containsExactly("Bob", "Cid");
        assertThat(names(db.query(Person.class).where(Person::getLevel).eq(Level.HIGH))).containsExactly("Ann");
        assertThat(names(db.query(Person.class).where(Person::getBirthday).isNotNull())).containsExactly("Ann");
        assertThat(names(db.query(Person.class).where(Person::getHome).isNull().where(Person::getScore).isNull())).containsExactly("Cid");
        assertThat(names(db.query(Person.class).orderBy(Person::getScore).ascNullsFirst().limit(2))).containsExactly("Cid", "Dee");
        assertThat(names(db.query(Person.class).orderBy(Person::isActive).desc().orderBy(Person::getName).asc()))
                .containsExactly("Ann", "Cid", "Bob", "Dee");
        assertThat(names(db.query(Person.class).orderBy(Person::getLevel).asc().limit(1))).containsExactly("Bob");
        assertThat(names(db.query(Person.class).orderBy(Person::getBirthday).asc().limit(1))).containsExactly("Ann");
        // OR / NOT stay available through filter(...) in the same chain
        assertThat(names(db.query(Person.class).filter(or(where(Person::getAge).lt(26), where(Person::getAge).gt(39)))
                .orderBy(Person::getName).asc())).containsExactly("Bob", "Cid");
    }

    @Test
    void misuseIsReported() {
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getTags).eq("a")).hasMessageContaining("use contains");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getName).contains("a")).hasMessageContaining("collection field");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getAge).startsWith("1")).hasMessageContaining("string field");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getAge).isTrue()).hasMessageContaining("boolean field");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getTags).gt("a")).hasMessageContaining("comparable field");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getHome).eq(null)).hasMessageContaining("comparable or reference");
        assertThatThrownBy(() -> db.query(Person.class).where(Person::getEmployer).eq(null)).hasMessageContaining("compare with an OID");
        assertThatThrownBy(() -> db.query(Person.class).limit(1).orderBy(Person::getAge).asc()).hasMessageContaining("orderBy() must come before");
        assertThatThrownBy(() -> db.query(Person.class).where("name")).hasMessageContaining("are for dynamic queries");
        assertThatThrownBy(() -> db.query(Person.class).orderBy("name")).hasMessageContaining("where(Person::get...)");
    }

    @Test
    void dynamicPaths() {
        DynamicCollection people = db.dynamic("Person");
        List<DynamicObject> sydney = people.from().where("city").eq("Sydney").orderBy("age").desc().list();
        assertThat(sydney).extracting(d -> d.get("name")).containsExactly("Ann", "Cid");
        assertThat(people.from().where("age").gt(30).count()).isEqualTo(2);
        assertThat(people.from().where("age").ge(35).count()).isEqualTo(2);
        assertThat(people.from().where("age").lt(30).count()).isEqualTo(1);
        assertThat(people.from().where("age").le(30).count()).isEqualTo(2);
        assertThat(people.from().where("age").between(25, 30).count()).isEqualTo(2);
        assertThat(people.from().where("name").notEq("Ann").count()).isEqualTo(3);
        assertThat(people.from().where("name").in("Ann", "Bob").count()).isEqualTo(2);
        assertThat(people.from().where("name").startsWith("C").count()).isEqualTo(1);
        assertThat(people.from().where("tags").contains("b").count()).isEqualTo(2);
        assertThat(people.from().where("active").isTrue().count()).isEqualTo(2);
        assertThat(people.from().where("active").isFalse().count()).isEqualTo(2);
        assertThat(people.from().where("city").isNull().count()).isEqualTo(1);
        assertThat(people.from().orderBy("name").asc().limit(1).list()).extracting(d -> d.get("name")).containsExactly("Ann");
        assertThatThrownBy(() -> people.from().where("nope")).hasMessageContaining("no stored field 'nope'");
    }
}
