package com.github.kookaburra.face.demo;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.WriteBatch;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final double[][] GEO = {{-33.87, 151.21}, {-31.95, 115.86}, {-12.46, 130.84}, {-42.88, 147.33}, {-27.47, 153.03}};
    private static final String[] CONTACTS = {"work", "billing", "holiday"};
    private static final String NOTE = "Prefers e-mail. Asked about bulk discounts for the next quarter; follow up after the "
            + "catalogue update.\nDelivery: leave at the side door, not the front.\n";

    private DemoData() {
    }

    /**
     * Inserts {@code customers} customers (unique e-mail), 30 products (unique SKU, decimal price) and 3 purchases
     * per customer (foreign keys: deleting a customer cascades to its purchases, a purchased product cannot be
     * deleted). Nested examples: customer
     * address with coordinates (two levels), contacts (map of embedded addresses), notes (long text), product photos
     * (PNG), purchase lines (list of embedded objects with a nested price and a reference).
     */
    public static void fill(ObjectDB db, int customers, long seed) {
        Random r = new Random(seed);
        Random n = new Random(seed * 31 + 7);              // nested examples: separate stream, base values stay stable
        List<Long> customerOids = new ArrayList<>();
        try (WriteBatch b = db.batch()) {
            for (int i = 0; i < customers; i++) {
                Customer c = new Customer();
                c.name = NAMES[i % NAMES.length] + " " + (char) ('A' + r.nextInt(26)) + ".";
                c.email = NAMES[i % NAMES.length].toLowerCase() + "." + (i + 1) + "@example.com";
                c.tier = Tier.values()[r.nextInt(3)];
                c.age = 18 + r.nextInt(60);
                c.since = LocalDate.of(2015, 1, 1).plusDays(r.nextInt(3600));
                c.address = r.nextInt(5) == 0 ? null : address(r.nextInt(CITIES.length), r.nextInt(200) + " Main St");
                c.tags = r.nextBoolean() ? List.of(TAGS[r.nextInt(TAGS.length)]) : List.of();
                c.credit = r.nextInt(4) == 0 ? null : Math.round(r.nextDouble() * 100_000) / 100.0;
                if (n.nextInt(3) == 0) {
                    c.contacts = new LinkedHashMap<>();
                    for (int k = 0; k <= n.nextInt(3); k++) {
                        c.contacts.put(CONTACTS[k], address(n.nextInt(CITIES.length), (1 + n.nextInt(80)) + " " + CONTACTS[k] + " Rd"));
                    }
                }
                int notes = n.nextInt(10);
                c.notes = notes == 0 ? NOTE.repeat(6) : notes < 4 ? "Called on " + c.since.plusDays(n.nextInt(300)) : null;
                b.insert(c);
            }
            b.commit();
        }
        db.from(Customer.class).toList().forEach(c -> customerOids.add(c.oid));
        List<Long> productOids = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
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
            p.sku = "SKU-" + (1000 + i);
            p.price = BigDecimal.valueOf(Math.round(r.nextDouble() * 50_000), 2);
            p.photo = photo(i, p.title);
            productOids.add(db.insert(p));
            prices.add(p.price);
        }
        try (WriteBatch b = db.batch()) {
            for (long c : customerOids) {
                for (int k = 0; k < 3; k++) {
                    Purchase o = new Purchase();
                    o.customer = Ref.of(c);
                    o.product = Ref.of(productOids.get(r.nextInt(productOids.size())));
                    o.quantity = 1 + r.nextInt(5);
                    o.placedAt = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(r.nextInt(30_000_000));
                    o.lines = new ArrayList<>();
                    for (int l = 0; l <= n.nextInt(3); l++) {
                        int pi = n.nextInt(productOids.size());
                        o.lines.add(new Line(Ref.of(productOids.get(pi)), "SKU-" + (1000 + pi), 1 + n.nextInt(4), new Money(prices.get(pi), "AUD")));
                    }
                    b.insert(o);
                }
            }
            b.commit();
        }
    }

    private static Address address(int city, String street) {
        return new Address(CITIES[city], street, new Geo(GEO[city][0], GEO[city][1]));
    }

    /** A 64 × 64 PNG: a coloured tile with the product's initial. */
    public static byte[] photo(int i, String title) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(i / 30f, 0.7f, 0.9f), 64, 64, Color.getHSBColor(i / 30f + 0.3f, 0.8f, 0.5f)));
            g.fillRect(0, 0, 64, 64);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.drawString(title.substring(0, 1), 20, 44);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
