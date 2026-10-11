package com.github.kookaburra.query;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Ref;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Company;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static com.github.kookaburra.query.Q.count;
import static com.github.kookaburra.query.Q.key;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QueryStream / Projection / GroupStream API behaviour beyond the differential suite.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class QueryApiTest {

    private ObjectDB db;
    private long aiCompany;

    @BeforeEach
    void setUp() {
        db = ObjectDB.inMemory();
        Company ai = new Company();
        ai.setIndustry("AI");
        aiCompany = db.insert(ai);
        Company mining = new Company();
        mining.setIndustry("Mining");
        long miningCompany = db.insert(mining);
        db.insert(new Person("Ann", "Sydney", 30).score(90).level(Level.HIGH).tags(List.of("vip"))
                .home(new Address("Perth", "6000")).employer(Ref.of(aiCompany)));
        db.insert(new Person("Bob", "Perth", 45).score(70).level(Level.LOW).employer(Ref.of(miningCompany)));
        db.insert(new Person("Cid", "Sydney", 20).level(Level.MEDIUM).tags(List.of("new", "vip")));
        db.insert(new Person("Dee", null, 61).score(10).weight(80.5));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void pipelineOrderIsEnforced() {
        QueryStream<Person> sorted = db.from(Person.class).sorted(where(Person::getAge).asc());
        assertThatThrownBy(() -> sorted.filter(p -> true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sorted.sorted(where(Person::getAge).asc())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).limit(1).filter(p -> true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).sorted()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).skip(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).limit(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sorted.groupBy(where(Person::getCity))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).groupBy()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).aggregate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).groupBy(where(Person::getTags))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).groupBy(where(Person::getCity)).agg(key(where(Person::getCity))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void slicing() {
        QueryStream<Person> byAge = db.from(Person.class).sorted(where(Person::getAge).asc());
        assertThat(byAge.skip(1).limit(2).toList()).extracting(Person::getName).containsExactly("Ann", "Bob");
        assertThat(byAge.limit(3).skip(1).toList()).extracting(Person::getName).containsExactly("Ann", "Bob");
        assertThat(byAge.limit(3).limit(5).count()).isEqualTo(3);
        assertThat(byAge.skip(10).count()).isZero();
        assertThat(byAge.skip(3).exists()).isTrue();
        assertThat(byAge.skip(4).exists()).isFalse();
        assertThat(byAge.limit(0).exists()).isFalse();
        assertThat(db.from(Person.class).limit(0).toList()).isEmpty();
        assertThat(byAge.limit(0).toList()).isEmpty();
        assertThat(byAge.first()).map(Person::getName).contains("Cid");
        assertThat(db.from(Person.class).filter(where(Person::getAge).gt(100)).first()).isEmpty();
        assertThat(db.from(Person.class).limit(2).toList()).hasSize(2);
        List<String> names = new ArrayList<>();
        byAge.forEach(p -> names.add(p.getName()));
        assertThat(names).containsExactly("Cid", "Ann", "Bob", "Dee");
    }

    @Test
    void projections() {
        assertThat(db.from(Person.class).sorted(where(Person::getName).asc()).map(where(Person::getCity)).toList())
                .containsExactly("Sydney", "Perth", "Sydney", null);
        assertThat(db.from(Person.class).map(where(Person::getCity)).distinct().count()).isEqualTo(3);
        assertThat(db.from(Person.class).sorted(where(Person::getName).asc()).map(where(Person::getName)).skip(1).limit(2).toList())
                .containsExactly("Bob", "Cid");
        assertThat(db.from(Person.class).map(where(Person::getName)).limit(2).skip(1).toList()).hasSize(1);
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Ann"))
                .select(where(Person::getName), where(Person::getLevel)).first())
                .contains(new Tuple2<>("Ann", Level.HIGH));
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Ann"))
                .select(where(Person::getName), where(Person::getAge), where(Person::getTags)).first().orElseThrow())
                .isEqualTo(new Tuple3<>("Ann", 30, List.of("vip")));
        record NameAge(String name, Integer age) { }
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Bob"))
                .select(NameAge::new, where(Person::getName), where(Person::getAge)).toList())
                .containsExactly(new NameAge("Bob", 45));
        record Three(String n, Integer a, Ref<Company> e) { }
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Bob"))
                .select(Three::new, where(Person::getName), where(Person::getAge), where(Person::getEmployer))
                .first().orElseThrow().e()).isInstanceOf(Ref.class);
        Address home = db.from(Person.class).filter(where(Person::getName).eq("Ann")).map(where(Person::getHome)).first().orElseThrow();
        assertThat(home.getCity()).isEqualTo("Perth");
        assertThat(db.from(Person.class).map(where(Person::getOid)).toList()).hasSize(4).allMatch(o -> o > 0);
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Zed")).map(where(Person::getName)).first()).isEmpty();
        List<String> seen = new ArrayList<>();
        db.from(Person.class).map(where(Person::getName)).forEach(seen::add);
        assertThat(seen).hasSize(4);
        assertThat(db.from(Person.class).map(where(Person::getName)).stream().count()).isEqualTo(4);
    }

    @Test
    void aggregatesAndTerminals() {
        QueryStream<Person> all = db.from(Person.class);
        assertThat(all.sum(where(Person::getAge))).isEqualTo(156);
        assertThat(all.sum(where(Person::getBalance))).isZero();
        assertThat(all.sum(where(Person::getWeight))).isEqualTo(80.5);
        assertThat(all.avg(where(Person::getScore))).hasValue(170 / 3.0);
        assertThat(all.min(where(Person::getAge))).contains(20);
        assertThat(all.max(where(Person::getName))).contains("Dee");
        assertThat(all.max(where(Person::getLevel))).contains(Level.HIGH);
        QueryStream<Person> none = all.filter(where(Person::getAge).gt(100));
        assertThat(none.avg(where(Person::getAge))).isEmpty();
        assertThat(none.min(where(Person::getAge))).isEmpty();
        assertThat(none.sum(where(Person::getAge))).isZero();
        Row r = all.aggregate(count(), Q.countDistinct(where(Person::getCity)), Q.max(where(Person::getOid)));
        assertThat(r.getLong("count(*)")).isEqualTo(4);
        assertThat(r.get(1)).isEqualTo(2L);
        assertThat((Long) r.get(2)).isPositive();
        Row sliced = all.sorted(where(Person::getAge).asc()).limit(2).aggregate(Q.sum(where(Person::getAge)));
        assertThat(sliced.getLong("sum(age)")).isEqualTo(50);
    }

    @Test
    void groups() {
        List<Row> rows = db.from(Person.class).groupBy(where(Person::getCity), where(Person::getLevel))
                .agg(count(), Q.avg(where(Person::getAge)).as("avgAge"), Q.min(where(Person::getLevel)))
                .sortedBy(key(where(Person::getCity)).asc(), key(where(Person::getLevel)).desc())
                .toList();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("city")).isEqualTo("Perth");
        assertThat(rows.get(3).get(where(Person::getCity))).isNull();
        assertThat(rows.get(1).get(where(Person::getLevel))).isEqualTo(Level.HIGH);
        assertThat(rows.get(1).get("min(level)")).isEqualTo(Level.HIGH);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).toList()).hasSize(3);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(count()).having(count().eq(2L))
                .first().orElseThrow().get("city")).isEqualTo("Sydney");
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(count()).having(key(where(Person::getCity)).eq("Perth"))
                .count()).isEqualTo(1);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(Q.max(where(Person::getScore)))
                .having(Q.max(where(Person::getScore)).lt(80)).toList()).hasSize(2);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(count()).having(count().ne(1L)).count()).isEqualTo(1);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(count()).having(count().ge(2)).count()).isEqualTo(1);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(count()).having(count().le(1)).count()).isEqualTo(2);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(Q.avg(where(Person::getScore)))
                .sortedBy(Q.avg(where(Person::getScore)).asc()).toList()).extracting(x -> x.get(1)).containsExactly(10.0, 70.0, 90.0);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(Q.avg(where(Person::getScore)))
                .sortedBy(Q.avg(where(Person::getScore)).desc()).first().orElseThrow().get(1)).isEqualTo(90.0);
        assertThat(db.from(Person.class).filter(where(Person::getAge).gt(100)).groupBy(where(Person::getCity)).first()).isEmpty();
        List<Row> seen = new ArrayList<>();
        db.from(Person.class).groupBy(where(Person::getCity)).forEach(seen::add);
        assertThat(seen).hasSize(3);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).stream().count()).isEqualTo(3);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).skip(1).limit(1).toList()).hasSize(1);
        assertThatThrownBy(() -> db.from(Person.class).groupBy(where(Person::getCity)).skip(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).groupBy(where(Person::getCity)).limit(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).map(where(Person::getCity)).skip(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Person.class).map(where(Person::getCity)).limit(-1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rowsAndAggs() {
        Row r = new Row(List.of("a", "b"), new Object[]{1L, "x"});
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.names()).containsExactly("a", "b");
        assertThat(r).isEqualTo(new Row(List.of("a", "b"), new Object[]{1L, "x"})).hasSameHashCodeAs(new Row(List.of("a", "b"), new Object[]{1L, "x"}));
        assertThat(r.toString()).isEqualTo("{a=1, b=x}");
        assertThatThrownBy(() -> r.get("c")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> new Row(List.of("a"), new Object[0])).isInstanceOf(IllegalArgumentException.class);
        Agg<Person> avg = Q.avg(where(Person::getAge));
        assertThat(avg.kind()).isEqualTo(Agg.Kind.AVG);
        assertThat(avg.field()).isSameAs(where(Person::getAge));
        assertThat(avg.toString()).isEqualTo("avg(age)");
        assertThat(count().field()).isNull();
        assertThat(Agg.compare(1, 1.5)).isNegative();
        assertThat(Agg.compare("b", "a")).isPositive();
        assertThat(Q.count().asc().compare(new Row(List.of("count(*)"), new Object[]{null}), new Row(List.of("count(*)"), new Object[]{1L}))).isPositive();
        assertThat(Q.count().asc().compare(new Row(List.of("count(*)"), new Object[]{null}), new Row(List.of("count(*)"), new Object[]{null}))).isZero();
        assertThat(Q.count().gt(0).test(new Row(List.of("count(*)"), new Object[]{null}))).isFalse();
        assertThatThrownBy(() -> Q.sum(Fields.objectField(Person.class, "attributes"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referenceSubqueriesAndSystemFields() {
        List<Person> ai = db.from(Person.class)
                .filter(where(Person::getEmployer).matches(where(Company::getIndustry).eq("AI"))).toList();
        assertThat(ai).extracting(Person::getName).containsExactly("Ann");
        assertThat(db.from(Person.class).filter(where(Person::getEmployer).matches(where(Company::getIndustry).eq("None"))).count()).isZero();
        assertThat(db.from(Person.class).filter(where(Person::getEmployer).eq(aiCompany)).count()).isEqualTo(1);
        long annOid = ai.get(0).getOid();
        assertThat(db.from(Person.class).filter(where(Person::getOid).eq(annOid)).toList()).extracting(Person::getName).containsExactly("Ann");
        assertThat(db.from(Person.class).filter(where(Person::getOid).in(annOid, 1L)).count()).isEqualTo(1);
        assertThat(db.from(Person.class).filter(where(Person::getOid).isNull()).count()).isZero();
        assertThat(db.from(Person.class).filter(where(Person::getOid).gt(annOid)).count()).isEqualTo(3);
        assertThat(db.from(Person.class).filter(Fields.longField(Person.class, "version").eq(1L)).count()).isEqualTo(4);
        assertThat(db.from(Person.class).sorted(where(Person::getOid).desc()).toList().get(3).getName()).isEqualTo("Ann");
        assertThat(db.from(Person.class).sorted(Fields.longField(Person.class, "version").asc()).count()).isEqualTo(4);
    }

    @Test
    void referencesFetchAndSemiJoin() {
        List<Person> people = db.from(Person.class).filter(where(Person::getEmployer).isNotNull())
                .sorted(where(Person::getName).asc()).fetch(where(Person::getEmployer)).toList();
        assertThat(people).hasSize(2);
        assertThat(people).allMatch(p -> p.getEmployer().isResolved());
        assertThat(people.get(0).getEmployer().resolve(db).getIndustry()).isEqualTo("AI");
        Person cid = db.from(Person.class).filter(where(Person::getName).eq("Cid")).first().orElseThrow();
        assertThat(cid.getEmployer()).isNull();
        Person bob = db.from(Person.class).filter(where(Person::getName).eq("Bob")).first().orElseThrow();
        assertThat(bob.getEmployer().isResolved()).isFalse();
        assertThat(bob.getEmployer().resolve(db).getIndustry()).isEqualTo("Mining");
        assertThat(bob.getEmployer().isResolved()).isTrue();
        assertThat(Ref.<Company>of(999_999).resolve(db)).isNull();

        QueryStream<Company> ai = db.from(Company.class).filter(where(Company::getIndustry).eq("AI"));
        assertThat(db.from(Person.class).filter(where(Person::getEmployer).in(ai)).toList())
                .extracting(Person::getName).containsExactly("Ann");
        assertThat(db.from(Person.class).filter(where(Person::getEmployer).in(ai.filter(where(Company::getIndustry).eq("x")))).count()).isZero();
        assertThat(db.from(Person.class).filter(where(Person::getEmployer).in(ai)).explain()).contains("employer IN");
        assertThatThrownBy(() -> where(Person::getEmployer).in(ai).test(bob)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(where(Person::getEmployer).in(ai).toString()).contains("sub-query");
        assertThat(db.get(aiCompany)).isInstanceOf(Company.class);
        assertThat(db.get(Long.MAX_VALUE)).isNull();
    }

    @Test
    void bulkUpdateAndDelete() {
        int n = db.from(Person.class).filter(where(Person::getCity).eq("Sydney")).updateEach(p -> p.score(1));
        assertThat(n).isEqualTo(2);
        assertThat(db.from(Person.class).filter(where(Person::getScore).eq(1)).count()).isEqualTo(2);
        assertThat(db.from(Person.class).filter(Fields.longField(Person.class, "version").eq(2L)).count()).isEqualTo(2);
        long deleted = db.from(Person.class).sorted(where(Person::getAge).desc()).limit(1).deleteAll();
        assertThat(deleted).isEqualTo(1);
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Dee")).exists()).isFalse();
        assertThat(db.from(Person.class).filter(where(Person::getAge).gt(1000)).deleteAll()).isZero();
        assertThat(db.from(Person.class).filter(where(Person::getAge).gt(1000)).updateEach(p -> { })).isZero();
        try (Snapshot old = db.snapshot()) {
            db.from(Person.class).updateEach(p -> p.score(2));
            assertThatThrownBy(() -> old.from(Person.class).updateEach(p -> p.score(3)))
                    .isInstanceOf(OptimisticLockException.class);
        }
        assertThat(db.from(Person.class).filter(where(Person::getScore).eq(2)).count()).isEqualTo(3);
    }

    @Test
    void cursorsStreamsAndExplain() {
        try (Cursor<Person> c = db.from(Person.class).sorted(where(Person::getAge).desc()).cursor()) {
            assertThat(c.next().getName()).isEqualTo("Dee");
            assertThat(c.hasNext()).isTrue();
        }
        Cursor<Person> c = db.from(Person.class).limit(1).cursor();
        c.next();
        assertThat(c.hasNext()).isFalse();
        assertThat(c.hasNext()).isFalse();
        assertThatThrownBy(c::next).isInstanceOf(NoSuchElementException.class);
        c.close();
        try (Stream<Person> s = db.from(Person.class).filter(where(Person::getCity).eq("Sydney")).stream()) {
            assertThat(s.map(Person::getName)).containsExactlyInAnyOrder("Ann", "Cid");
        }

        String plan = db.from(Person.class)
                .filter(where(Person::getCity).eq("Sydney").and(where(Person::getAge).gt(10)).and(p -> true)
                        .or(where(Person::getTags).contains("vip").negate()))
                .sorted(where(Person::getAge).desc()).skip(1).limit(5).explain();
        assertThat(plan).contains("QUERY Person", "mem postings", "scan", "decode rows", "OR", "AND", "NOT",
                "SORT age DESC", "top-N", "SLICE skip=1 limit=5");
        assertThat(db.from(Person.class).explain()).contains("FILTER none");
        assertThat(db.from(Person.class).sorted(where(Person::getAge).asc()).explain()).contains("full sort");
        assertThat(db.from(Dog.class).filter(where(Dog::getBreed).eq("x")).explain()).contains("TYPE IN", "segments=0");
        assertThat(db.from(Animal.class).filter(Q.instanceOf(Dog.class)).explain()).contains("FILTER");
        db.insert(new Dog("Rex", 1, "Lab"));
        assertThat(db.from(Animal.class).filter(Q.instanceOf(Dog.class)).filter(where(Animal::getAge).in(1, 2)).explain())
                .contains("[$type]");
        assertThat(db.from(Person.class).filter(where(Person::getOid).eq(1L)).explain()).contains("OID lookup");
        assertThat(db.from(Animal.class).filter(Q.instanceOf(Dog.class)).count()).isEqualTo(1);
    }
}
