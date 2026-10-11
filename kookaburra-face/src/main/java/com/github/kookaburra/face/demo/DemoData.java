package com.github.kookaburra.face.demo;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.WriteBatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates the demo data set shown by Studio's "Demo database".
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DemoData {

    private static final String[] NAMES = {"Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", "Ida", "Jon"};
    private static final String[] CITIES = {"Sydney", "Perth", "Darwin", "Hobart", "Brisbane"};
    private static final String[] TAGS = {"vip", "new", "b2b", "churn-risk"};

    private DemoData() {
    }

    /** Inserts {@code customers} customers, 30 products and 3 purchases per customer. */
    public static void fill(ObjectDB db, int customers, long seed) {
        Random r = new Random(seed);
        List<Long> customerOids = new ArrayList<>();
        try (WriteBatch b = db.batch()) {
            for (int i = 0; i < customers; i++) {
                Customer c = new Customer();
                c.name = NAMES[i % NAMES.length] + " " + (char) ('A' + r.nextInt(26)) + ".";
                c.tier = Tier.values()[r.nextInt(3)];
                c.age = 18 + r.nextInt(60);
                c.since = LocalDate.of(2015, 1, 1).plusDays(r.nextInt(3600));
                c.address = r.nextInt(5) == 0 ? null : new Address(CITIES[r.nextInt(CITIES.length)], r.nextInt(200) + " Main St");
                c.tags = r.nextBoolean() ? List.of(TAGS[r.nextInt(TAGS.length)]) : List.of();
                c.credit = r.nextInt(4) == 0 ? null : Math.round(r.nextDouble() * 100_000) / 100.0;
                b.insert(c);
            }
            b.commit();
        }
        db.from(Customer.class).toList().forEach(c -> customerOids.add(c.oid));
        List<Long> productOids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Product p;
            if (i % 3 == 0) {
                Book bk = new Book();
                bk.author = NAMES[r.nextInt(NAMES.length)] + " Writer";
                bk.pages = 100 + r.nextInt(500);
                p = bk;
                p.title = "Book #" + i;
            } else if (i % 3 == 1) {
                Gadget g = new Gadget();
                g.wireless = r.nextBoolean();
                p = g;
                p.title = "Gadget #" + i;
            } else {
                p = new Product();
                p.title = "Item #" + i;
            }
            p.price = Math.round(r.nextDouble() * 50_000) / 100.0;
            productOids.add(db.insert(p));
        }
        try (WriteBatch b = db.batch()) {
            for (long c : customerOids) {
                for (int k = 0; k < 3; k++) {
                    Purchase o = new Purchase();
                    o.customer = Ref.of(c);
                    o.product = Ref.of(productOids.get(r.nextInt(productOids.size())));
                    o.quantity = 1 + r.nextInt(5);
                    o.placedAt = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(r.nextInt(30_000_000));
                    b.insert(o);
                }
            }
            b.commit();
        }
    }
}
