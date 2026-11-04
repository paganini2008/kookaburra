package com.github.kookaburra.vector;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.mapping.Vectors;
import com.github.kookaburra.model.Chunk;
import com.github.kookaburra.model.Doc;
import com.github.kookaburra.oql.OqlException;
import com.github.kookaburra.query.Neighbor;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code float[]} vectors: storage, dimension checks, exact nearest-neighbour search with filters and three
 * distances, dynamic access, OQL distance functions with ORDER BY pushdown and transactions
 * ({@link VectorDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
class VectorTest {

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

    /** Five documents: three English (x, mostly x, y), two German, one without a vector. */
    private void docs() {
        db.insert(new Doc("x", "en", 1, 0, 0));
        db.insert(new Doc("mostly-x", "en", 0.9f, 0.1f, 0));
        db.insert(new Doc("y", "en", 0, 1, 0));
        db.insert(new Doc("de-x", "de", 1, 0.05f, 0));
        db.insert(new Doc("de-z", "de", 0, 0, 1));
        db.insert(new Doc("none", "en"));
        checkpoint();
    }

    private static List<String> titles(List<Doc> docs) {
        return docs.stream().map(Doc::getTitle).toList();
    }

    @Test
    void storedAndCopied() {
        float[] v = {0.25f, -1.5f, 3e-4f};
        Doc d = new Doc("a", "en", v).sketch(1, 2, 3, 4, 5).chunk(new Chunk("c", 1, 2));
        db.insert(d);
        v[0] = 99;                                               // the stored copy does not change
        checkpoint();
        Doc back = db.get(Doc.class, d.getOid());
        assertThat(back.getEmbedding()).containsExactly(0.25f, -1.5f, 3e-4f);
        assertThat(back.getSketch()).containsExactly(1, 2, 3, 4, 5);
        assertThat(back.getChunk().getVector()).containsExactly(1, 2);
        assertThat(back.getTail()).isEqualTo("after-a");         // fields after a vector are read correctly
        back.getEmbedding()[0] = 7;
        assertThat(db.get(Doc.class, d.getOid()).getEmbedding()[0]).isEqualTo(0.25f);
        assertThat(db.from(Doc.class).where(Doc::getTail).eq("after-a").count()).isEqualTo(1);
        assertThat(db.from(Doc.class).where(Doc::getEmbedding).isNotNull().count()).isEqualTo(1);

        TypeDef t = db.schema().byName(Doc.class.getName()).current().fields().stream()
                .filter(f -> f.name().equals("embedding")).findFirst().orElseThrow().type();
        assertThat(t.kind()).isEqualTo(ValueType.VECTOR);
        assertThat(t.dim()).isEqualTo(3);
        assertThat(t.distance()).isEqualTo(VectorDistance.COSINE);
        assertThat(t).hasToString("VECTOR(3, COSINE)");
        assertThat(TypeDef.vector(0, VectorDistance.L2)).hasToString("VECTOR(L2)");
        assertThat(t.canRead(TypeDef.vector(3, VectorDistance.L2))).isTrue();
        assertThat(t.canRead(TypeDef.vector(4, VectorDistance.COSINE))).isFalse();
        assertThat(TypeDef.vector(0, VectorDistance.COSINE).canRead(TypeDef.vector(4, VectorDistance.COSINE))).isTrue();
        assertThatThrownBy(() -> TypeDef.vector(-1, VectorDistance.COSINE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesAreChecked() {
        assertThatThrownBy(() -> db.insert(new Doc("short", "en", 1, 2))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 dimensions, expected 3");
        assertThatThrownBy(() -> db.insert(new Doc("nan", "en", 1, Float.NaN, 0))).hasMessageContaining("not finite");
        assertThatThrownBy(() -> db.insert(new Doc("empty", "en").sketch())).hasMessageContaining("at least one dimension");
        assertThat(db.from(Doc.class).count()).isZero();
        assertThatThrownBy(() -> db.insert(new BadVectors.OnDoubles())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("float[] fields");
        assertThatThrownBy(() -> db.insert(new BadVectors.NegativeDim())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("negative dimension");
        assertThatThrownBy(() -> db.insert(new BadVectors.Indexed())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("@Index");
        assertThatThrownBy(() -> db.insert(new BadVectors.UniqueVector())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("@Unique");
    }

    @Test
    void nearestWithFilter() {
        docs();
        float[] q = {1, 0, 0};
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).list())).containsExactly("x", "de-x", "mostly-x");
        assertThat(titles(db.from(Doc.class).where(Doc::getLang).eq("en").nearest(Doc::getEmbedding, q, 2).list()))
                .containsExactly("x", "mostly-x");
        assertThat(titles(db.from(Doc.class).where(Doc::getLang).eq("en").nearest(Doc::getEmbedding, q).list()))
                .containsExactly("x", "mostly-x", "y");                 // no limit: every object with a vector
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q).skip(1).limit(2).list()))
                .containsExactly("de-x", "mostly-x");
        assertThat(db.from(Doc.class).where(Doc::getLang).eq("fr").nearest(Doc::getEmbedding, q, 3).list()).isEmpty();
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 0).list()).isEmpty();

        List<Neighbor<Doc>> n = db.from(Doc.class).nearest(Fields.vectorField(Doc.class, "embedding"), q, null).limit(2).listWithDistance();
        assertThat(n.get(0).value().getTitle()).isEqualTo("x");
        assertThat(n.get(0).distance()).isCloseTo(0, within(1e-6));
        assertThat(n.get(1).distance()).isCloseTo(1 - 1 / Math.sqrt(1.0025), within(1e-6));

        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q).count()).isEqualTo(5);
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 2).count()).isEqualTo(2);
        assertThat(db.from(Doc.class).where(Doc::getTitle).eq("none").nearest(Doc::getEmbedding, q).exists()).isFalse();
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q).exists()).isTrue();
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 1).first().orElseThrow().getTitle()).isEqualTo("x");
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 2).map(Fields.stringField(Doc.class, "title")).toList()).containsExactly("x", "de-x");
        try (var s = db.from(Doc.class).nearest(Doc::getEmbedding, q, 2).stream()) {
            assertThat(s.map(Doc::getTitle).toList()).containsExactly("x", "de-x");
        }
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 1).explain())
                .contains("NEAREST embedding by COSINE to a 3-dim vector", "top-N heap");
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q).explain()).contains("full sort");
    }

    @Test
    void distances() {
        docs();
        float[] q = {2, 0, 0};                                       // not normalized: L2 and inner product differ from cosine
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, VectorDistance.L2).limit(2).list()))
                .containsExactly("x", "de-x");
        List<Neighbor<Doc>> ip = db.from(Doc.class).nearest(Doc::getEmbedding, q, VectorDistance.INNER_PRODUCT).limit(3)
                .listWithDistance();
        assertThat(ip).extracting(x -> x.value().getTitle()).containsExactly("x", "de-x", "mostly-x");
        assertThat(ip.get(0).distance()).isEqualTo(-2.0);
        Doc x = db.from(Doc.class).where(Doc::getTitle).eq("x").first().orElseThrow();
        db.update(x.sketch(0, 0));
        db.insert(new Doc("far", "en").sketch(3, 4));
        db.insert(new Doc("odd", "en").sketch(1, 1, 1));            // another dimension: not comparable, left out
        List<Neighbor<Doc>> l2 = db.from(Doc.class).nearest(Doc::getSketch, new float[]{0, 0}).listWithDistance();
        assertThat(l2).extracting(v -> v.value().getTitle()).containsExactly("x", "far");
        assertThat(l2.get(1).distance()).isEqualTo(5.0);              // the field's default distance is L2

        assertThat(VectorDistance.COSINE.distance(new float[]{0, 0}, new float[]{1, 0})).isNaN();
        assertThat(VectorDistance.L2.distance(new float[]{1, 2, 3, 4, 5}, new float[]{1, 2, 3, 4, 5})).isZero();
        assertThat(VectorDistance.dot(new float[]{1, 2, 3, 4, 5}, new float[]{1, 1, 1, 1, 1})).isEqualTo(15);
        assertThatThrownBy(() -> VectorDistance.L2.distance(new float[]{1}, new float[]{1, 2}))
                .hasMessageContaining("different dimensions");
    }

    @Test
    void invalidSearches() {
        docs();
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0}, 3).list())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 dimensions, embedding has 3");
        assertThatThrownBy(() -> db.from(Doc.class).nearest("title", new float[]{1, 0, 0}, null).list())
                .hasMessageContaining("is not a vector field");
        assertThatThrownBy(() -> db.from(Doc.class).nearest("nope", new float[]{1, 0, 0}, null).list())
                .hasMessageContaining("is not a vector field");
        assertThatThrownBy(() -> db.from(Doc.class).orderBy(Doc::getTitle).asc().nearest(Doc::getEmbedding, new float[3]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}).orderBy(Doc::getTitle).asc())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}).sorted(Fields.stringField(Doc.class, "title").asc()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}).groupBy(Fields.stringField(Doc.class, "lang")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Doc.class).listWithDistance()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{Float.NaN, 0, 0}))
                .hasMessageContaining("not finite");
    }

    @Test
    void embeddedAndDynamic() {
        db.insert(new Doc("a", "en").chunk(new Chunk("a", 1, 0)));
        db.insert(new Doc("b", "en").chunk(new Chunk("b", 0, 1)));
        db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("title", "list").set("embedding", List.of(0, 1, 0.5)));
        db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("title", "doubles").set("embedding", new double[]{0, 0, 1}));
        db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("title", "text").set("embedding", " [1, 1, 0] "));
        assertThatThrownBy(() -> db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("embedding", "1, 2, 3")))
                .hasMessageContaining("is not a vector");
        assertThatThrownBy(() -> db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("embedding", "[1, x, 3]")))
                .hasMessageContaining("'x' is not a number");
        assertThatThrownBy(() -> db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("embedding", List.of(1, "a", 3))))
                .hasMessageContaining("is not a number");
        assertThatThrownBy(() -> db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("embedding", 5)))
                .hasMessageContaining("is not a vector");
        assertThatThrownBy(() -> db.dynamic("Doc").insert(new DynamicObject(Doc.class.getName()).set("embedding", "[]")))
                .hasMessageContaining("at least one dimension");
        checkpoint();

        assertThat(titles(db.from(Doc.class).nearest("chunk.vector", new float[]{0, 1}, null).list())).containsExactly("b", "a");
        List<DynamicObject> dyn = db.dynamic("Doc").from().nearest("embedding", "[0, 0, 1]", VectorDistance.COSINE).limit(2).list();
        assertThat(dyn).extracting(o -> o.get("title")).containsExactly("doubles", "list");
        assertThat(dyn.get(0).get("embedding")).isEqualTo(new float[]{0, 0, 1});
        assertThat(db.dynamic("Doc").from().nearest("embedding", List.of(1, 1, 0), null).limit(1).list().get(0).get("title"))
                .isEqualTo("text");
        assertThat(Vectors.format(new float[]{1, 2, 3}, 2)).isEqualTo("[1.0, 2.0, … (3 dims)]");
        assertThat(Vectors.format(new float[]{1}, 2)).isEqualTo("[1.0]");
    }

    @Test
    void oql() {
        docs();
        assertThat(db.oql("SELECT d FROM Doc d WHERE d.lang = 'en' ORDER BY cosine_distance(d.embedding, :q) LIMIT 2", Doc.class)
                .param("q", new float[]{1, 0, 0}).list()).extracting(Doc::getTitle).containsExactly("x", "mostly-x");
        String plan = db.oql("SELECT d FROM Doc d ORDER BY cosine_distance(d.embedding, :q) LIMIT 2").param("q", new float[]{1, 0, 0})
                .explain();
        assertThat(plan).contains("NEAREST embedding by COSINE", "nearest neighbours");

        List<Row> rows = db.oql("SELECT d.title, l2_distance(d.embedding, '[1, 0, 0]') AS dist, vector_dims(d.embedding) "
                + "FROM Doc d ORDER BY dist LIMIT 2").rows();
        assertThat(rows).extracting(r -> r.get(0)).containsExactly("x", "de-x");
        assertThat((Double) rows.get(1).get(1)).isCloseTo(0.05, within(1e-6));
        assertThat(rows.get(0).get(2)).isEqualTo(3L);

        // inner product: larger is closer, so ORDER BY ... DESC; objects without a vector go first (DESC = NULLS FIRST)
        assertThat(db.oql("SELECT d.title FROM Doc d ORDER BY inner_product(d.embedding, ?1) DESC LIMIT 2")
                .param(1, List.of(1, 0, 0)).rows()).extracting(r -> r.get(0)).containsExactly("none", "x");
        assertThat(db.oql("SELECT d.title FROM Doc d ORDER BY inner_product(:q, d.embedding) DESC NULLS LAST LIMIT 2")
                .param("q", new double[]{1, 0, 0}).rows()).extracting(r -> r.get(0)).containsExactly("x", "de-x");
        // fewer objects with a vector than the limit: those without come last, like the in-memory sort
        assertThat(db.oql("SELECT d.title FROM Doc d WHERE d.lang = 'en' ORDER BY cosine_distance(d.embedding, :q) LIMIT 10 OFFSET 1")
                .param("q", new float[]{1, 0, 0}).rows()).extracting(r -> r.get(0)).containsExactly("mostly-x", "y", "none");
        assertThat(db.oql("SELECT d.title FROM Doc d WHERE d.lang = 'en' ORDER BY cosine_distance(d.embedding, :q)")
                .param("q", new float[]{1, 0, 0}).rows()).extracting(r -> r.get(0)).containsExactly("x", "mostly-x", "y", "none");
        assertThat(db.oql("SELECT d.title FROM Doc d WHERE d.lang = 'en' ORDER BY cosine_distance(d.embedding, :q) NULLS FIRST LIMIT 2")
                .param("q", new float[]{1, 0, 0}).rows()).extracting(r -> r.get(0)).containsExactly("none", "x");

        // not pushed down: descending distance, expressions, distances in WHERE
        String desc = db.oql("SELECT d.title FROM Doc d ORDER BY cosine_distance(d.embedding, :q) DESC LIMIT 1")
                .param("q", new float[]{1, 0, 0}).explain();
        assertThat(desc).contains("ORDER BY in memory");
        assertThat(db.oql("SELECT d.title FROM Doc d WHERE d.embedding IS NOT NULL ORDER BY cosine_distance(d.embedding, :q) DESC LIMIT 1")
                .param("q", new float[]{1, 0, 0}).rows().get(0).get(0)).isEqualTo("y");   // y and de-z tie: storage order
        assertThat(db.oql("SELECT d.title FROM Doc d WHERE l2_distance(d.embedding, :q) < 0.2 ORDER BY d.title")
                .param("q", new float[]{1, 0, 0}).rows()).extracting(r -> r.get(0)).containsExactly("de-x", "mostly-x", "x");
        assertThat(db.oql("SELECT d.title FROM Doc d ORDER BY cosine_distance(d.embedding, :q) + 0 LIMIT 1")
                .param("q", new float[]{1, 0, 0}).rows().get(0).get(0)).isEqualTo("x");
        assertThat(db.oql("SELECT DISTINCT d.lang FROM Doc d ORDER BY cosine_distance(d.embedding, :q)")
                .param("q", new float[]{1, 0, 0}).explain()).contains("ORDER BY in memory");

        assertThatThrownBy(() -> db.oql("SELECT d FROM Doc d ORDER BY cosine_distance(d.embedding, :q) LIMIT 1")
                .param("q", "[1, 0]").list()).hasMessageContaining("2 dimensions");
        assertThatThrownBy(() -> db.oql("SELECT d FROM Doc d ORDER BY cosine_distance(d.embedding, :q) LIMIT 1")
                .param("q", "oops").list()).isInstanceOf(OqlException.class).hasMessageContaining("is not a vector");
        assertThatThrownBy(() -> db.oql("SELECT l2_distance(d.embedding, '[1, 0]') FROM Doc d WHERE d.title = 'x'").rows())
                .isInstanceOf(OqlException.class).hasMessageContaining("different dimensions");
        assertThatThrownBy(() -> db.oql("SELECT l2_distance(d.title, '[1, 0]') FROM Doc d WHERE d.title = 'x'").rows())
                .isInstanceOf(OqlException.class).hasMessageContaining("is not a vector");
        assertThat(db.oql("SELECT cosine_distance(d.embedding, '[1, 0, 0]') FROM Doc d WHERE d.title = 'none'").rows().get(0).get(0))
                .isNull();

        assertThat(db.oql("UPDATE Doc d SET d.embedding = '[0, 0, 1]' WHERE d.title = 'none'").execute()).isEqualTo(1);
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{0, 0, 1}, 2).list()).extracting(Doc::getTitle)
                .containsExactlyInAnyOrder("de-z", "none");
    }

    @Test
    void transactions() {
        docs();
        try (Transaction tx = db.begin()) {
            tx.insert(new Doc("tx-x", "en", 1, 0, 0.01f));
            Doc y = tx.query(Doc.class).where(Doc::getTitle).eq("y").first().orElseThrow();
            tx.delete(y);
            assertThat(tx.query(Doc.class).where(Doc::getLang).eq("en").nearest(Doc::getEmbedding, new float[]{1, 0, 0}).list())
                    .extracting(Doc::getTitle).containsExactly("x", "tx-x", "mostly-x");
            assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}, 2).list()).extracting(Doc::getTitle)
                    .containsExactly("x", "de-x");                     // not committed yet
            tx.commit();
        }
        assertThat(db.from(Doc.class).where(Doc::getLang).eq("en").nearest(Doc::getEmbedding, new float[]{0, 1, 0}).list())
                .extracting(Doc::getTitle).doesNotContain("y").startsWith("mostly-x");
    }

    /** The search agrees with sorting every object by its distance, for each distance, through flushes. */
    @Test
    void differential() {
        Random rnd = new Random(42);
        List<Doc> all = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            Doc d = new Doc("d" + i, i % 3 == 0 ? "en" : "de", rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f);
            db.insert(d);
            all.add(d);
            if (i == 300) {
                db.flush();
            }
        }
        for (int i = 0; i < 50; i++) {
            db.delete(all.remove(rnd.nextInt(all.size())));
        }
        checkpoint();
        for (VectorDistance dist : VectorDistance.values()) {
            float[] q = {rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()};
            List<String> expected = all.stream().filter(d -> d.getLang().equals("en"))
                    .sorted(Comparator.comparingDouble(d -> dist.distance(d.getEmbedding(), q)))
                    .limit(7).map(Doc::getTitle).toList();
            assertThat(titles(db.from(Doc.class).where(Doc::getLang).eq("en").nearest(Doc::getEmbedding, q, dist).limit(7).list()))
                    .as(dist.name()).isEqualTo(expected);
        }
    }
}
