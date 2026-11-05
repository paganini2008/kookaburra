package com.github.kookaburra.rename;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Renamed;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.SchemaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Renamed}: fields (top level and inside embeddables, the embeddable class renamed too) and entity classes
 * keep their stored data; queries, indexes, references, dynamic access and OQL work under the new names, before and
 * after compaction, after a reopen; conflicting renames are refused.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class RenameTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- version 1

    @Embeddable
    public static class OldPlace {
        String town;

        public OldPlace() {
        }

        OldPlace(String town) {
            this.town = town;
        }
    }

    @Entity
    public static class OldCustomer {
        @Id long oid;
        @Index String fullName;
        int age;
        OldPlace where;

        public OldCustomer() {
        }

        OldCustomer(String fullName, int age, String town) {
            this.fullName = fullName;
            this.age = age;
            this.where = new OldPlace(town);
        }
    }

    @Entity
    public static class Order {
        @Id long oid;
        Ref<OldCustomer> buyer;

        public Order() {
        }

        Order(long buyer) {
            this.buyer = Ref.of(buyer);
        }

        public long buyer() {
            return buyer.oid();
        }
    }

    // ---------------------------------------------------------------- version 2: everything renamed

    @Embeddable
    public static class Spot {
        @Renamed("town") String city;

        public Spot() {
        }

        Spot(String city) {
            this.city = city;
        }

        public String getCity() {
            return city;
        }
    }

    @Entity
    @Renamed("com.github.kookaburra.rename.RenameTest$OldCustomer")
    public static class Customer {
        @Id long oid;
        @Index @Renamed({"fullName", "nameV0"}) String name;
        int age;
        @Renamed("where") Spot place;
        String email;                                                       // new field: null in old rows

        public Customer() {
        }

        Customer(String name, int age, String city) {
            this.name = name;
            this.age = age;
            this.place = new Spot(city);
        }

        public long getOid() { return oid; }
        public String getName() { return name; }
        public int getAge() { return age; }
        public Spot getPlace() { return place; }
        public String getEmail() { return email; }
    }

    /** Claims a name that is stored as another field. */
    @Entity
    @Renamed("com.github.kookaburra.rename.RenameTest$OldCustomer")
    public static class Clash {
        @Id long oid;
        @Renamed("fullName") String age;
    }

    private void writeVersion1(Path db) {
        try (ObjectDB v1 = ObjectDB.open(db, Config.defaults())) {
            long ann = v1.insert(new OldCustomer("Ann", 31, "Perth"));
            v1.insert(new OldCustomer("Bob", 45, "Sydney"));
            v1.flush();
            v1.insert(new OldCustomer("Cid", 22, "Perth"));                  // stays in the WAL
            v1.insert(new Order(ann));
        }
    }

    @Test
    void fieldsAndClassRenamed() {
        Path db = dir.resolve("db");
        writeVersion1(db);
        try (ObjectDB v2 = ObjectDB.open(db, Config.defaults())) {
            List<Customer> all = v2.from(Customer.class).orderBy(Customer::getName).asc().list();
            assertThat(all).extracting(Customer::getName).containsExactly("Ann", "Bob", "Cid");
            assertThat(all).extracting(c -> c.getPlace().getCity()).containsExactly("Perth", "Sydney", "Perth");
            assertThat(all).extracting(Customer::getEmail).containsOnlyNulls();
            assertThat(v2.from(Customer.class).where(Customer::getName).eq("Bob").count()).isEqualTo(1);
            assertThat(v2.dynamic(Customer.class.getName()).from().where("place.city").eq("Perth").count()).isEqualTo(2);

            // references written before point at the same objects
            Order o = v2.from(Order.class).first().orElseThrow();
            assertThat(v2.get(Customer.class, o.buyer()).getName()).isEqualTo("Ann");

            // the old class name still finds the class (dynamic access, OQL)
            DynamicObject d = v2.dynamic(OldCustomer.class.getName()).from().where("name").eq("Cid").first().orElseThrow();
            assertThat(d.className()).isEqualTo(Customer.class.getName());
            assertThat(v2.oql("SELECT c.name FROM Customer c WHERE c.place.city = 'Perth' ORDER BY c.name").list())
                    .containsExactly("Ann", "Cid");

            // new writes, then compaction rewrites the segments under the new names
            v2.insert(new Customer("Dee", 50, "Hobart"));
            v2.flush();
            v2.compact();
            assertThat(v2.from(Customer.class).where(Customer::getName).eq("Ann").explain())
                    .contains("name = 'Ann'  [sorted docs").doesNotContain("scan");              // the index, under the new name
            assertThat(v2.from(Customer.class).count()).isEqualTo(4);
        }
        try (ObjectDB again = ObjectDB.open(db, Config.defaults())) {                 // reopen: the schema remembers
            assertThat(again.from(Customer.class).where(Customer::getName).eq("Dee").first().orElseThrow().getPlace().getCity())
                    .isEqualTo("Hobart");
            assertThat(again.dynamic(OldCustomer.class.getName()).from().count()).isEqualTo(4);
            assertThatThrownBy(() -> again.from(OldCustomer.class).count()).isInstanceOf(SchemaException.class)
                    .hasMessageContaining("is a former name of");
        }
    }

    @Test
    void conflicts() {
        Path db = dir.resolve("clash");
        writeVersion1(db);
        try (ObjectDB v2 = ObjectDB.open(db, Config.defaults())) {
            assertThatThrownBy(() -> v2.from(Clash.class).count()).isInstanceOf(SchemaException.class)
                    .hasMessageContaining("cannot rename fullName to age, both are stored");
        }
        try (ObjectDB mem = ObjectDB.inMemory(Config.defaults())) {
            mem.insert(new Customer("Eve", 1, "Perth"));                            // nothing to rename: plain new class
            assertThat(mem.from(Customer.class).count()).isEqualTo(1);
            mem.insert(new OldCustomer("Old", 2, "x"));                             // a different class with its own name
            assertThat(mem.from(OldCustomer.class).count()).isEqualTo(1);
            assertThat(mem.from(Customer.class).count()).isEqualTo(1);
        }
    }
}
