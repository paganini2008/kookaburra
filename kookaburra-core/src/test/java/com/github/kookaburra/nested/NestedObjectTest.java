package com.github.kookaburra.nested;

import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Config;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.model.Geo;
import com.github.kookaburra.model.Item;
import com.github.kookaburra.model.Money;
import com.github.kookaburra.model.Place;
import com.github.kookaburra.model.Route;
import com.github.kookaburra.model.Shipment;
import com.github.kookaburra.query.Q;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.kookaburra.query.Q.where;
import static com.github.kookaburra.query.Q.whereAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Nested objects end to end: three embedded levels, lists of embedded objects (with embedded values inside),
 * maps of embedded values, references and reference cycles — create, read (every API), update (leaf, whole
 * sub-object, null, list and map changes), delete, inside transactions. {@link NestedObjectDiskTest} runs it on
 * disk with flushes, compaction and reopening.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class NestedObjectTest {

    protected ObjectDB db;

    /** The database under test (in memory here). */
    protected ObjectDB create() {
        return TestDbs.create(Config.defaults().withTransactions(true));
    }

    /** Disk variant: flush / compact / reopen at the checkpoints; nothing in memory. */
    protected void checkpoint() {
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    static Shipment full(String code) {
        return new Shipment(code, new Route(new Place("Perth", "1 Hay St", new Geo(-31.95, 115.86)),
                new Place("Sydney", "2 George St", new Geo(-33.87, 151.21)), 2))
                .items(List.of(new Item("apple", 3, new Money(250, "AUD")), new Item("pear", 1, new Money(400, "AUD"))))
                .stops(new LinkedHashMap<>(Map.of("mid", new Place("Adelaide", "3 King William St", new Geo(-34.93, 138.6)))))
                .labels(List.of("fragile", "express"));
    }

    private long count(String oql) {
        return (Long) db.oql(oql).list().get(0);
    }

    // ---------------------------------------------------------------- create + read

    @Test
    void createAndReadEveryLevel() {
        Shipment s = full("S1");
        long oid = db.insert(s);
        checkpoint();
        Shipment back = db.get(Shipment.class, oid);
        assertThat(back).usingRecursiveComparison().ignoringFields("oid", "version").isEqualTo(s);
        assertThat(back.getRoute().getTo().getGeo().getLat()).isEqualTo(-33.87);       // third level
        assertThat(back.getItems()).extracting(i -> i.getPrice().getCents()).containsExactly(250L, 400L);
        assertThat(back.getStops().get("mid").getGeo().getLon()).isEqualTo(138.6);      // map of embedded values

        DynamicObject d = db.dynamic("Shipment").get(oid);
        assertThat(d.get("route.to.city")).isEqualTo("Sydney");
        assertThat(d.get("route.to.geo.lat")).isEqualTo(-33.87);
        assertThat(d.get("items.sku")).isEqualTo(List.of("apple", "pear"));
        assertThat(d.get("items.price.cents")).isEqualTo(List.of(250L, 400L));
        assertThat(d.get("stops")).isEqualTo(Map.of("mid", Map.of("city", "Adelaide", "street", "3 King William St",
                "geo", Map.of("lat", -34.93, "lon", 138.6))));

        List<Row> rows = db.oql("SELECT s.route.from.city, s.route.to.geo.lat, s.items.sku, s.stops FROM Shipment s").rows();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get(0)).isEqualTo("Perth");                                    // 'from' is a keyword
            assertThat(r.get(1)).isEqualTo(-33.87);
            assertThat(r.get(2)).isEqualTo(List.of("apple", "pear"));
            assertThat(r.get(3).toString()).startsWith("{mid=");
        });
        assertThat(db.oql("SELECT s FROM Shipment s", Shipment.class).list()).singleElement()
                .usingRecursiveComparison().ignoringFields("oid", "version").isEqualTo(s);
    }

    @Test
    void queryNestedPaths() {
        db.insert(full("A"));
        db.insert(new Shipment("B", new Route(new Place("Sydney", "x", null), null, 1))
                .items(List.of(new Item("pear", 5, new Money(100, "NZD")))));
        db.insert(new Shipment("C", null));
        checkpoint();
        var toCity = where(Shipment::getRoute).then(Route::getTo).then(Place::getCity);
        var toLat = where(Shipment::getRoute).then(Route::getTo).then(Place::getGeo).then(Geo::getLat);
        var fromCity = where(Shipment::getRoute).then(Route::getFrom).then(Place::getCity);
        assertThat(db.query(Shipment.class).filter(toCity.eq("Sydney")).list()).extracting(Shipment::getCode).containsExactly("A");
        assertThat(db.query(Shipment.class).filter(where(Shipment::getRoute).then(Route::getTo).isNull()).count()).isEqualTo(2);
        assertThat(db.query(Shipment.class).filter(toLat.lt(-30.0)).count()).isEqualTo(1);
        assertThat(db.query(Shipment.class).sorted(fromCity.asc()).list()).extracting(Shipment::getCode)
                .containsExactly("A", "B", "C");                                          // nulls last
        assertThat(db.query(Shipment.class).filter(where(Shipment::getRoute).then(Route::getLegs).ge(1))
                .orderBy(Shipment::getCode).desc().list()).extracting(Shipment::getCode).containsExactly("B", "A");
        assertThat(db.query(Shipment.class).groupBy(fromCity).agg(Q.count().as("n")).toList())
                .extracting(r -> r.get("route.from.city") + "=" + r.getLong("n"))
                .containsExactlyInAnyOrder("Perth=1", "Sydney=1", "null=1");
        // elements of embedded lists: "any element" in every API
        assertThat(db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").count()).isEqualTo(2);
        assertThat(db.query(Shipment.class).filter(whereAny(Shipment::getItems, Item::getQty).contains(5)).list())
                .extracting(Shipment::getCode).containsExactly("B");
        assertThat(db.query(Shipment.class).filter(Fields.<Shipment, String>listField(Shipment.class, "items.price.currency")
                .contains("NZD")).count()).isEqualTo(1);
        assertThat(db.dynamic("Shipment").from().where("items.sku").contains("apple").count()).isEqualTo(1);
        assertThat(db.dynamic("Shipment").from().where("route.to.geo.lat").lt(-30.0).count()).isEqualTo(1);
        assertThat(db.oql("SELECT s.code FROM Shipment s WHERE 'pear' MEMBER OF s.items.sku ORDER BY s.code").list())
                .containsExactly("A", "B");
        assertThat(db.oql("SELECT s.route.from.city, COUNT(*) FROM Shipment s WHERE s.route IS NOT NULL "
                + "GROUP BY s.route.from.city ORDER BY 1").rows()).extracting(r -> r.get(0)).containsExactly("Perth", "Sydney");
        assertThat(count("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Sydney' AND s.route.legs > 1")).isEqualTo(1);
        // a whole-element condition is reported with a hint
        assertThatThrownBy(() -> where(Shipment::getItems).contains(new Item("apple", 3, null)))
                .hasMessageContaining("Q.whereAny");
        assertThatThrownBy(() -> Fields.listField(Shipment.class, "items.price")).hasMessageContaining("must end at a scalar");
        assertThatThrownBy(() -> Q.whereAny(Shipment::getLabels, String::length)).hasMessageContaining("not a list of @Embeddable");
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateNestedValues() {
        long oid = db.insert(full("U"));
        checkpoint();
        // a leaf three levels down, through the typed API
        Shipment s = db.get(Shipment.class, oid);
        Route r = s.getRoute();
        Place to = new Place(r.getTo().getCity(), r.getTo().getStreet(), new Geo(-33.0, 151.0));
        db.update(s.route(new Route(r.getFrom(), to, r.getLegs())));
        assertThat(db.get(Shipment.class, oid).getRoute().getTo().getGeo().getLat()).isEqualTo(-33.0);
        assertThat(db.get(Shipment.class, oid).getVersion()).isEqualTo(2);
        checkpoint();

        // the whole sub-object, indexed: old value no longer found, new one is
        s = db.get(Shipment.class, oid);
        db.update(s.route(new Route(s.getRoute().getFrom(), new Place("Darwin", "5 Mitchell St", null), 4)));
        assertThat(count("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Sydney'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Darwin'")).isEqualTo(1);
        assertThat(db.get(Shipment.class, oid).getRoute().getTo().getGeo()).isNull();
        checkpoint();

        // list and map changes: add, change, remove elements; put / remove entries
        s = db.get(Shipment.class, oid);
        List<Item> items = new ArrayList<>(s.getItems());
        items.add(new Item("plum", 7, new Money(99, "AUD")));
        items.set(0, new Item("apple", 30, new Money(2500, "AUD")));
        items.removeIf(i -> i.getSku().equals("pear"));
        Map<String, Place> stops = new LinkedHashMap<>(s.getStops());
        stops.put("end", new Place("Alice Springs", "6 Todd St", new Geo(-23.7, 133.88)));
        stops.remove("mid");
        db.update(s.items(items).stops(stops));
        checkpoint();
        Shipment back = db.get(Shipment.class, oid);
        assertThat(back.getItems()).extracting(Item::getSku, Item::getQty).containsExactly(
                tuple("apple", 30), tuple("plum", 7));
        assertThat(back.getStops()).containsOnlyKeys("end");
        assertThat(db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").count()).isZero();
        assertThat(db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("plum").count()).isEqualTo(1);

        // set to null, then OQL and dynamic updates of nested paths
        db.update(back.items(null).stops(null).route(null));
        assertThat(db.get(Shipment.class, oid).getRoute()).isNull();
        assertThat(db.oql("UPDATE Shipment s SET s.route.to.city = 'Hobart', s.route.legs = 9 WHERE s.code = 'U'").execute()).isEqualTo(1);
        Shipment viaOql = db.get(Shipment.class, oid);
        assertThat(viaOql.getRoute().getTo().getCity()).isEqualTo("Hobart");            // intermediate objects created
        assertThat(viaOql.getRoute().getFrom()).isNull();
        DynamicCollection dyn = db.dynamic("Shipment");
        DynamicObject d = dyn.get(oid);
        d.set("route.from.geo.lat", -42.88).set("items", List.of(Map.of("sku", "kiwi", "qty", 2, "price", Map.of("cents", 5L))));
        dyn.update(d);
        checkpoint();
        Shipment viaDyn = db.get(Shipment.class, oid);
        assertThat(viaDyn.getRoute().getFrom().getGeo().getLat()).isEqualTo(-42.88);
        assertThat(viaDyn.getItems()).singleElement().satisfies(i -> {
            assertThat(i.getSku()).isEqualTo("kiwi");
            assertThat(i.getPrice().getCents()).isEqualTo(5);
            assertThat(i.getPrice().getCurrency()).isNull();
        });
        DynamicCollection reopened = db.dynamic("Shipment");                     // the database may have been reopened
        assertThatThrownBy(() -> reopened.update(reopened.get(oid).set("route.to.nope", 1))).hasMessageContaining("no field 'nope'");
        assertThatThrownBy(() -> reopened.update(reopened.get(oid).set("items", List.of(Map.of("qty", "many")))))
                .hasMessageContaining("items.qty");
    }

    // ---------------------------------------------------------------- references, delete

    @Test
    void referencesCyclesAndDelete() {
        long a = db.insert(full("A"));
        long b = db.insert(full("B").parent(Ref.of(a)));
        Shipment sa = db.get(Shipment.class, a);
        db.update(sa.parent(Ref.of(b)));                                                // cycle A → B → A
        checkpoint();
        assertThat(db.get(Shipment.class, b).getParent().resolve(db).getCode()).isEqualTo("A");
        assertThat(db.get(Shipment.class, a).getParent().resolve(db).getParent().resolve(db).getCode()).isEqualTo("A");
        assertThat(db.oql("SELECT s.code, s.parent.parent.code FROM Shipment s ORDER BY s.code").rows())
                .extracting(r -> r.get(0) + "→" + r.get(1)).containsExactly("A→A", "B→B");
        assertThat(db.query(Shipment.class).fetch(where(Shipment::getParent)).list()).hasSize(2);
        assertThat(db.query(Shipment.class).where(Shipment::getParent).eq(a).list()).extracting(Shipment::getCode).containsExactly("B");

        // delete: the nested values go with the object; references to it dangle (resolve to null)
        db.delete(Shipment.class, a);
        checkpoint();
        assertThat(db.get(Shipment.class, a)).isNull();
        assertThat(count("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Sydney'")).isEqualTo(1);
        assertThat(db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("apple").count()).isEqualTo(1);
        assertThat(db.get(Shipment.class, b).getParent().resolve(db)).isNull();
        assertThat(db.oql("SELECT s.parent.code FROM Shipment s").list()).containsExactly((Object) null);
        assertThat(db.oql("DELETE FROM Shipment s WHERE 'apple' MEMBER OF s.items.sku").execute()).isEqualTo(1);
        checkpoint();
        assertThat(db.query(Shipment.class).count()).isZero();
        assertThat(db.dynamic("Shipment").from().where("route.to.city").eq("Sydney").count()).isZero();
    }

    // ---------------------------------------------------------------- transactions

    @Test
    void nestedChangesInsideTransactions() {
        long oid = db.insert(full("T"));
        checkpoint();
        try (Transaction tx = db.begin()) {
            Shipment s = tx.get(Shipment.class, oid);
            tx.update(s.route(new Route(s.getRoute().getFrom(), new Place("Cairns", "7 Esplanade", null), 3)));
            long child = tx.insert(full("T2").parent(Ref.of(oid)));
            // read-your-writes on indexed nested paths, element paths and references
            assertThat(tx.query(Shipment.class).filter(where(Shipment::getRoute).then(Route::getTo).then(Place::getCity).eq("Cairns"))
                    .count()).isEqualTo(1);
            assertThat(tx.oql("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Sydney'").list()).containsExactly(1L);
            assertThat(tx.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("apple").count()).isEqualTo(2);
            assertThat(tx.oql("SELECT s.parent.route.to.city FROM Shipment s WHERE s.code = 'T2'").list()).containsExactly("Cairns");
            assertThat(db.oql("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Cairns'").list()).containsExactly(0L);
            assertThat(tx.get(Shipment.class, child).getParent().oid()).isEqualTo(oid);
            tx.commit();
        }
        checkpoint();
        assertThat(count("SELECT COUNT(*) FROM Shipment s WHERE s.route.to.city = 'Cairns'")).isEqualTo(1);
        // conflicts are per object: two transactions changing different nested fields of one object conflict
        Transaction t1 = db.begin();
        Transaction t2 = db.begin();
        Shipment a1 = t1.get(Shipment.class, oid);
        Shipment a2 = t2.get(Shipment.class, oid);
        t1.update(a1.labels(List.of("t1")));
        t2.update(a2.items(List.of()));
        t1.commit();
        assertThatThrownBy(t2::commit).isInstanceOf(ConflictException.class);
        // increments reach embedded scalars, not list elements
        try (Transaction tx = db.begin()) {
            tx.increment(Shipment.class, oid, "route.legs", 1);
            assertThatThrownBy(() -> tx.increment(Shipment.class, oid, "items.qty", 1)).hasMessageContaining("not embedded");
            tx.commit();
        }
        assertThat(db.get(Shipment.class, oid).getRoute().getLegs()).isEqualTo(4);
    }
}
