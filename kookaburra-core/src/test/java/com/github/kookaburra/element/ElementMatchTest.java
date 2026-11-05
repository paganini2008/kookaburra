package com.github.kookaburra.element;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.model.Item;
import com.github.kookaburra.model.Money;
import com.github.kookaburra.model.Shipment;
import com.github.kookaburra.oql.OqlException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conditions met by one element of a list of embedded objects ({@code $elemMatch}): {@code whereElement(...)} and OQL
 * {@code JOIN s.items i}, against the "any element" conditions that may be met by different elements, with
 * negations, IN, ranges, LEFT JOIN, two element joins and a differential check ({@link ElementMatchDiskTest} on disk).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class ElementMatchTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(Config.defaults());
    }

    /** Disk variant: flush and reopen. */
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

    private static Item item(String sku, int qty) {
        return new Item(sku, qty, new Money(qty * 100L, "AUD"));
    }

    /** S-1 has A×2 and B×9 (A and qty > 5, but not in the same element); S-2 has A×7; S-3 nothing; S-4 B×1. */
    private void shipments() {
        db.insert(new Shipment("S-1", null).items(List.of(item("A", 2), item("B", 9))));
        db.insert(new Shipment("S-2", null).items(List.of(item("A", 7))));
        db.insert(new Shipment("S-3", null).items(List.of()));
        db.insert(new Shipment("S-4", null).items(List.of(item("B", 1))));
        db.insert(new Shipment("S-5", null));
        checkpoint();
    }

    private static List<String> codes(List<Shipment> l) {
        return l.stream().map(Shipment::getCode).sorted().toList();
    }

    @Test
    void javaApi() {
        shipments();
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq("A").where(Item::getQty).gt(5)).list()))
                .containsExactly("S-2");
        // the "any element" form is met by different elements of S-1
        assertThat(db.from(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("A").list()).hasSize(2);
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).in("A", "B")
                .where(Item::getQty).between(2, 7)).list())).containsExactly("S-1", "S-2");
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).ne("A")
                .where(Item::getQty).lt(5)).list())).containsExactly("S-4");
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getQty).ge(9)).list()))
                .containsExactly("S-1");
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getQty).le(1)).list()))
                .containsExactly("S-4");
        assertThat(codes(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).startsWith("B")
                .where(Item::getPrice).isNotNull()).list())).containsExactly("S-1", "S-4");
        assertThat(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getPrice).isNull()).list()).isEmpty();
        assertThat(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq("A"))
                .where(Shipment::getCode).eq("S-1").count()).isEqualTo(1);
        assertThat(db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq("A").where(Item::getQty).gt(5))
                .explain()).contains("items.sku CONTAINS ANY ('A')", "HAS AN ELEMENT WITH sku = 'A' AND qty > 5");

        assertThatThrownBy(() -> db.from(Shipment.class).whereElement(Shipment::getItems, e -> { }))
                .hasMessageContaining("at least one condition");
        assertThatThrownBy(() -> db.from(Shipment.class).whereElement(Shipment::getItems, e -> e.where(Item::getSku).in()))
                .hasMessageContaining("at least one value");
        assertThatThrownBy(() -> db.from(Shipment.class).whereElement(Shipment::getLabels, e -> e.where(String::length).eq(1)))
                .hasMessageContaining("not a list of @Embeddable objects");
    }

    @Test
    void oql() {
        shipments();
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE i.sku = 'A' AND i.qty > 5").list()).containsExactly("S-2");
        assertThat(db.oql("SELECT s.code FROM Shipment s WHERE 'A' MEMBER OF s.items.sku ORDER BY s.code").list())
                .containsExactly("S-1", "S-2");                                             // any element, for contrast
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE NOT (i.sku = 'A') AND i.qty < 5 ORDER BY s.code").list())
                .containsExactly("S-4");
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE i.sku IN ('A', 'B') AND (i.qty = 2 OR i.qty = 1) ORDER BY s.code").list())
                .containsExactly("S-1", "S-4");
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items i ORDER BY s.code").list())
                .containsExactly("S-1", "S-2", "S-4");                                      // inner join: needs an element
        assertThat(db.oql("SELECT s.code FROM Shipment s LEFT JOIN s.items i WHERE i.sku IS NULL OR i.qty > 8 ORDER BY s.code").list())
                .containsExactly("S-1", "S-3", "S-5");                                      // no element: i is null
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items a JOIN s.items b WHERE a.sku = 'A' AND b.sku = 'B' AND a.qty < b.qty").list())
                .containsExactly("S-1");                                                    // two different elements
        assertThat(db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE i.sku = :sku AND s.code <> 'S-9'").param("sku", "A").list())
                .hasSize(2);
        assertThat(db.oql("EXPLAIN SELECT s FROM Shipment s JOIN s.items i WHERE i.sku = 'A' AND i.qty > 5").explain())
                .contains("items.sku CONTAINS ANY ('A')", "for an element of i");
        assertThatThrownBy(() -> db.oql("SELECT i.sku FROM Shipment s JOIN s.items i").list()).isInstanceOf(OqlException.class)
                .hasMessageContaining("can only be used in WHERE");
        assertThatThrownBy(() -> db.oql("SELECT s FROM Shipment s JOIN s.code c").list()).isInstanceOf(OqlException.class)
                .hasMessageContaining("reference field or a list of embedded objects");
    }

    @Test
    void differential() {
        Random r = new Random(9);
        String[] skus = {"A", "B", "C"};
        List<Shipment> all = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            List<Item> items = new ArrayList<>();
            for (int k = 0; k < r.nextInt(4); k++) {
                items.add(item(skus[r.nextInt(3)], r.nextInt(10)));
            }
            Shipment s = new Shipment("S" + i, null).items(items);
            db.insert(s);
            all.add(s);
            if (i == 150) {
                db.flush();
            }
        }
        checkpoint();
        for (String sku : skus) {
            for (int min = 0; min < 10; min += 3) {
                int m = min;
                Set<String> expected = new TreeSet<>();
                all.stream().filter(s -> s.getItems().stream().anyMatch(it -> it.getSku().equals(sku) && it.getQty() > m))
                        .forEach(s -> expected.add(s.getCode()));
                Set<String> api = new TreeSet<>(codes(db.from(Shipment.class)
                        .whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq(sku).where(Item::getQty).gt(m)).list()));
                Set<String> oql = new TreeSet<>();
                db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE i.sku = :s AND i.qty > :m")
                        .param("s", sku).param("m", m).list().forEach(x -> oql.add((String) x));
                assertThat(api).as(sku + " > " + m).isEqualTo(expected);
                assertThat(oql).as(sku + " > " + m).isEqualTo(expected);
            }
        }
    }
}
