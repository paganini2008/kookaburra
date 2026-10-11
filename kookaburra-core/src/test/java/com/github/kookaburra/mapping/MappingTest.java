package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Cat;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class MappingTest {

    private final AtomicReference<Schema> persisted = new AtomicReference<>();
    private final TypeRegistry registry = new TypeRegistry(Schema.empty(), getClass().getClassLoader(), persisted::set);

    @Test
    void registersPersonWithColumns() {
        EntityType<Person> t = registry.entity(Person.class);
        ClassDef def = t.def();
        assertThat(def.id()).isEqualTo(1);
        assertThat(def.isRoot()).isTrue();
        assertThat(def.idField()).isEqualTo("oid");
        assertThat(def.versionField()).isEqualTo("version");
        assertThat(def.current().fields()).extracting(FieldDef::name).containsExactly(
                "name", "city", "age", "score", "balance", "weight", "shortValue", "active", "level", "birthday",
                "home", "employer", "tags", "attributes", "bio");
        assertThat(registry.schema().columns(def.collection())).containsOnlyKeys("name", "city", "age", "home.city", "tags");
        assertThat(registry.schema().columns(def.collection()).get("age").kind()).isEqualTo(IndexKind.ORDINAL);
        assertThat(registry.schema().columns(def.collection()).get("tags").multi()).isTrue();
        assertThat(persisted.get()).isSameAs(registry.schema());
        assertThat(registry.entity(Person.class)).isSameAs(t);
        assertThat(registry.entity(1)).isSameAs(t);
        assertThat(t.toString()).contains("Person");
        assertThat(registry.schema().toString()).contains("collection " + Person.class.getName(), "home Address");
    }

    @Test
    void roundTripsEveryValueType() {
        EntityType<Kitchen> t = registry.entity(Kitchen.class);
        Kitchen k = new Kitchen();
        k.b = true; k.bb = false; k.by = -3; k.bby = 4; k.sh = -300; k.ssh = 301; k.ch = 'x'; k.cch = 'é';
        k.i = Integer.MIN_VALUE; k.ii = 7; k.l = Long.MAX_VALUE; k.ll = -1L; k.f = 1.5f; k.ff = -2.25f;
        k.d = -0.0; k.dd = Double.NaN; k.s = "héllo 😀"; k.level = Level.HIGH;
        k.date = LocalDate.of(1900, 1, 2); k.dateTime = LocalDateTime.of(2030, 5, 6, 7, 8, 9, 123456789);
        k.instant = Instant.ofEpochSecond(-12345, 999); k.ref = Ref.of(99);
        k.levels = new LinkedHashSet<>(List.of(Level.LOW, Level.HIGH)); k.sorted = new TreeSet<>(List.of(3, 1, 2));
        k.arrayList = new ArrayList<>(List.of("a", "b"));
        k.address = new Address("Perth", "6000");
        k.nested = new Kitchen.Nested(); k.nested.code = "N1"; k.nested.inner = new Address("Hobart", null);
        k.nested.numbers = List.of(1, 2, 3);
        k.addresses = List.of(new Address("A", "1"), new Address("B", "2"));
        k.addressSet = Set.of(new Address("C", "3"));
        k.map = new LinkedHashMap<>(Map.of("one", 1)); k.map.put("none", null);
        k.sortedMap = new TreeMap<>(Map.of("pi", 3.14)); k.hashMap = new HashMap<>(Map.of(5L, "five"));
        k.bytes = new byte[]{1, 2, 3}; k.uuid = UUID.randomUUID();
        k.anything = new LinkedHashMap<>(Map.of("k", List.of(1, "x"))); k.objects = new ArrayList<>(List.of(1, "two"));

        Record r = t.toRecord(k);
        byte[] bytes = RecordCodec.encode(r);
        Record back = RecordCodec.decode(registry.schema(), bytes);
        assertThat(back).isEqualTo(r).hasSameHashCodeAs(r);
        assertThat(back.toString()).contains("s=héllo");

        Kitchen c = t.fromRecord(back, 77, 3);
        assertThat(c.id).isEqualTo(77);
        assertThat(c.version).isEqualTo(3);
        assertThat(c).usingRecursiveComparison().ignoringFields("id", "version", "dd").isEqualTo(k);
        assertThat(c.dd).isNaN();
        assertThat(Double.doubleToRawLongBits(c.d)).isEqualTo(Double.doubleToRawLongBits(-0.0));
        assertThat(c.sorted).isInstanceOf(TreeSet.class);
        assertThat(c.levels).isInstanceOf(LinkedHashSet.class);
        assertThat(c.sortedMap).isInstanceOf(TreeMap.class);
        assertThat(c.hashMap).isInstanceOf(HashMap.class);
        assertThat(c.arrayList).isInstanceOf(ArrayList.class);

        assertThat(r.get("level")).isEqualTo("HIGH");
        assertThat(r.get("ref")).isEqualTo(99L);
        assertThat(r.get("nested.inner.city")).isEqualTo("Hobart");
        assertThat(r.get("nested.inner.zip")).isNull();
        assertThat(r.get("nested.code.x")).isNull();
        assertThat(r.get("nested.nope")).isNull();
        assertThat(r.get("nope")).isNull();
        assertThat(r.get("ch")).isEqualTo((int) 'x');

        // path-only decoding (unindexed scans) ≡ full decode + get, for every field, nested path and miss
        List<String> paths = new ArrayList<>(registry.schema().columns(Kitchen.class.getName()).keySet());
        r.layout().fields().forEach(f -> paths.add(f.name()));
        paths.addAll(List.of("nested.inner.zip", "nested.code.x", "nested.nope", "nope", "addresses.city", "map.one"));
        Record empty = t.toRecord(new Kitchen());
        for (Record row : List.of(r, empty)) {
            byte[] enc = RecordCodec.encode(row);
            for (String p : paths) {
                assertThat(deep(RecordCodec.readPath(registry.schema(), new ByteSource(enc), p))).as(p).isEqualTo(deep(row.get(p)));
            }
        }
        assertThat(registry.schema().columns(Kitchen.class.getName()))
                .containsKeys("i", "d", "s", "level", "levels", "address.city", "nested.code", "nested.inner.city");
    }

    @Test
    void nullsStayDefaults() {
        EntityType<Kitchen> t = registry.entity(Kitchen.class);
        Kitchen empty = new Kitchen();
        Record r = t.toRecord(empty);
        Kitchen back = t.fromRecord(RecordCodec.decode(registry.schema(), RecordCodec.encode(r)), 1, 1);
        assertThat(back.s).isNull();
        assertThat(back.i).isZero();
        assertThat(back.address).isNull();
    }

    @Test
    void rejectsBadValues() {
        EntityType<Kitchen> t = registry.entity(Kitchen.class);
        Kitchen k = new Kitchen();
        k.arrayList = new ArrayList<>(Arrays.asList("a", null));
        assertThatThrownBy(() -> t.toRecord(k)).hasMessageContaining("null elements");
        Kitchen k2 = new Kitchen();
        k2.anything = Map.of("x", new Object());
        assertThatThrownBy(() -> t.toRecord(k2)).hasMessageContaining("cannot serialize");
        assertThatThrownBy(() -> t.fromRecord(new Record(999, t.def().current(), new Object[t.def().current().fields().size()]), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inheritanceSharesCollection() {
        EntityType<Dog> dog = registry.entity(Dog.class);
        EntityType<Cat> cat = registry.entity(Cat.class);
        EntityType<Animal> animal = registry.entity(Animal.class);
        Schema s = registry.schema();
        assertThat(dog.collection()).isEqualTo(Animal.class.getName()).isEqualTo(cat.collection());
        assertThat(dog.def().parent()).isEqualTo(Animal.class.getName());
        assertThat(s.classesOf(Animal.class.getName())).extracting(ClassDef::name).first().isEqualTo(Animal.class.getName());
        assertThat(s.subclassIds(Animal.class.getName())).containsExactlyInAnyOrder(animal.classId(), dog.classId(), cat.classId());
        assertThat(s.subclassIds(Dog.class.getName())).containsExactly(dog.classId());
        assertThat(s.collections()).containsExactly(Animal.class.getName());
        assertThat(s.columns(Animal.class.getName())).containsOnlyKeys("name", "breed");
        assertThat(dog.def().current().fields()).extracting(FieldDef::name).containsExactly("name", "age", "breed");
        assertThat(dog.def().simpleName()).isEqualTo("Dog");

        Dog d = new Dog("Rex", 3, "Lab");
        Dog back = dog.fromRecord(RecordCodec.decode(s, RecordCodec.encode(dog.toRecord(d))), 5, 1);
        assertThat(back.getName()).isEqualTo("Rex");
        assertThat(back.getBreed()).isEqualTo("Lab");
        assertThat(dog.hasVersion()).isFalse();
        assertThat(dog.version(back)).isZero();
    }

    @Test
    void evolvesSchemaAndReadsOldRows() {
        String name = Person.class.getName();
        Layout old = new Layout(0, List.of(
                new FieldDef("name", TypeDef.scalar(ValueType.STRING), IndexKind.AUTO),
                new FieldDef("age", TypeDef.scalar(ValueType.INT32), null),
                new FieldDef("balance", TypeDef.scalar(ValueType.INT32), null),           // widened to long
                new FieldDef("level", TypeDef.enumOf(Level.class.getName(), List.of("LOW", "MEDIUM")), null),
                new FieldDef("removed", TypeDef.scalar(ValueType.STRING), null)));
        Schema stored = new Schema(1, List.of(new ClassDef(3, name, null, name, "oid", "version", List.of(old))));
        TypeRegistry reg = new TypeRegistry(SchemaIO.read(SchemaIO.write(stored)), null, s -> { });

        EntityType<Person> t = reg.entity(Person.class);
        assertThat(t.classId()).isEqualTo(3);
        assertThat(t.def().layouts()).hasSize(2);
        assertThat(reg.schema().version()).isEqualTo(2);

        Record oldRow = new Record(3, old, new Object[]{"Ann", 40, 12, "MEDIUM", "gone"});
        Record decoded = RecordCodec.decode(reg.schema(), RecordCodec.encode(oldRow));
        assertThat(decoded.layout().id()).isZero();
        Person p = t.fromRecord(decoded, 9, 1);
        assertThat(p.getName()).isEqualTo("Ann");
        assertThat(p.getAge()).isEqualTo(40);
        assertThat(p.getBalance()).isEqualTo(12L);
        assertThat(p.getLevel()).isEqualTo(Level.MEDIUM);
        assertThat(p.getCity()).isNull();
        assertThat(p.getOid()).isEqualTo(9);
    }

    @Test
    void rejectsIncompatibleEvolution() {
        String name = Person.class.getName();
        Layout old = new Layout(0, List.of(new FieldDef("age", TypeDef.scalar(ValueType.STRING), null)));
        TypeRegistry reg = new TypeRegistry(new Schema(1, List.of(new ClassDef(1, name, null, name, "oid", null, List.of(old)))), null, null);
        assertThatThrownBy(() -> reg.entity(Person.class)).isInstanceOf(SchemaException.class).hasMessageContaining("migrate");

        Layout reordered = new Layout(0, List.of(new FieldDef("level", TypeDef.enumOf("x", List.of("HIGH", "LOW")), null)));
        TypeRegistry reg2 = new TypeRegistry(new Schema(1, List.of(new ClassDef(1, name, null, name, "oid", null, List.of(reordered)))), null, null);
        assertThatThrownBy(() -> reg2.entity(Person.class)).hasMessageContaining("level");

        TypeRegistry reg3 = new TypeRegistry(new Schema(1, List.of(new ClassDef(1, Dog.class.getName(), "x.Other",
                Dog.class.getName(), "oid", null, List.of(new Layout(0, List.of()))))), null, null);
        assertThatThrownBy(() -> reg3.entity(Dog.class)).hasMessageContaining("superclass changed");
    }

    @Test
    void unchangedClassKeepsSchemaVersion() {
        registry.entity(Person.class);
        Schema s = registry.schema();
        TypeRegistry again = new TypeRegistry(SchemaIO.read(SchemaIO.write(s)), null, x -> {
            throw new AssertionError("no change expected");
        });
        assertThat(again.entity(Person.class).def()).isEqualTo(s.byName(Person.class.getName()));
        assertThat(again.schema().version()).isEqualTo(s.version());
    }

    @Test
    void schemaFileRoundTripAndCorruption() {
        registry.entity(Kitchen.class);
        registry.entity(Dog.class);
        Schema s = registry.schema();
        byte[] file = SchemaIO.write(s);
        Schema back = SchemaIO.read(file);
        assertThat(back.version()).isEqualTo(s.version());
        assertThat(back.classes()).containsExactlyElementsOf(s.classes());

        byte[] bad = file.clone();
        bad[20] ^= 1;
        assertThatThrownBy(() -> SchemaIO.read(bad)).hasMessageContaining("checksum");
        assertThatThrownBy(() -> SchemaIO.read(new byte[4])).hasMessageContaining("truncated");
        assertThatThrownBy(() -> SchemaIO.read(new byte[20])).hasMessageContaining("not a schema");
    }

    @Test
    void lazyEntityLookupById() {
        int id = registry.entity(Person.class).classId();
        TypeRegistry fresh = new TypeRegistry(registry.schema(), null, null);
        assertThat(fresh.entity(id).type()).isEqualTo(Person.class);

        ClassDef ghost = new ClassDef(40, "com.nowhere.Ghost", null, "com.nowhere.Ghost", "oid", null,
                List.of(new Layout(0, List.of())));
        TypeRegistry r = new TypeRegistry(new Schema(1, List.of(ghost)), null, null);
        assertThat(r.entity(40)).isNull();
        assertThatThrownBy(() -> r.schema().byId(41)).isInstanceOf(SchemaException.class);
        assertThatThrownBy(() -> r.schema().byId(-1)).isInstanceOf(SchemaException.class);
    }

    // ---------------------------------------------------------------- invalid entities

    static class NotEntity { @Id long id; }
    @Entity static class NoId { int x; }
    @Entity static class IntId { @Id int id; }
    @Entity static class TwoIds { @Id long a; @Id long b; }
    @Entity static class FinalField { @Id long id; final int x = 1; }
    @Entity static class IndexedMap { @Id long id; @Index Map<String, String> m; }
    @Entity static class Bsi { @Id long id; @Index(IndexKind.BSI) int x; }
    @Embeddable static class Loop { Loop next; }
    @Entity static class Recursive { @Id long id; Loop loop; }
    static class Plain { }
    @Entity static class NotStorable { @Id long id; Plain plain; }
    @Entity static class NoCtor { @Id long id; NoCtor(int x) { } }
    @Entity static class Base { @Id long id; String x; }
    @Entity static class Hiding extends Base { String x; }
    @Entity record Rec(@Id long id) { }
    @Entity static class Root { @Id long id; }
    @Entity static class SizeA extends Root { @Index String size; }
    @Entity static class SizeB extends Root { @Index int size; }

    @Test
    void rejectsInvalidEntities() {
        assertThatThrownBy(() -> registry.entity(NotEntity.class)).hasMessageContaining("not an @Entity");
        assertThatThrownBy(() -> registry.entity(NoId.class)).hasMessageContaining("needs a long @Id");
        assertThatThrownBy(() -> registry.entity(IntId.class)).hasMessageContaining("must be a long");
        assertThatThrownBy(() -> registry.entity(TwoIds.class)).hasMessageContaining("more than one");
        assertThatThrownBy(() -> registry.entity(FinalField.class)).hasMessageContaining("must not be final");
        assertThatThrownBy(() -> registry.entity(IndexedMap.class)).hasMessageContaining("not supported");
        assertThatThrownBy(() -> registry.entity(Bsi.class)).hasMessageContaining("BSI");
        assertThatThrownBy(() -> registry.entity(Recursive.class)).hasMessageContaining("Recursive");
        assertThatThrownBy(() -> registry.entity(NotStorable.class)).hasMessageContaining("not storable");
        assertThatThrownBy(() -> registry.entity(NoCtor.class)).hasMessageContaining("no-argument constructor");
        assertThatThrownBy(() -> registry.entity(Hiding.class)).hasMessageContaining("hides");
        assertThatThrownBy(() -> registry.entity(Rec.class)).hasMessageContaining("records");
        registry.entity(SizeA.class);
        assertThatThrownBy(() -> registry.entity(SizeB.class)).hasMessageContaining("Column 'size'");
    }

    @Test
    void typeDefDetails() {
        TypeDef e = TypeDef.enumOf("E", List.of("A", "B"));
        assertThat(e.constantIndex("B")).isEqualTo(1);
        assertThat(e.toString()).isEqualTo("ENUM(A, B)");
        assertThat(TypeDef.ref("x.Y").toString()).isEqualTo("REF(Y)");
        assertThat(TypeDef.multi(TypeDef.scalar(ValueType.INT32), true).toString()).isEqualTo("SET(INT32)");
        assertThat(TypeDef.map(TypeDef.scalar(ValueType.STRING), TypeDef.scalar(ValueType.INT64)).toString())
                .isEqualTo("MAP(STRING, INT64)");
        assertThat(TypeDef.object("java.util.UUID").toString()).isEqualTo("OBJECT(java.util.UUID)");
        assertThat(TypeDef.bytes().toString()).isEqualTo("BYTES");
        assertThat(TypeDef.embeddedList(TypeDef.embedded("a.B", List.of()), false).toString()).isEqualTo("LIST(B[])");
        assertThatThrownBy(() -> TypeDef.scalar(ValueType.ENUM)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TypeDef.scalar(ValueType.INT64).canRead(TypeDef.scalar(ValueType.INT32))).isTrue();
        assertThat(TypeDef.scalar(ValueType.INT32).canRead(TypeDef.scalar(ValueType.INT64))).isFalse();
        TypeDef m1 = TypeDef.map(TypeDef.scalar(ValueType.STRING), TypeDef.scalar(ValueType.INT32));
        TypeDef m2 = TypeDef.map(TypeDef.scalar(ValueType.STRING), TypeDef.scalar(ValueType.INT64));
        assertThat(m2.canRead(m1)).isTrue();
        TypeDef emb1 = TypeDef.embedded("A", List.of(new FieldDef("x", TypeDef.scalar(ValueType.STRING), null)));
        TypeDef emb2 = TypeDef.embedded("A", List.of(new FieldDef("x", TypeDef.scalar(ValueType.INT32), null)));
        assertThat(emb2.canRead(emb1)).isFalse();
        assertThat(emb1.field("nope")).isNull();
        assertThat(Canonical.compare(e, "B", "A")).isPositive();
        assertThat(Canonical.compare(TypeDef.scalar(ValueType.STRING), "a", "b")).isNegative();
        assertThat(new Layout(0, List.of()).field("x")).isNull();
        assertThatThrownBy(() -> new ClassDef(1, "x", null, "x", "id", null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Record(1, new Layout(0, List.of()), new Object[1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Schema(1, List.of(
                new ClassDef(1, "a", null, "a", "id", null, List.of(new Layout(0, List.of()))),
                new ClassDef(1, "b", null, "b", "id", null, List.of(new Layout(0, List.of()))))))
                .hasMessageContaining("duplicate");
        assertThat(new FieldDef("a", TypeDef.scalar(ValueType.BOOL), IndexKind.AUTO).toString()).isEqualTo("a BOOL @AUTO");
    }

    @Test
    void encodeRejectsUnknownEnumConstant() {
        Layout l = new Layout(0, List.of(new FieldDef("e", TypeDef.enumOf("E", List.of("A")), null)));
        Schema s = new Schema(1, List.of(new ClassDef(1, "x", null, "x", "id", null, List.of(l))));
        assertThatThrownBy(() -> RecordCodec.encode(new Record(1, l, new Object[]{"Z"}))).hasMessageContaining("unknown enum");
        assertThat(RecordCodec.decode(s, RecordCodec.encode(new Record(1, l, new Object[]{"A"}))).value(0)).isEqualTo("A");
    }

    @Test
    void canonicalScalars() {
        assertThat(Canonical.scalar(Level.LOW)).isEqualTo("LOW");
        assertThat(Canonical.scalar(Ref.of(4))).isEqualTo(4L);
        assertThat(Canonical.scalar((short) 2)).isEqualTo(2);
        assertThat(Canonical.scalar(1.5f)).isEqualTo(1.5);
        assertThat(Convert.scalarToJava(5, Object.class)).isEqualTo(5);
        assertThatThrownBy(() -> Convert.scalarToJava(5, UUID.class)).isInstanceOf(SchemaException.class);
        assertThatThrownBy(() -> Convert.deserialize(new byte[]{1, 2}, null)).isInstanceOf(SchemaException.class);
        assertThat(new SchemaException("x", new RuntimeException()).getCause()).isNotNull();
    }

    /** Arrays (embedded values, bytes) → lists, recursively, so values compare by content. */
    private static Object deep(Object v) {
        if (v instanceof Object[] a) {
            return Arrays.stream(a).map(MappingTest::deep).toList();
        }
        if (v instanceof byte[] b) {
            return Arrays.toString(b);
        }
        if (v instanceof List<?> l) {
            return l.stream().map(MappingTest::deep).toList();
        }
        return v;
    }

}
