package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Gadget;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.model.Specs;
import org.junit.jupiter.api.Test;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class EmbeddedNavigationTest {

    /** What the annotation processor generates for {@code Gadget.specs}. */
    static class Specs_ extends EmbeddedPath<Gadget, Specs> {
        Specs_() {
            super(Gadget.class, "specs");
        }

        final IntField<Gadget> count = Fields.intField(Gadget.class, "specs.count");
    }

    @Test
    void thenCoversEveryFieldKind() {
        ObjectField<Gadget, Specs> specs = where(Gadget::getSpecs);
        assertThat(specs.then(Specs::getCount).path()).isEqualTo("specs.count");
        assertThat(specs.then(Specs::getTotal)).isInstanceOf(LongField.class);
        assertThat(specs.then(Specs::getRatio)).isInstanceOf(DoubleField.class);
        assertThat(specs.then(Specs::isEnabled)).isInstanceOf(BoolField.class);
        assertThat(specs.then(Specs::getLabel)).isInstanceOf(StringField.class);
        assertThat(specs.then(Specs::getSince).type()).isEqualTo(ValueType.DATE);
        assertThat(specs.then(Specs::getParts)).isInstanceOf(ListField.class);
        assertThat(specs.then(Specs::getMaker)).isInstanceOf(RefField.class);
        assertThat(specs.then(Specs::getExtra).type()).isEqualTo(ValueType.MAP);
        StringField<Gadget> city = specs.then(Specs::getOrigin).then(Address::getCity);
        assertThat(city.path()).isEqualTo("specs.origin.city");
        assertThat(city.chain()).hasSize(3);
        assertThat(city.javaType()).isEqualTo(String.class);
    }

    @Test
    void generatedEmbeddedPath() {
        Specs_ s = new Specs_();
        assertThat(s.field()).isSameAs(where(Gadget::getSpecs));
        assertThat(s.count).isSameAs(where(Gadget::getSpecs).then(Specs::getCount));
        assertThat(s.isNull().test(new Gadget())).isTrue();
        assertThat(s.isNotNull().test(new Gadget())).isFalse();
        assertThat(s.toString()).isEqualTo("Gadget.specs");
        assertThatThrownBy(() -> new EmbeddedPath<Gadget, Object>(Gadget.class, "specs.extra") {
        }).hasMessageContaining("not an @Embeddable");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void thenRejectsMisuse() {
        // The generic signatures reject these at compile time; raw types reach the runtime checks.
        StringGetter<Address> city = Address::getCity;
        ObjectField attributes = where(Person::getAttributes);
        assertThatThrownBy(() -> attributes.then(city)).isInstanceOf(IllegalStateException.class);
        ObjectField specs = where(Gadget::getSpecs);
        assertThatThrownBy(() -> specs.then(city)).hasMessageContaining("is not a property of");
    }

    @Test
    void propertyDetails() {
        Property p = Property.find(Person.class, "tags").orElseThrow();
        assertThat(p.elementType()).isEqualTo(String.class);
        assertThat(Property.find(Person.class, "cache")).isEmpty();
        assertThat(Property.find(Person.class, "missing")).isEmpty();
        assertThat(Property.typeArg(String.class, 0, 1)).isNull();
        assertThat(GetterResolver.decapitalize("URL")).isEqualTo("URL");
        assertThat(GetterResolver.decapitalize("Age")).isEqualTo("age");
        assertThatThrownBy(() -> p.read("not a person")).isInstanceOf(ClassCastException.class);
    }
}
