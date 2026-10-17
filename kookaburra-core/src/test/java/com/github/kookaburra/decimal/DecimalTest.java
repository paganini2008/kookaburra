package com.github.kookaburra.decimal;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Invoice;
import com.github.kookaburra.query.Q;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BigDecimal} fields: exact storage, numeric equality ({@code 1.50 = 1.5}), indexed and unindexed filters,
 * sorting, grouping, exact SUM / AVG, OQL arithmetic, dynamic access, increments and transactions
 * ({@link DecimalDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class DecimalTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true);
    }

    /** Disk variant: flush, compact, close and reopen. */
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

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static List<Object> values(Row r) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < r.size(); i++) {
            out.add(r.get(i));
        }
        return out;
    }

    private void fill() {
        db.insert(new Invoice("ann", "19.90").rate("0.075").tax("1.49").parts(List.of(d("10.00"), d("9.9")))
                .fees(Map.of("card", d("0.30"))));
        db.insert(new Invoice("bob", "19.9").rate("0.1").tax("0.1"));
        db.insert(new Invoice("cid", "100").rate("0.2").tax("0.2"));
        db.insert(new Invoice("dee", "-5.25").rate("0").tax("0.00"));
        db.insert(new Invoice("eve", "123456789012345678901234567890.123456789").rate("1E-10"));
        db.insert(new Invoice("fay", null));
        checkpoint();
    }

    @Test
    void storesExactValuesWithoutTrailingZeros() {
        fill();
        Invoice ann = db.from(Invoice.class).where(Invoice::getCustomer).eq("ann").first().orElseThrow();
        assertThat(ann.getAmount()).isEqualTo(d("19.9"));                // 19.90 → 19.9 (numeric value kept)
        assertThat(ann.getParts()).containsExactly(d("10"), d("9.9"));
        assertThat(ann.getFees()).containsEntry("card", d("0.3"));
        assertThat(ann.getRate()).isEqualTo(d("0.075"));
        Invoice cid = db.from(Invoice.class).where(Invoice::getCustomer).eq("cid").first().orElseThrow();
        assertThat(cid.getAmount().toString()).isEqualTo("100");          // never 1E+2
        Invoice dee = db.from(Invoice.class).where(Invoice::getCustomer).eq("dee").first().orElseThrow();
        assertThat(dee.getTax()).isEqualTo(BigDecimal.ZERO);           // 0.00 → 0 (scale 0)
        Invoice eve = db.from(Invoice.class).where(Invoice::getCustomer).eq("eve").first().orElseThrow();
        assertThat(eve.getAmount()).isEqualTo(d("123456789012345678901234567890.123456789"));
        assertThat(eve.getRate().toPlainString()).isEqualTo("0.0000000001");
        assertThat(db.from(Invoice.class).where(Invoice::getCustomer).eq("fay").first().orElseThrow().getAmount()).isNull();
    }

    @Test
    void filtersSortsAndAggregatesNumerically() {
        fill();
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).eq(d("19.90")).count()).isEqualTo(2);
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).gt(d("19.9")).list())
                .extracting(Invoice::getCustomer).containsExactlyInAnyOrder("cid", "eve");
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).between(d("-6"), d("20")).count()).isEqualTo(3);
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).in(d("100.000"), d("-5.250")).count()).isEqualTo(2);
        assertThat(db.from(Invoice.class).where(Invoice::getRate).lt(d("0.1")).list())
                .extracting(Invoice::getCustomer).containsExactlyInAnyOrder("ann", "dee", "eve");
        assertThat(db.from(Invoice.class).where(Invoice::getTax).ge(d("0.2")).list())      // unindexed
                .extracting(Invoice::getCustomer).containsExactlyInAnyOrder("ann", "cid");
        assertThat(db.from(Invoice.class).orderBy(Invoice::getAmount).desc().list()).extracting(Invoice::getCustomer)
                .containsExactly("fay", "eve", "cid", "ann", "bob", "dee");   // DESC: nulls first
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).isNotNull().orderBy(Invoice::getAmount).asc().limit(1).list())
                .extracting(Invoice::getCustomer).containsExactly("dee");

        assertThat(db.from(Invoice.class).where(Invoice::getCustomer).in("ann", "bob", "cid", "dee").sum(where(Invoice::getAmount)))
                .isEqualTo(d("134.55"));
        assertThat(db.from(Invoice.class).where(Invoice::getCustomer).eq("nobody").sum(where(Invoice::getAmount)))
                .isEqualTo(BigDecimal.ZERO);
        assertThat(db.from(Invoice.class).where(Invoice::getTax).isNotNull().avg(where(Invoice::getTax)).orElseThrow())
                .isEqualTo(0.4475);
        assertThat(db.from(Invoice.class).max(where(Invoice::getAmount))).contains(d("123456789012345678901234567890.123456789"));
        assertThat(db.from(Invoice.class).min(where(Invoice::getRate))).contains(BigDecimal.ZERO);

        // grouping: 19.90 and 19.9 are one group; SUM / AVG of decimals are exact decimals
        List<Row> groups = db.from(Invoice.class).where(Invoice::getAmount).isNotNull()
                .groupBy(where(Invoice::getAmount)).agg(Q.count(), Q.sum(where(Invoice::getTax)),
                        Q.avg(where(Invoice::getRate))).toList();
        Row nineteen = groups.stream().filter(r -> d("19.9").equals(r.get(0))).findFirst().orElseThrow();
        assertThat(nineteen.get(1)).isEqualTo(2L);
        assertThat(nineteen.get(2)).isEqualTo(d("1.59"));
        assertThat(nineteen.get(3)).isEqualTo(d("0.0875"));
        assertThat(db.from(Invoice.class).where(Invoice::getParts).contains(d("10.0")).count()).isEqualTo(1);
    }

    @Test
    void oql() {
        fill();
        assertThat(db.oql("SELECT i.customer FROM Invoice i WHERE i.amount = 19.90 ORDER BY i.customer").list())
                .containsExactly("ann", "bob");
        assertThat(db.oql("SELECT COUNT(*) FROM Invoice i WHERE i.amount = '19.900'").list()).containsExactly(2L);
        assertThat(db.oql("SELECT COUNT(*) FROM Invoice i WHERE i.amount > 19.9 AND i.amount < 1000").list()).containsExactly(1L);
        assertThat(db.oql("SELECT SUM(i.tax) FROM Invoice i").list()).containsExactly(d("1.79"));
        assertThat(db.oql("SELECT i.tax + 0.2 FROM Invoice i WHERE i.customer = 'bob'").list()).containsExactly(d("0.3"));
        assertThat(values(db.oql("SELECT i.amount * 2 - 0.8, -i.amount, ABS(i.amount) FROM Invoice i WHERE i.customer = 'dee'")
                .rows().get(0))).containsExactly(d("-11.3"), d("5.25"), d("5.25"));
        assertThat(db.oql("SELECT i.amount / 3 FROM Invoice i WHERE i.customer = 'cid'").list().get(0))
                .isEqualTo(d("33.33333333333333333333333333333333"));
        assertThat(db.oql("SELECT i.amount % 30 FROM Invoice i WHERE i.customer = 'cid'").list()).containsExactly(d("10"));
        assertThatThrownBy(() -> db.oql("SELECT i.amount / 0 FROM Invoice i WHERE i.customer = 'cid'").list())
                .hasMessageContaining("division by zero");
        assertThat(db.oql("SELECT i.amount * 0.5 FROM Invoice i WHERE i.customer = 'cid'").list()).containsExactly(d("50"));
        assertThat(db.oql("SELECT i.customer FROM Invoice i WHERE i.tax * 10 > 1.5 ORDER BY i.customer").list())   // residual
                .containsExactly("ann", "cid");
        assertThat(db.oql("SELECT i.customer FROM Invoice i WHERE i.tax > 0.15e0 ORDER BY i.customer").list())
                .containsExactly("ann", "cid");
        assertThat(values(db.oql("SELECT AVG(i.amount), MIN(i.amount), MAX(i.tax) FROM Invoice i WHERE i.customer IN ('ann', 'bob', 'cid')")
                .rows().get(0))).containsExactly(d("46.6"), d("19.9"), d("1.49"));
        assertThat(db.oql("SELECT SUM(DISTINCT i.amount) FROM Invoice i WHERE i.customer IN ('ann', 'bob', 'cid')").list())
                .containsExactly(d("119.9"));
        assertThat(db.oql("SELECT i.amount, COUNT(*) FROM Invoice i WHERE i.amount < 50 GROUP BY i.amount ORDER BY i.amount").rows())
                .extracting(r -> r.get(0) + "x" + r.get(1)).containsExactly("-5.25x1", "19.9x2");
        assertThat(db.oql("SELECT COUNT(*) FROM Invoice i WHERE 10 MEMBER OF i.parts").list()).containsExactly(1L);
        assertThat(db.oql("UPDATE Invoice i SET i.tax = i.tax * 1.1 WHERE i.tax IS NOT NULL").execute()).isEqualTo(4);
        checkpoint();
        assertThat(db.from(Invoice.class).where(Invoice::getCustomer).eq("ann").first().orElseThrow().getTax()).isEqualTo(d("1.639"));
    }

    @Test
    void dynamicIncrementsAndTransactions() {
        fill();
        DynamicObject o = db.dynamic("Invoice").newObject();
        o.set("customer", "gus");
        o.set("amount", "12.30");
        o.set("rate", 0.05);
        o.set("tax", 7L);
        db.dynamic("Invoice").insert(o);
        assertThatThrownBy(() -> {
            DynamicObject bad = db.dynamic("Invoice").newObject();
            bad.set("amount", "twelve");
            db.dynamic("Invoice").insert(bad);
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not a valid DECIMAL");
        assertThatThrownBy(() -> {
            DynamicObject bad = db.dynamic("Invoice").newObject();
            bad.set("amount", Double.NaN);
            db.dynamic("Invoice").insert(bad);
        }).isInstanceOf(IllegalArgumentException.class);
        checkpoint();
        DynamicObject gus = db.dynamic("Invoice").from().where("customer").eq("gus").list().get(0);
        assertThat(gus.get("amount")).isEqualTo(d("12.3"));
        assertThat(gus.get("rate")).isEqualTo(d("0.05"));
        assertThat(gus.get("tax")).isEqualTo(d("7"));
        assertThat(db.dynamic("Invoice").from().where("amount").ge("12.3").count()).isEqualTo(5);

        db.increment(Invoice.class, gus.oid(), "amount", d("0.70"));
        db.increment(Invoice.class, gus.oid(), "tax", 1);
        db.increment(Invoice.class, gus.oid(), "rate", 0.01);
        Invoice g = db.get(Invoice.class, gus.oid());
        assertThat(g.getAmount()).isEqualTo(d("13"));
        assertThat(g.getTax()).isEqualTo(d("8"));
        assertThat(g.getRate()).isEqualTo(d("0.06"));

        try (Transaction tx = db.begin()) {
            tx.increment(Invoice.class, g.getOid(), "amount", d("0.5"));
            tx.increment(Invoice.class, g.getOid(), "amount", d("0.25"));
            Invoice in = tx.get(Invoice.class, g.getOid());
            assertThat(in.getAmount()).isEqualTo(d("13.75"));
            in.amount(d("20.000"));
            tx.update(in);
            assertThat(tx.from(Invoice.class).where(Invoice::getAmount).eq(d("20")).count()).isEqualTo(1);
            tx.commit();
        }
        checkpoint();
        assertThat(db.get(Invoice.class, g.getOid()).getAmount()).isEqualTo(d("20"));
        assertThat(db.from(Invoice.class).where(Invoice::getAmount).eq(d("20")).count()).isEqualTo(1);
        assertThatThrownBy(() -> db.increment(Invoice.class, g.getOid(), "customer", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
