package com.github.kookaburra.query;

import com.github.kookaburra.Ref;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Cat;
import com.github.kookaburra.model.Company;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static com.github.kookaburra.query.Q.instanceOf;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class PredTest {

    private final Person ann = new Person("Ann", "Sydney", 30).score(90).tags(List.of("vip", "new"));
    private final Person bob = new Person("Bob", "Perth", 45).weight(-0.0);
    private final Person nul = new Person(null, null, 0);

    @Test
    void comparisonsAreFalseOnNull() {
        Pred<Person> p = where(Person::getCity).eq("Sydney");
        assertThat(p.test(ann)).isTrue();
        assertThat(p.test(bob)).isFalse();
        assertThat(p.test(nul)).isFalse();
        assertThat(where(Person::getScore).gt(10).test(bob)).isFalse();
        assertThat(where(Person::getName).startsWith("A").test(nul)).isFalse();
    }

    @Test
    void negationIsTwoValued() {
        Pred<Person> notSydney = where(Person::getCity).notEq("Sydney");
        assertThat(notSydney.test(nul)).isTrue();
        assertThat(notSydney.test(ann)).isFalse();
        assertThat(where(Person::getScore).gt(10).negate().test(bob)).isTrue();
        Pred<Person> p = where(Person::getAge).gt(1);
        assertThat(p.negate().negate()).isSameAs(p);
    }

    @Test
    void ranges() {
        var age = where(Person::getAge);
        assertThat(age.between(30, 45).test(ann)).isTrue();
        assertThat(age.between(30, false, 45, false).test(ann)).isFalse();
        assertThat(age.ge(45).test(bob)).isTrue();
        assertThat(age.lt(45).test(bob)).isFalse();
        assertThat(age.le(45).test(bob)).isTrue();
    }

    @Test
    void doublesFollowDoubleCompare() {
        var w = where(Person::getWeight);
        assertThat(w.eq(0.0).test(bob)).isFalse();          // -0.0 != 0.0
        assertThat(w.eq(-0.0).test(bob)).isTrue();
        assertThat(w.lt(0.0).test(bob)).isTrue();
        assertThat(w.in(0.0, -0.0).test(bob)).isTrue();
        assertThat(w.eq(Double.NaN).test(new Person().weight(Double.NaN))).isTrue();
    }

    @Test
    void inAndContains() {
        assertThat(where(Person::getCity).in("Perth", "Darwin").test(bob)).isTrue();
        var tags = where(Person::getTags);
        assertThat(tags.contains("vip").test(ann)).isTrue();
        assertThat(tags.containsAny("x", "new").test(ann)).isTrue();
        assertThat(tags.containsAll("vip", "x").test(ann)).isFalse();
        assertThat(tags.containsAll("vip", "new").test(ann)).isTrue();
        assertThat(tags.contains("vip").test(bob)).isFalse();
        assertThat(tags.isNull().test(bob)).isTrue();
    }

    @Test
    void andOrFlattenAndKeepMetadata() {
        Pred<Person> p = where(Person::getCity).eq("Sydney")
                .and(where(Person::getAge).gt(20))
                .and(where(Person::getName).eq("Ann"));
        assertThat(p).isInstanceOf(Pred.And.class);
        assertThat(((Pred.And<Person>) p).preds()).hasSize(3);
        Pred<Person> o = where(Person::getCity).eq("A").or(where(Person::getCity).eq("B")).or(where(Person::getCity).eq("C"));
        assertThat(((Pred.Or<Person>) o).preds()).hasSize(3);
        assertThat(p.toString()).isEqualTo("city = 'Sydney' AND age > 20 AND name = 'Ann'");
    }

    @Test
    void lambdasBecomeResiduals() {
        Predicate<Person> lambda = x -> x.getAge() > 40;
        Pred<Person> p = where(Person::getCity).isNotNull().and(lambda);
        Pred.And<Person> and = (Pred.And<Person>) p;
        assertThat(and.preds().get(1)).isInstanceOf(Pred.Residual.class);
        assertThat(p.test(bob)).isTrue();
        assertThat(p.test(ann)).isFalse();
    }

    @Test
    void instanceOfIsPolymorphic() {
        Pred<Animal> dogs = instanceOf(Dog.class);
        assertThat(dogs.test(new Dog("Rex", 3, "Lab"))).isTrue();
        assertThat(dogs.test(new Cat("Tom", 2, true))).isFalse();
        Pred<Animal> byName = Fields.stringField(Animal.class, "name").eq("Rex");
        assertThat(byName.test(new Dog("Rex", 3, "Lab"))).isTrue();
    }

    @Test
    void refs() {
        Person p = new Person().employer(Ref.of(42));
        assertThat(where(Person::getEmployer).eq(42).test(p)).isTrue();
        assertThat(where(Person::getEmployer).eq(Ref.of(41)).test(p)).isFalse();
        assertThatThrownBy(() -> where(Person::getEmployer).matches(where(Company::getIndustry).eq("AI")).test(p))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void nullValuesAreRejected() {
        assertThatThrownBy(() -> where(Person::getCity).eq(null)).hasMessageContaining("isNull");
        assertThatThrownBy(() -> where(Person::getCity).in("a", null)).hasMessageContaining("isNull");
        assertThatThrownBy(() -> where(Person::getAge).between(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
