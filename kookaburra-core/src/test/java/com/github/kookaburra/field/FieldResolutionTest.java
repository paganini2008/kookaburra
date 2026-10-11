package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class FieldResolutionTest {

    @Test
    void getterResolvesToInternedField() {
        IntField<Person> age = where(Person::getAge);
        assertThat(age.path()).isEqualTo("age");
        assertThat(age.entity()).isEqualTo(Person.class);
        assertThat(age.type()).isEqualTo(ValueType.INT32);
        assertThat(age).isSameAs(Fields.intField(Person.class, "age")).isSameAs(where(Person::getAge));
    }

    @Test
    void overloadsPickTypedFields() {
        assertThat(where(Person::getName)).isInstanceOf(StringField.class);
        assertThat(where(Person::getBalance)).isInstanceOf(LongField.class);
        assertThat(where(Person::getWeight)).isInstanceOf(DoubleField.class);
        assertThat(where(Person::isActive)).isInstanceOf(BoolField.class);
        assertThat(where(Person::getShortValue)).isInstanceOf(IntField.class);
        assertThat(where(Person::getScore)).isInstanceOf(IntField.class);     // boxed Integer → IntField
        assertThat(where(Person::getLevel)).isInstanceOf(EnumField.class);
        assertThat(where(Person::getBirthday).type()).isEqualTo(ValueType.DATE);
        assertThat(where(Person::getTags)).isInstanceOf(ListField.class);
        assertThat(where(Person::getEmployer)).isInstanceOf(RefField.class);
        assertThat(where(Person::getHome).type()).isEqualTo(ValueType.EMBEDDED);
        assertThat(where(Person::getAttributes).type()).isEqualTo(ValueType.MAP);
    }

    @Test
    void listElementAndRefTarget() {
        assertThat(where(Person::getTags).elementType()).isEqualTo(String.class);
        assertThat(where(Person::getEmployer).target().getSimpleName()).isEqualTo("Company");
    }

    @Test
    void embeddedPathsAreNullSafe() {
        StringField<Person> city = where(Person::getHome).then(Address::getCity);
        assertThat(city.path()).isEqualTo("home.city");
        assertThat(city).isSameAs(Fields.stringField(Person.class, "home.city"));
        assertThat(city.get(new Person().home(new Address("Perth", "6000")))).isEqualTo("Perth");
        assertThat(city.get(new Person())).isNull();
    }

    @Test
    void valuesAreNormalized() {
        Person p = new Person("a", "b", 1).shortValue((short) 7);
        Object v = where(Person::getShortValue).get(p);
        assertThat(v).isEqualTo(7).isInstanceOf(Integer.class);
    }

    @Test
    void inheritedPropertiesKeepSubclassEntity() {
        StringField<Dog> dogName = where(Dog::getName);
        StringField<Animal> animalName = where(Animal::getName);
        assertThat(dogName.entity()).isEqualTo(Dog.class);
        assertThat(dogName.path()).isEqualTo("name");
        assertThat(dogName).isNotEqualTo(animalName);
        assertThat(where(Dog::getBreed).path()).isEqualTo("breed");
    }

    @Test
    void rejectsLambdasAndNonProperties() {
        assertThatThrownBy(() -> where((IntGetter<Person>) p -> p.getAge() + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("method reference");
        assertThatThrownBy(() -> where(Person::computeSomething))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("persistent field");
        assertThatThrownBy(() -> where(Person::getCache))          // @Transient
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(Address::getCity))          // not an @Entity
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an @Entity");
    }

    @Test
    void rejectsWrongKindsAndBadPaths() {
        assertThatThrownBy(() -> Fields.intField(Person.class, "name"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STRING");
        assertThatThrownBy(() -> Fields.of(Person.class, "name.x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not @Embeddable");
        assertThatThrownBy(() -> Fields.of(Person.class, "nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No persistent field");
    }

    @Test
    void enumFieldTyping() {
        EnumField<Person, Level> level = Fields.enumField(Person.class, "level");
        assertThat(level.eq(Level.HIGH).test(new Person().level(Level.HIGH))).isTrue();
        assertThat(level).isSameAs(where(Person::getLevel));
        assertThat(List.of(level.path())).containsExactly("level");
    }
}
