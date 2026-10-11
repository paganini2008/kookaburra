package com.github.kookaburra.face.model;

import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.oql.grammar.OqlLexer;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
        assertThat(book.fields()).extracting(ClassMeta.FieldMeta::path).containsExactly("title", "price", "author", "pages");
        assertThat(book.fields().get(0).declaredIn()).isEqualTo("Product");
        assertThat(book.fields().get(2).index()).isEqualTo("AUTO");
        assertThat(book.fields().get(3).index()).isEmpty();
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
            assertThat(s.run("DELETE FROM Gadget g WHERE g.wireless = TRUE").summary()).contains("affected");
            assertThat(s.run("UPDATE Book b SET b.pages = 1 WHERE b.pages > 0 AND b.title = 'Book #0'").summary()).contains("1 object affected");
        } finally {
            s.close();
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
        assertThat(back.get("d")).isEqualTo(-2500.0);
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
        assertThat(Display.text(new byte[3])).isEqualTo("bytes[3]");
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
}
