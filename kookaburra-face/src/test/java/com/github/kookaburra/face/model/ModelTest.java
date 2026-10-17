package com.github.kookaburra.face.model;

import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.oql.OqlException;
import com.github.kookaburra.oql.grammar.OqlLexer;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Studio model: session operations, JSON, display, highlighting and completion.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class ModelTest {

    static Session demo;
    static final String CUSTOMER = "com.github.kookaburra.face.demo.Customer";
    static final String PRODUCT = "com.github.kookaburra.face.demo.Product";
    static final String PURCHASE = "com.github.kookaburra.face.demo.Purchase";

    @BeforeAll
    static void open() {
        demo = Session.demo();
    }

    @AfterAll
    static void close() {
        demo.close();
    }

    @Test
    void browsing() {
        List<ClassInfo> classes = demo.classes();
        assertThat(classes).extracting(ClassInfo::simpleName).containsExactly("Customer", "Product", "Purchase");
        ClassInfo product = classes.get(1);
        assertThat(product.count()).isEqualTo(30);
        assertThat(product.children()).extracting(ClassInfo::toString).containsExactly("Book", "Gadget");
        assertThat(demo.title()).contains("demo");
        assertThat(demo.seq()).isPositive();
        assertThat(demo.db()).isNotNull();

        List<Column> cols = demo.columns(CUSTOMER);
        assertThat(cols).extracting(Column::path).startsWith("oid", "version").contains("address.city", "tags");
        assertThat(cols.stream().filter(c -> c.path().equals("tags")).findFirst().orElseThrow().sortable()).isFalse();

        Page p = demo.page(CUSTOMER, "x.age > 60", "age", true, 0, 10);
        assertThat(p.rows()).hasSize(10);
        assertThat(p.total()).isGreaterThan(10);
        assertThat(p.pageCount()).isEqualTo((int) ((p.total() + 9) / 10));
        assertThat((Integer) p.rows().get(0).get("age")).isGreaterThanOrEqualTo((Integer) p.rows().get(9).get("age"));
        Page all = demo.page(PRODUCT, " ", null, false, 1, 20);
        assertThat(all.rows()).hasSize(10);
        DynamicObject first = p.rows().get(0);
        assertThat(demo.cell(first, cols.get(0))).isEqualTo(first.oid());
        assertThat(demo.cell(first, cols.get(1))).isEqualTo(first.version());
        assertThat(demo.cell(first, cols.get(4))).isEqualTo(first.get(cols.get(4).path()));
        assertThat(demo.schemaText()).contains("collection", "Customer");
        assertThat(demo.segments()).isNotEmpty().allMatch(i -> i.live() <= i.docs() && i.garbage() >= 0);
        assertThat(new SegmentInfo("C", "seg#1", "DISK", 0, 0, 0).garbage()).isZero();
        assertThat(new SegmentInfo("C", "seg#1", "DISK", 4, 1, 10).garbage()).isEqualTo(0.75);
        assertThat(demo.classDef(CUSTOMER).simpleName()).isEqualTo("Customer");
        ClassMeta productMeta = demo.meta(PRODUCT);
        assertThat(productMeta.subclasses()).containsExactlyInAnyOrder("Book", "Gadget");
        assertThat(productMeta.parent()).isNull();
        assertThat(productMeta.count()).isEqualTo(30);
        ClassMeta book = demo.meta("com.github.kookaburra.face.demo.Book");
        assertThat(book.parent()).isEqualTo(PRODUCT);
        assertThat(book.idField()).isEqualTo("oid");
        assertThat(book.versionField()).isNull();
        assertThat(book.layouts()).isEqualTo(1);
        assertThat(book.fields()).extracting(ClassMeta.FieldMeta::path).containsExactly("title", "sku", "price", "photo", "author", "pages");
        assertThat(book.fields().get(0).declaredIn()).isEqualTo("Product");
        assertThat(book.fields().get(4).index()).isEqualTo("AUTO");
        assertThat(book.fields().get(5).index()).isEmpty();
        assertThat(book.fields().get(1).index()).isEqualTo("AUTO");                  // implied by @Unique
        assertThat(book.fields().get(1).constraint()).isEqualTo("UNIQUE");
        assertThat(book.fields().get(5).constraint()).isEmpty();
        assertThat(demo.meta(PURCHASE).fields()).extracting(ClassMeta.FieldMeta::constraint)
                .contains("FK → Customer · CASCADE", "FK → Product · RESTRICT");
        ClassMeta customer = demo.meta(CUSTOMER);
        assertThat(customer.fields()).extracting(ClassMeta.FieldMeta::type).contains("ENUM(BRONZE, SILVER, GOLD)", "LIST(STRING)");
        assertThat(customer.fields()).filteredOn(f -> f.path().equals("address.city")).singleElement()
                .extracting(ClassMeta.FieldMeta::declaredIn).isEqualTo("Customer");
    }

    @Test
    void console() {
        QueryOutcome t = demo.run("SELECT c.tier, COUNT(*) FROM Customer c GROUP BY c.tier");
        assertThat(t.isTable()).isTrue();
        assertThat(t.columns()).containsExactly("c.tier", "COUNT(*)");
        assertThat(t.rows()).hasSize(3);
        assertThat(t.summary()).contains("3 rows");
        assertThat(demo.run("SELECT c FROM Customer c WHERE c.age > 1000").summary()).contains("0 rows");
        assertThat(demo.run("SELECT c FROM Customer c WHERE c.age = 999").rows()).isEmpty();
        QueryOutcome objects = demo.run("SELECT c FROM Customer c ORDER BY c.age LIMIT 2");
        assertThat(objects.columns()).startsWith("oid", "version", "name").contains("address.city", "tags");
        assertThat(objects.rows()).hasSize(2).allMatch(r -> r.size() == objects.columns().size());
        QueryOutcome products = demo.run("SELECT p FROM Product p");
        assertThat(products.columns()).contains("title", "price", "author", "pages", "wireless");
        QueryOutcome one = demo.run("SELECT c.name FROM Customer c ORDER BY c.age LIMIT 1");
        assertThat(one.summary()).contains("1 row ");
        QueryOutcome plan = demo.run("EXPLAIN SELECT c FROM Customer c WHERE c.tier = 'GOLD'");
        assertThat(plan.plan()).contains("QUERY Customer");
        assertThat(plan.isTable()).isFalse();
        assertThat(plan.summary()).startsWith("plan");
        assertThat(demo.explain("SELECT c FROM Customer c")).contains("FILTER");
        QueryOutcome upd = demo.run("UPDATE Purchase p SET p.quantity = p.quantity WHERE p.quantity > 100");
        assertThat(upd.affected()).isZero();
        assertThat(upd.summary()).contains("0 objects affected");
        Session s = Session.demo();
        try {
            assertThatThrownBy(() -> s.run("DELETE FROM Gadget g WHERE g.wireless = TRUE"))   // purchased: ON DELETE RESTRICT
                    .isInstanceOf(ConstraintViolationException.class).hasMessageContaining("Purchase.product");
            assertThat(s.run("DELETE FROM Purchase p WHERE p.quantity = 1").summary()).contains("affected");
            assertThat(s.run("UPDATE Book b SET b.pages = 1 WHERE b.pages > 0 AND b.title = 'Book #0'").summary()).contains("1 object affected");
        } finally {
            s.close();
        }
    }

    @Test
    void scriptSplitting() {
        assertThat(Session.statements("BEGIN; UPDATE Customer c SET c.age = 1 WHERE c.name = 'a;b';\n-- x; y\nCOMMIT;  ;"))
                .containsExactly("BEGIN", "UPDATE Customer c SET c.age = 1 WHERE c.name = 'a;b'", "-- x; y\nCOMMIT");
        assertThat(Session.statements("SELECT c FROM Customer c /* a; b */")).containsExactly("SELECT c FROM Customer c /* a; b */");
        assertThat(Session.statements(" ; -- only a comment")).isEmpty();
    }

    /** Console BEGIN / COMMIT / ROLLBACK and scripts, in memory and on disk (flushed, reopened afterwards). */
    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void consoleTransactions(boolean onDisk, @TempDir Path dir) {
        Session s = onDisk ? Session.open(dir.resolve("db")) : Session.demo();
        try {
            if (onDisk) {
                DemoData.fill(s.db(), 60, 42);
                assertThat(s.flush()).isPositive();
            }
            long total = (Long) s.run("SELECT COUNT(*) FROM Customer c").rows().get(0).get(0);
            QueryOutcome begun = s.run("begin transaction isolation level snapshot;");
            assertThat(begun.note()).startsWith("transaction started (SNAPSHOT)");
            assertThat(begun.isTable()).isFalse();
            assertThat(begun.summary()).contains("transaction started", " ms");
            assertThat(s.txLabel()).contains("SNAPSHOT");
            assertThatThrownBy(() -> s.run("BEGIN")).hasMessageContaining("already open");
            assertThat(s.run("DELETE FROM Customer c WHERE c.age < 30").affected()).isPositive();
            long inside = (Long) s.run("SELECT COUNT(*) FROM Customer c").rows().get(0).get(0);
            assertThat(inside).isLessThan(total);
            assertThat(s.db().oql("SELECT COUNT(*) FROM Customer c").list().get(0)).isEqualTo(total);   // private until COMMIT
            assertThat(s.run("ROLLBACK WORK").note()).isEqualTo("transaction rolled back");
            assertThat(s.tx()).isNull();
            assertThatThrownBy(() -> s.run("ROLLBACK")).hasMessageContaining("no open transaction");
            assertThatThrownBy(() -> s.run("COMMIT")).hasMessageContaining("no open transaction");

            // a script: the last statement's outcome, earlier ones applied in order
            QueryOutcome last = s.script(Session.statements(
                    "START TRANSACTION; UPDATE Customer c SET c.age = 99 WHERE c.age < 30; COMMIT TRANSACTION; "
                            + "SELECT COUNT(*) FROM Customer c WHERE c.age = 99"), 100);
            assertThat(last.isTable()).isTrue();
            assertThat((Long) last.rows().get(0).get(0)).isPositive();
            assertThat(s.tx()).isNull();
            assertThat(s.script(List.of("BEGIN WORK"), 100).note()).startsWith("transaction started");
            assertThat(s.run("commit").note()).isEqualTo("transaction committed");
            assertThatThrownBy(() -> s.script(List.of("BEGIN", "SELEC x", "COMMIT"), 100))
                    .hasMessageStartingWith("statement 2 of 3:");
            assertThat(s.tx()).isNotNull();                     // the script stopped: BEGIN stays open, like any console
            s.rollback();
            assertThatThrownBy(() -> s.script(List.of("SELECT COUNT(*) FROM Customer c", "SELEC x"), 100))
                    .hasMessageStartingWith("statement 2 of 2:");
            assertThatThrownBy(() -> s.script(List.of("SELEC x"), 100)).isInstanceOf(OqlException.class);

            assertThatThrownBy(() -> s.run("BEGIN ISOLATION LEVEL CHAOS"))
                    .hasMessage("unsupported isolation level CHAOS (supported: READ COMMITTED, REPEATABLE READ, SNAPSHOT, SERIALIZABLE)");
            for (String level : List.of("READ COMMITTED", "repeatable read", "SNAPSHOT", "Serializable")) {
                assertThat(s.run("BEGIN ISOLATION LEVEL " + level).note()).startsWith("transaction started ("
                        + (level.equalsIgnoreCase("repeatable read") ? "SNAPSHOT" : level.toUpperCase()) + ")");
                assertThat(s.txLabel()).contains(level.equalsIgnoreCase("repeatable read") ? "SNAPSHOT" : level.toUpperCase());
                s.run("ROLLBACK");
            }
            assertThatThrownBy(() -> s.run("BEGIN NOW")).hasMessageStartingWith("syntax: BEGIN");
            assertThatThrownBy(() -> s.run("BEGIN ISOLATION SNAPSHOT")).hasMessageStartingWith("syntax: BEGIN");
            assertThatThrownBy(() -> s.run("START")).hasMessageStartingWith("syntax: START TRANSACTION");
            assertThatThrownBy(() -> s.run("COMMIT NOW")).hasMessageStartingWith("syntax: COMMIT");
            assertThat(s.tx()).isNull();
            if (onDisk) {
                s.flush();
            }
        } finally {
            s.close();
        }
        if (onDisk) {
            Session again = Session.open(dir.resolve("db"));
            try {
                assertThat((Long) again.run("SELECT COUNT(*) FROM Customer c WHERE c.age < 30").rows().get(0).get(0)).isZero();
                assertThat((Long) again.run("SELECT COUNT(*) FROM Customer c WHERE c.age = 99").rows().get(0).get(0)).isPositive();
            } finally {
                again.close();
            }
        }
    }

    /** Composite unique constraints in the metadata panel and on save, in memory and on disk. */
    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void compositeUniqueConstraints(boolean onDisk, @TempDir Path dir) {
        Session s = onDisk ? Session.open(dir.resolve("db")) : Session.demo();
        try {
            s.db().insert(new Seat("A", "1", 7, "t-1"));
            String seat = Seat.class.getName();
            ClassMeta meta = s.meta(seat);
            assertThat(meta.fields()).filteredOn(f -> f.path().equals("hall")).singleElement()
                    .extracting(ClassMeta.FieldMeta::constraint).isEqualTo("seat UNIQUE (hall, row, number)");
            assertThat(meta.fields()).filteredOn(f -> f.path().equals("ticket")).singleElement()
                    .extracting(ClassMeta.FieldMeta::constraint).isEqualTo("UNIQUE");
            DynamicObject dup = s.newObject(seat);
            dup.set("hall", "A");
            dup.set("row", "1");
            dup.set("number", 7);
            assertThatThrownBy(() -> s.save(dup)).isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("for unique seat: Seat(hall, row, number)");
            dup.set("number", 8);
            s.save(dup);
            assertThat(s.run("SELECT COUNT(*) FROM Seat x").rows().get(0).get(0)).isEqualTo(2L);
            assertThat(s.schemaText()).contains("seat UNIQUE (hall, row, number)");
        } finally {
            s.close();
        }
        if (onDisk) {
            Session again = Session.open(dir.resolve("db"));
            try {
                assertThat(again.meta(Seat.class.getName()).fields()).extracting(ClassMeta.FieldMeta::constraint)
                        .contains("seat UNIQUE (hall, row, number)");
            } finally {
                again.close();
            }
        }
    }

    @Test
    void editing(@TempDir Path dir) {
        try (Session s = Session.open(dir.resolve("db"))) {
            assertThat(s.classes()).isEmpty();
            assertThat(s.title()).endsWith("db");
            DemoData.fill(s.db(), 5, 1);
            DynamicObject c = s.newObject(CUSTOMER).set("name", "Zed").set("age", 33).set("tier", "GOLD");
            s.save(c);
            assertThat(c.oid()).isPositive();
            c.set("age", 34);
            s.save(c);
            assertThat(c.version()).isEqualTo(2);
            DynamicObject stale = s.reload(c);
            c.set("age", 35);
            s.save(c);
            stale.set("age", 1);
            assertThatThrownBy(() -> s.save(stale)).isInstanceOf(OptimisticLockException.class);
            s.delete(List.of(c));
            assertThat(s.reload(c)).isNull();
            assertThat(s.classes()).hasSize(3);
        }
    }

    @Test
    void export(@TempDir Path dir) throws IOException {
        String cls = "com.github.kookaburra.face.demo.Customer";
        long matching = (Long) demo.run("SELECT COUNT(*) FROM Customer x WHERE x.age > 40").rows().get(0).get(0);

        Path jsonl = dir.resolve("c.jsonl");
        assertThat(Export.write(demo, cls, "x.age > 40", "age", true, Export.Format.JSONL, jsonl)).isEqualTo(matching);
        List<String> lines = Files.readAllLines(jsonl);
        assertThat(lines).hasSize((int) matching).allMatch(l -> l.startsWith("{\"$oid\":") && l.contains("\"$type\":\"Customer\""));
        Map<?, ?> first = (Map<?, ?>) Json.parse(lines.get(0));
        Map<?, ?> last = (Map<?, ?>) Json.parse(lines.get(lines.size() - 1));
        assertThat(((Number) first.get("age")).intValue()).isGreaterThanOrEqualTo(((Number) last.get("age")).intValue());

        Path json = dir.resolve("c.json");
        assertThat(Export.write(demo, cls, null, null, false, Export.Format.JSON, json)).isEqualTo(250);
        assertThat((List<?>) Json.parse(Files.readString(json))).hasSize(250);
        Path empty = dir.resolve("none.json");
        assertThat(Export.write(demo, cls, "x.age > 1000", null, false, Export.Format.JSON, empty)).isZero();
        assertThat((List<?>) Json.parse(Files.readString(empty))).isEmpty();

        Path csv = dir.resolve("p.csv");
        assertThat(Export.write(demo, "com.github.kookaburra.face.demo.Purchase", "", null, false, Export.Format.CSV, csv)).isEqualTo(750);
        List<String> rows = Files.readAllLines(csv);
        assertThat(rows).hasSize(751);
        assertThat(rows.get(0)).startsWith("oid,version,").contains("customer");
        assertThat(rows.get(1)).doesNotContain("→");                    // refs as plain OIDs

        // binary fields: Base64 in CSV and JSON (as Jackson writes byte[] and MongoDB Extended JSON's $binary.base64)
        Path products = dir.resolve("products.csv");
        assertThat(Export.write(demo, PRODUCT, "", "title", false, Export.Format.CSV, products)).isEqualTo(30);
        List<String> productRows = Files.readAllLines(products);
        List<String> header = List.of(productRows.get(0).split(","));
        DynamicObject firstProduct = demo.page(PRODUCT, "", "title", false, 0, 1).rows().get(0);
        String photoCell = productRows.get(1).split(",")[header.indexOf("photo")];
        assertThat(Base64.getDecoder().decode(photoCell)).isEqualTo((byte[]) firstProduct.get("photo"));
        Path productJson = dir.resolve("products.jsonl");
        Export.write(demo, PRODUCT, "", "title", false, Export.Format.JSONL, productJson);
        Map<?, ?> firstJson = (Map<?, ?>) Json.parse(Files.readAllLines(productJson).get(0));
        assertThat(Base64.getDecoder().decode((String) firstJson.get("photo"))).isEqualTo((byte[]) firstProduct.get("photo"));

        assertThat(Export.csv(null)).isEmpty();
        assertThat(Export.csv("plain")).isEqualTo("plain");
        assertThat(Export.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(Export.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Export.csv("x\ny")).isEqualTo("\"x\ny\"");
        assertThat(Export.csv("x\ry")).isEqualTo("\"x\ry\"");
        assertThat(ObjectJson.text(null)).isNull();
        assertThat(ObjectJson.text(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(ObjectJson.text(Map.of("k", 1))).isEqualTo("{\"k\":1}");
        assertThat(Json.compact(Map.of("k", List.of(1, Map.of())))).isEqualTo("{\"k\":[1,{}]}");
        assertThat(Export.Format.CSV.extension()).isEqualTo("csv");

        // query console results: whole objects, scalars, statements with their own LIMIT
        Path objects = dir.resolve("q/objects.jsonl");
        assertThat(Export.writeQuery(demo, "SELECT c FROM Customer c", Export.Format.JSONL, objects)).isEqualTo(250);
        assertThat(Files.readAllLines(objects)).hasSize(250).allMatch(l -> l.contains("\"name\":"));
        Path scalars = dir.resolve("q/tiers.csv");
        assertThat(Export.writeQuery(demo, "SELECT c.tier, COUNT(*) FROM Customer c GROUP BY c.tier ORDER BY 1",
                Export.Format.CSV, scalars)).isEqualTo(3);
        assertThat(Files.readAllLines(scalars).get(0)).isEqualTo("c.tier,COUNT(*)");
        Path limited = dir.resolve("q/limited.json");
        assertThat(Export.writeQuery(demo, "SELECT c.name FROM Customer c ORDER BY c.name LIMIT 7", Export.Format.JSON, limited)).isEqualTo(7);
        assertThat((List<?>) Json.parse(Files.readString(limited))).hasSize(7);
        Path none = dir.resolve("q/none.json");
        assertThat(Export.writeQuery(demo, "SELECT c FROM Customer c WHERE c.age > 1000", Export.Format.JSON, none)).isZero();
        assertThat((List<?>) Json.parse(Files.readString(none))).isEmpty();
        assertThatThrownBy(() -> Export.writeQuery(demo, "DELETE FROM Customer c", Export.Format.CSV, dir.resolve("x.csv")))
                .hasMessageContaining("only SELECT");
        assertThatThrownBy(() -> Export.writeQuery(demo, "EXPLAIN SELECT c FROM Customer c", Export.Format.CSV, dir.resolve("x.csv")))
                .hasMessageContaining("only SELECT");
        assertThat(ObjectJson.jsonValue(Ref.of(5))).isEqualTo(5L);
        assertThat(Export.write(demo, cls, "x.age > 1000", null, false, Export.Format.CSV, dir.resolve("new/sub/x.csv"))).isZero();
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file, not a directory");
        assertThatThrownBy(() -> Export.write(demo, cls, null, null, false, Export.Format.CSV, blocker.resolve("x.csv")))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("cannot create directory");
    }

    @Test
    void json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\n\t\r\u0001");
        m.put("n", 1L);
        m.put("d", -2.5e3);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(1L, "x", List.of()));
        m.put("o", Map.of());
        m.put("x", LocalDate.of(2020, 1, 2));
        String text = Json.write(m);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.parse(text);
        assertThat(back.get("s")).isEqualTo("a\"b\\c\n\t\r\u0001");
        assertThat(back.get("d")).isEqualTo(new BigDecimal("-2500.0"));          // exact
        assertThat(back.get("x")).isEqualTo("2020-01-02");
        assertThat(back).containsKeys("z", "l", "o");
        assertThat(Json.parse(" {\"u\": \"\\u0041\\b\\f\\/\"} ")).isEqualTo(Map.of("u", "A\b\f/"));
        assertThat(Json.parse("[false, null, -0]")).isEqualTo(Arrays.asList(false, null, 0L));
        assertThat(Json.write(List.of())).isEqualTo("[]");
        assertThat(Json.write(Map.of())).isEqualTo("{}");
        for (String bad : new String[]{"{", "{\"a\" 1}", "[1 2]", "tru", "\"x", "{\"a\": 1} x", "1.2.3", "\"\\u12\"", "@"}) {
            assertThatThrownBy(() -> Json.parse(bad)).as(bad).isInstanceOf(Json.JsonException.class);
        }
        Json.JsonException e = Assertions.assertThrows(Json.JsonException.class, () -> Json.parse("{\n  \"a\": x}"));
        assertThat(e.line()).isEqualTo(2);
        assertThat(e.column()).isEqualTo(8);
    }

    @Test
    void objectJson() {
        ClassDef def = demo.classDef(CUSTOMER);
        DynamicObject c = demo.page(CUSTOMER, "x.address IS NOT NULL", null, false, 0, 1).rows().get(0);
        String text = ObjectJson.toJson(c, def);
        assertThat(text).contains("\"name\"", "\"address\"", "\"since\"");
        DynamicObject back = ObjectJson.fromJson(c, def, text);
        assertThat(back.oid()).isEqualTo(c.oid());
        assertThat(back.get("address.city")).isEqualTo(c.get("address.city"));
        DynamicObject purchase = demo.page("com.github.kookaburra.face.demo.Purchase", "", null, false, 0, 1).rows().get(0);
        ClassDef pdef = demo.classDef("com.github.kookaburra.face.demo.Purchase");
        String pj = ObjectJson.toJson(purchase, pdef);
        assertThat(pj).contains("\"placedAt\": \"20");
        assertThat(ObjectJson.fromJson(purchase, pdef, pj).get("customer")).isInstanceOf(Ref.class);
        assertThatThrownBy(() -> ObjectJson.fromJson(c, def, "[]")).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> ObjectJson.fromJson(c, def, "{\"nope\": 1}")).hasMessageContaining("no field 'nope'");
        assertThatThrownBy(() -> ObjectJson.fromJson(c, def, "{\"address\": 1}")).hasMessageContaining("expected an object");
        assertThatThrownBy(() -> ObjectJson.fromJson(c, def, "{\"address\": {\"x\": 1}}")).hasMessageContaining("no field 'x'");
        assertThatThrownBy(() -> ObjectJson.fromJson(purchase, pdef, "{\"customer\": \"a\"}")).hasMessageContaining("OID number");
        assertThat(ObjectJson.fromJson(c, def, "{\"name\": null}").values()).isEmpty();
    }

    @Test
    void display() {
        assertThat(Display.text(null)).isEmpty();
        assertThat(Display.text(Ref.of(5))).isEqualTo("→ 5");
        assertThat(Display.text(Instant.EPOCH)).isEqualTo("1970-01-01T00:00:00Z UTC");
        assertThat(Display.text(new byte[3])).isEqualTo("Binary · 3 B");
        assertThat(Display.text(Map.of("a", 1))).isEqualTo("{a=1}");
        assertThat(Display.text(List.of(1, "b"))).isEqualTo("[1, b]");
        assertThat(Display.text(2.0)).isEqualTo("2.0");
        assertThat(Display.text(1.0 / 3)).isEqualTo("0.333333");
        assertThat(Display.text(Double.NaN)).isEqualTo("NaN");
        DynamicObject d = new DynamicObject("a.b.C");
        d.assign(7, 1);
        assertThat(Display.text(d)).isEqualTo("C#7");
        assertThat(Display.text("x")).isEqualTo("x");
    }

    @Test
    void highlighting() {
        StyleSpans<Collection<String>> spans = Highlight.oql("SELECT x.a, 'v', 1.5 FROM X x -- c\n WHERE ` ~");
        List<String> styles = new ArrayList<>();
        for (StyleSpan<Collection<String>> s : spans) {
            styles.addAll(s.getStyle());
        }
        assertThat(styles).contains("kw", "ident", "str", "num", "comment", "op", "error");
        assertThat(spans.length()).isEqualTo("SELECT x.a, 'v', 1.5 FROM X x -- c\n WHERE ` ~".length());
        StyleSpans<Collection<String>> js = Highlight.json("{\"k\": \"v\", \"n\": -1.5e2, \"b\": true}");
        List<String> jstyles = new ArrayList<>();
        js.forEach(s -> jstyles.addAll(s.getStyle()));
        assertThat(jstyles).contains("key", "str", "num", "kw", "op");
        assertThat(Highlight.oqlStyle(OqlLexer.WS)).isNull();
        List<String> tx = new ArrayList<>();
        Highlight.oql("begin; commit").forEach(s -> tx.addAll(s.getStyle()));
        assertThat(tx).containsExactly("kw", "op", "kw");            // console commands are keywords, not identifiers
    }

    @Test
    void completion() {
        var schema = demo.schema();
        assertThat(Completion.suggest("SELECT c FROM Cu", 16, schema)).containsExactly("Customer");
        assertThat(Completion.suggest("SELECT c.ad FROM Customer c", 11, schema)).contains("c.address", "c.address.city");
        assertThat(Completion.suggest("SELECT p.au FROM Product p", 11, schema)).containsExactly("p.author");
        assertThat(Completion.suggest("SELECT q.x FROM Customer c", 10, schema)).isEmpty();
        assertThat(Completion.suggest("SEL", 3, schema)).containsExactly("SELECT");
        assertThat(Completion.suggest("SELECT c FROM Customer c WHERE c", 32, schema)).contains("c", "CASE", "COALESCE", "CONCAT", "COUNT");
        assertThat(Completion.suggest("SELECT o.cu FROM com.github.kookaburra.face.demo.Purchase o", 11, schema)).containsExactly("o.customer");
        assertThat(Completion.suggest("UPDATE Pu", 9, schema)).containsExactly("Purchase");
        assertThat(Completion.prefix("ab cd.e", 7)).isEqualTo("cd.e");
        assertThat(Completion.aliases("SELECT x FROM Nope n", schema)).isEmpty();
        assertThat(new Column("a", ValueType.INT32, true, false).sortable()).isTrue();
    }

    @Test
    void nestedAndBinaryDisplay() {
        assertThat(Display.cell(List.of(Map.of("a", 1), Map.of("a", 2)))).isEqualTo("[ 2 elements ]");
        assertThat(Display.cell(List.of(Map.of("a", 1)))).isEqualTo("[ 1 element ]");
        assertThat(Display.cell(List.of("x", "y"))).isEqualTo("[x, y]");
        assertThat(Display.cell(Map.of("k", 1))).isEqualTo("{ 1 entry }");
        assertThat(Display.cell(new LinkedHashMap<>(Map.of("k", 1, "j", 2)))).isEqualTo("{ 2 entries }");
        assertThat(Display.nested(List.of("x"))).isFalse();
        assertThat(Display.nested("short")).isFalse();
        assertThat(Display.nested(null)).isFalse();
        String longText = "line one\n" + "x".repeat(3000);
        assertThat(Display.cell(longText)).hasSize(Display.CELL_MAX + 1).startsWith("line one x").endsWith("…");
        assertThat(Display.nested(longText)).isTrue();
        assertThat(Display.tooltip(longText)).hasSizeLessThan(Display.TOOLTIP_MAX + 40).contains("3,009 chars");
        assertThat(Display.tooltip(Map.of("k", List.of(1)))).contains("\"k\"").contains("\n");
        assertThat(Display.size(5, "bytes")).isEqualTo("5 B");
        assertThat(Display.size(2048, "bytes")).isEqualTo("2.0 KiB");
        assertThat(Display.size(3 << 20, "bytes")).isEqualTo("3.0 MiB");
        assertThat(Display.size(1234, "chars")).isEqualTo("1,234 chars");
        assertThat(Display.format(bytes(0x89, 'P', 'N', 'G'))).isEqualTo("PNG");
        assertThat(Display.format(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("JPEG");
        assertThat(Display.format(bytes('G', 'I', 'F', '8', '9'))).isEqualTo("GIF");
        assertThat(Display.format(bytes('B', 'M', 0))).isEqualTo("BMP");
        assertThat(Display.format(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isEqualTo("WEBP");
        assertThat(Display.format(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'))).isNull();
        assertThat(Display.format(bytes('R', 'I', 'F', 'F'))).isNull();
        assertThat(Display.format(bytes('%', 'P', 'D', 'F'))).isEqualTo("PDF");
        assertThat(Display.format(bytes('P', 'K', 3, 4))).isEqualTo("ZIP");
        assertThat(Display.format(bytes(0x1F, 0x8B))).isEqualTo("GZIP");
        assertThat(Display.format(bytes(1))).isNull();
        assertThat(Display.format(new byte[0])).isNull();
        assertThat(Display.isImage(bytes('%', 'P', 'D', 'F'))).isFalse();
        assertThat(Display.isImage(bytes(1))).isFalse();
        assertThat(Display.isImage(bytes('G', 'I', 'F', '8'))).isTrue();
        assertThat(Display.text(bytes(0x89, 'P', 'N', 'G'))).isEqualTo("PNG · 4 B");
        assertThat(Display.pretty(List.of(Ref.of(5)))).contains("5");
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    void editorJsonKeepsLargeBinariesAndConvertsMaps() {
        ClassDef def = demo.classDef(PRODUCT);
        DynamicObject p = demo.page(PRODUCT, "", null, false, 0, 1).rows().get(0);
        byte[] big = new byte[ObjectJson.EDITOR_INLINE_MAX + 1];
        p.set("photo", big);
        Map<String, byte[]> kept = new LinkedHashMap<>();
        String json = ObjectJson.toEditorJson(p, def, kept);
        assertThat(json).contains("<photo: binary · 64.0 KiB, kept unchanged").hasSizeLessThan(1000);
        assertThat(kept).hasSize(1);
        assertThat((byte[]) ObjectJson.fromJson(p, def, json, kept).get("photo")).isSameAs(big);
        assertThatThrownBy(() -> ObjectJson.fromJson(p, def, json)).hasMessageContaining("photo: not valid Base64");
        p.set("photo", new byte[]{1, 2});
        assertThat(ObjectJson.toEditorJson(p, def, kept)).contains("\"AQI=\"");

        // maps of embedded values: converted by the schema; wrong shapes are reported with the path
        ClassDef customer = demo.classDef(CUSTOMER);
        DynamicObject c = demo.page(CUSTOMER, "", null, false, 0, 1).rows().get(0);
        DynamicObject withContacts = ObjectJson.fromJson(c, customer,
                "{\"name\": \"M\", \"contacts\": {\"work\": {\"city\": \"Perth\", \"geo\": {\"lat\": -31.9, \"lon\": 115.8}}}}");
        assertThat(withContacts.get("contacts.work.geo.lat")).isEqualTo(-31.9);
        assertThatThrownBy(() -> ObjectJson.fromJson(c, customer, "{\"contacts\": [1]}")).hasMessageContaining("contacts: expected an object");
        assertThatThrownBy(() -> ObjectJson.fromJson(c, customer, "{\"contacts\": {\"w\": {\"planet\": 1}}}"))
                .hasMessageContaining("contacts.w has no field 'planet'");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("list", List.of(Map.of("b", new byte[ObjectJson.EDITOR_INLINE_MAX + 5])));
        DynamicObject holder = new DynamicObject(CUSTOMER);
        holder.set("contacts", nested);
        Map<String, byte[]> k2 = new LinkedHashMap<>();
        assertThat(ObjectJson.toEditorJson(holder, customer, k2)).contains("<contacts.list[0].b: binary");
    }
}
