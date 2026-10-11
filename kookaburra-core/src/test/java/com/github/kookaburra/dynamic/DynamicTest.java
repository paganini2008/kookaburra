package com.github.kookaburra.dynamic;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Ref;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Cat;
import com.github.kookaburra.model.Company;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.query.Order;
import com.github.kookaburra.query.Pred;
import com.github.kookaburra.query.QueryStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.github.kookaburra.query.DifferentialTest.randomPerson;
import static com.github.kookaburra.query.DifferentialTest.randomPred;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema-driven access must agree with typed access on the same data.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class DynamicTest {

    private final ObjectDB db = ObjectDB.inMemory(Config.defaults().withMemSegmentBytes(8192));

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void dynamicQueriesAgreeWithTypedQueries() {
        Random r = new Random(21);
        for (int i = 0; i < 400; i++) {
            db.insert(randomPerson(r));
        }
        db.flush();
        for (int i = 0; i < 100; i++) {
            db.insert(randomPerson(r));
        }
        DynamicCollection people = db.dynamic("Person");
        int compared = 0;
        for (int q = 0; q < 300; q++) {
            Pred<Person> typed = randomPred(r, 3);
            Pred<DynamicObject> dyn = translate(typed, people);
            if (dyn == null) {
                continue;
            }
            compared++;
            Set<Long> expected = new HashSet<>();
            db.from(Person.class).filter(typed).toList().forEach(p -> expected.add(p.getOid()));
            Set<Long> got = new HashSet<>();
            people.from().filter(dyn).toList().forEach(o -> got.add(o.oid()));
            assertThat(got).as(typed.toString()).isEqualTo(expected);
            assertThat(people.from().filter(dyn).count()).isEqualTo(expected.size());
        }
        assertThat(compared).isGreaterThan(150);

        List<String> typedNames = db.from(Person.class).sorted(where(Person::getAge).desc(), where(Person::getName).asc())
                .limit(20).map(where(Person::getName)).toList();
        List<Object> dynNames = new ArrayList<>();
        people.from().sorted(people.field("age").desc(), people.field("name").asc()).limit(20).toList()
                .forEach(o -> dynNames.add(o.get("name")));
        assertThat(dynNames).isEqualTo(new ArrayList<Object>(typedNames));
    }

    /** Typed predicate → equivalent dynamic predicate (null if it contains opaque Java code). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Pred<DynamicObject> translate(Pred<?> p, DynamicCollection c) {
        if (p instanceof Pred.And<?> a) {
            List<Pred<DynamicObject>> out = new ArrayList<>();
            for (Pred<?> x : a.preds()) {
                Pred<DynamicObject> t = translate(x, c);
                if (t == null) {
                    return null;
                }
                out.add(t);
            }
            return Pred.And.of(out);
        }
        if (p instanceof Pred.Or<?> o) {
            List<Pred<DynamicObject>> out = new ArrayList<>();
            for (Pred<?> x : o.preds()) {
                Pred<DynamicObject> t = translate(x, c);
                if (t == null) {
                    return null;
                }
                out.add(t);
            }
            return Pred.Or.of(out);
        }
        if (p instanceof Pred.Not<?> n) {
            Pred<DynamicObject> t = translate(n.pred(), c);
            return t == null ? null : t.negate();
        }
        if (p instanceof Pred.Eq<?, ?> e) {
            return c.field(e.field().path()).eq(e.value());
        }
        if (p instanceof Pred.In<?, ?> i) {
            return c.field(i.field().path()).in((Set) i.values());
        }
        if (p instanceof Pred.Range<?, ?> rg) {
            return c.field(rg.field().path()).between(rg.lo(), rg.loInclusive(), rg.hi(), rg.hiInclusive());
        }
        if (p instanceof Pred.IsNull<?> n) {
            return c.field(n.field().path()).isNull();
        }
        if (p instanceof Pred.StartsWith<?> s) {
            return c.field(s.field().path()).startsWith(s.prefix());
        }
        if (p instanceof Pred.Contains<?, ?> ct) {
            Object[] vals = ct.values().toArray();
            return ct.all() ? c.field(ct.field().path()).containsAll(vals) : c.field(ct.field().path()).containsAny(vals);
        }
        return null;
    }

    @Test
    void crudInteroperatesWithTypedObjects() {
        Company acme = new Company();
        acme.setIndustry("AI");
        long acmeOid = db.insert(acme);
        assertThatThrownBy(() -> db.dynamic(Person.class.getName())).hasMessageContaining("no stored class");
        db.from(Person.class);   // registers the class (as any typed access does)
        DynamicCollection people = db.dynamic(Person.class.getName());
        assertThat(people.name()).isEqualTo(Person.class.getName());
        assertThat(people.def().simpleName()).isEqualTo("Person");
        assertThat(people.fields()).extracting(f -> f.name()).contains("name", "home", "tags");

        DynamicObject ann = people.newObject()
                .set("name", "Ann").set("city", "Perth").set("age", 41).set("level", "HIGH")
                .set("home.city", "Hobart").set("tags", List.of("vip")).set("employer", acmeOid)
                .set("birthday", "2000-01-02").set("weight", 3).set("active", true);
        long oid = people.insert(ann);
        assertThat(ann.oid()).isEqualTo(oid);
        assertThat(ann.version()).isEqualTo(1);

        Person typed = db.get(Person.class, oid);
        assertThat(typed.getName()).isEqualTo("Ann");
        assertThat(typed.getHome().getCity()).isEqualTo("Hobart");
        assertThat(typed.getLevel()).isEqualTo(Level.HIGH);
        assertThat(typed.getEmployer().oid()).isEqualTo(acmeOid);
        assertThat(typed.getBirthday()).isEqualTo(LocalDate.of(2000, 1, 2));
        assertThat(typed.getWeight()).isEqualTo(3.0);

        Person bob = new Person("Bob", "Sydney", 30).home(new Address("Perth", "6000")).tags(List.of("x"));
        db.insert(bob);
        DynamicObject bobDyn = people.get(bob.getOid());
        assertThat(bobDyn.get("home.city")).isEqualTo("Perth");
        assertThat(bobDyn.get("home.zip")).isEqualTo("6000");
        assertThat(bobDyn.get("tags")).isEqualTo(List.of("x"));
        assertThat(bobDyn.get("nope.deeper")).isNull();
        assertThat(bobDyn.values()).containsKeys("name", "home");
        assertThat(bobDyn.className()).isEqualTo(Person.class.getName());
        assertThat(bobDyn.toString()).contains("Person#");

        DynamicObject again = people.get(oid);
        assertThat(again.get("employer")).isEqualTo(Ref.of(acmeOid));
        again.set("age", 42).remove("tags");
        people.update(again);
        assertThat(again.version()).isEqualTo(2);
        assertThat(db.get(Person.class, oid).getTags()).isNull();
        assertThatThrownBy(() -> people.update(ann)).isInstanceOf(OptimisticLockException.class);

        assertThat(people.from().filter(people.field("employer").eq(acmeOid)).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("oid").eq(oid)).toList()).singleElement()
                .extracting(DynamicObject::oid).isEqualTo(oid);
        assertThat(people.from().filter(people.field("version").eq(2)).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("home.city").startsWith("Ho")).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("tags").contains("x")).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("level").gt("LOW")).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("level").in(Level.HIGH, "MEDIUM")).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("age").notEq(42)).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("age").ge(30).and(people.field("age").lt(42)).and(people.field("age").le(30))).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("age").between(30, 42)).count()).isEqualTo(2);
        assertThat(people.from().filter(people.field("tags").containsAll("x")).count()).isEqualTo(1);
        assertThat(people.from().filter(people.field("tags").containsAny("x", "y")).count()).isEqualTo(1);

        people.delete(again);
        assertThat(people.get(oid)).isNull();
        assertThat(people.delete(bob.getOid())).isTrue();
        assertThat(people.delete(bob.getOid())).isFalse();
        assertThat(people.from().count()).isZero();
    }

    @Test
    void polymorphismAndValidation() {
        db.insert(new Dog("Rex", 3, "Lab"));
        db.insert(new Cat("Tom", 2, true));
        DynamicCollection animals = db.dynamic("Animal");
        DynamicCollection dogs = db.dynamic("Dog");
        assertThat(animals.from().count()).isEqualTo(2);
        assertThat(dogs.from().toList()).singleElement().extracting(DynamicObject::className).isEqualTo(Dog.class.getName());
        assertThat(animals.from().filter(animals.field("breed").eq("Lab")).count()).isEqualTo(1);
        assertThat(animals.get(dogs.from().toList().get(0).oid())).isNotNull();
        assertThat(dogs.get(animals.from().filter(animals.field("name").eq("Tom")).toList().get(0).oid())).isNull();
        assertThat(dogs.get(999)).isNull();
        DynamicObject puppy = dogs.newObject().set("name", "Pup").set("breed", "Pug");
        dogs.insert(puppy);
        assertThat(db.get(Dog.class, puppy.oid()).getBreed()).isEqualTo("Pug");

        assertThatThrownBy(() -> db.dynamic("Nope")).hasMessageContaining("no stored class");
        assertThatThrownBy(() -> animals.field("nope")).hasMessageContaining("no stored field");
        assertThatThrownBy(() -> dogs.insert(dogs.newObject().set("color", "x"))).hasMessageContaining("no stored field 'color'");
        assertThatThrownBy(() -> dogs.insert(dogs.newObject().set("age", "old"))).hasMessageContaining("not a valid INT32");
        assertThatThrownBy(() -> dogs.insert(puppy)).hasMessageContaining("already has OID");
        assertThatThrownBy(() -> dogs.update(dogs.newObject())).hasMessageContaining("no OID");
        assertThatThrownBy(() -> dogs.delete(dogs.newObject())).hasMessageContaining("no OID");
        assertThatThrownBy(() -> animals.field("name").eq(null)).hasMessageContaining("isNull");
        assertThatThrownBy(() -> animals.field("age").startsWith("x")).hasMessageContaining("not a string");
        assertThatThrownBy(() -> animals.field("age").contains(1)).hasMessageContaining("not a multi-valued");
        assertThat(animals.field("name").entity()).isEqualTo(DynamicObject.class);
        assertThat(animals.field("name")).isEqualTo(animals.field("name")).hasSameHashCodeAs(animals.field("name"));
        assertThat(animals.field("name").toString()).isEqualTo("name");
        assertThat(animals.field("name").typeDef().kind().name()).isEqualTo("STRING");
        Order<DynamicObject> o = animals.field("name").asc();
        assertThat(animals.from().sorted(o).toList()).extracting(x -> x.get("name")).containsExactly("Pup", "Rex", "Tom");
        assertThat(animals.from().sorted(animals.field("name").desc()).first()).map(x -> x.get("name")).contains("Tom");
        QueryStream<DynamicObject> typedIn = animals.from().filter(new Pred.TypeIn<>(Set.of(Cat.class.getName())));
        assertThat(typedIn.count()).isEqualTo(1);
        assertThat(new Pred.TypeIn<DynamicObject>(Set.of("x.Y")).test(animals.newObject())).isFalse();
        assertThat(new Pred.TypeIn<Object>(Set.of(Dog.class.getName())).test(new Dog())).isTrue();
        assertThat(animals.from().filter(new Pred.TypeIn<>(Set.of("x.Unknown"))).count()).isZero();
        assertThat(new Pred.TypeIn<>(Set.of(Dog.class.getName())).toString()).isEqualTo("TYPE IN [Dog]");
        assertThat(animals.from().explain()).contains("QUERY Animal");
    }

    @Test
    void dynamicReferenceMatch() {
        Company ai = new Company();
        ai.setIndustry("AI");
        long aiOid = db.insert(ai);
        db.insert(new Person("Ann", "X", 1).employer(Ref.of(aiOid)));
        db.insert(new Person("Bob", "X", 1));
        DynamicCollection people = db.dynamic("Person");
        DynamicCollection companies = db.dynamic("Company");
        Field<DynamicObject, ?> employer = people.field("employer");
        Pred<DynamicObject> m = new Pred.DynRefMatch<>(employer, companies.name(), companies.field("industry").eq("AI"));
        assertThat(people.from().filter(m).toList()).singleElement().extracting(x -> x.get("name")).isEqualTo("Ann");
        Pred<DynamicObject> none = new Pred.DynRefMatch<>(employer, companies.name(), companies.field("industry").eq("Z"));
        assertThat(people.from().filter(none).count()).isZero();
        assertThatThrownBy(() -> m.test(people.newObject())).isInstanceOf(UnsupportedOperationException.class);
        assertThat(m.toString()).contains("employer ->");
        // malformed temporal text: a field-qualified "not a valid" error, not a parser exception
        DynamicObject bad = people.newObject();
        bad.set("name", "Bad");
        bad.set("birthday", "2024-13-45");
        assertThatThrownBy(() -> people.insert(bad)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birthday").hasMessageContaining("is not a valid DATE");
        assertThat(db.from(Person.class).filter(where(Person::getName).eq("Ann")).map(where(Person::getEmployer)).toList())
                .containsExactly(Ref.of(aiOid));
    }
}
