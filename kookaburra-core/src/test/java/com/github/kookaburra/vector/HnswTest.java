package com.github.kookaburra.vector;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.annotation.VectorQuantization;
import com.github.kookaburra.field.VectorGetter;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.model.Emb;
import com.github.kookaburra.query.QueryStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Approximate nearest-neighbour search with per-segment HNSW graphs: recall against the exact search for the three
 * distances, filters from very selective (exact fallback) to broad, deletes, several segments plus the in-memory
 * one, compaction, explain, {@code numCandidates} and {@code exact()} ({@link HnswDiskTest} runs them on disk).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class HnswTest {

    static final int DIM = 16;

    protected ObjectDB db;
    private final Random rnd = new Random(7);
    private final List<float[]> centres = new ArrayList<>();

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withVectorIndexMinRows(500);
    }

    /** Disk variant: close and reopen. */
    protected void reopen() {
    }

    @BeforeEach
    void open() {
        db = create();
        for (int c = 0; c < 20; c++) {
            centres.add(gaussian(1.0f));
        }
    }

    @AfterEach
    void close() {
        db.close();
    }

    private float[] gaussian(float sigma) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = (float) rnd.nextGaussian() * sigma;
        }
        return v;
    }

    /** A point near a random cluster centre (clustered data, like embeddings). */
    private float[] point() {
        float[] c = centres.get(rnd.nextInt(centres.size()));
        float[] v = gaussian(0.35f);
        for (int i = 0; i < DIM; i++) {
            v[i] += c[i];
        }
        return v;
    }

    private void insert(int n, int from) {
        WriteBatch batch = db.batch();
        for (int i = 0; i < n; i++) {
            batch.insert(new Emb("e" + (from + i), (from + i) % 100, point()));
        }
        batch.commit();
    }

    private static Set<String> names(List<Emb> l) {
        Set<String> s = new HashSet<>();
        l.forEach(e -> s.add(e.getName()));
        return s;
    }

    /** Mean recall@k of the default (approximate) search against exact() over {@code queries} random queries. */
    private double recall(VectorGetter<Emb> field, int k, int queries, UnaryOperator<QueryStream<Emb>> filter) {
        double sum = 0;
        for (int q = 0; q < queries; q++) {
            float[] query = point();
            Set<String> exact = names(filter.apply(db.from(Emb.class)).nearest(field, query, k).exact().list());
            List<Emb> approx = filter.apply(db.from(Emb.class)).nearest(field, query, k).list();
            assertThat(approx).hasSizeLessThanOrEqualTo(k);
            int hit = 0;
            for (Emb e : approx) {
                hit += exact.contains(e.getName()) ? 1 : 0;
            }
            sum += exact.isEmpty() ? 1 : (double) hit / exact.size();
        }
        return sum / queries;
    }

    @Test
    void recallForEachDistance() {
        insert(3000, 0);
        db.flush();
        reopen();
        String plan = db.from(Emb.class).nearest(Emb::getCos, point(), 10).explain();
        assertThat(plan).contains("HNSW, ef=100", "3000 vectors");
        assertThat(recall(Emb::getCos, 10, 30, s -> s)).isGreaterThanOrEqualTo(0.95);
        assertThat(recall(Emb::getL2, 10, 30, s -> s)).isGreaterThanOrEqualTo(0.95);
        assertThat(recall(Emb::getIp, 10, 30, s -> s)).isGreaterThanOrEqualTo(0.90);

        // the distances returned are exact, closest first
        float[] q = point();
        var approx = db.from(Emb.class).nearest(Emb::getL2, q, 5).listWithDistance();
        for (int i = 0; i < approx.size(); i++) {
            assertThat(approx.get(i).distance()).isEqualTo(VectorDistance.L2.distance(approx.get(i).value().getL2(), q));
            if (i > 0) {
                assertThat(approx.get(i).distance()).isGreaterThanOrEqualTo(approx.get(i - 1).distance());
            }
        }
    }

    @Test
    void filters() {
        insert(3000, 0);
        db.flush();
        reopen();
        // 1 %: 30 matches <= ef, answered exactly
        assertThat(recall(Emb::getCos, 10, 10, s -> s.where(Emb::getGrp).eq(42))).isEqualTo(1.0);
        // 5 %: 150 matches; the graph search gives up when it compares more vectors than there are matches
        assertThat(recall(Emb::getCos, 10, 10, s -> s.where(Emb::getGrp).lt(5))).isGreaterThanOrEqualTo(0.95);
        // 50 %: graph search with the filter as accept set
        assertThat(recall(Emb::getCos, 10, 20, s -> s.where(Emb::getGrp).lt(50))).isGreaterThanOrEqualTo(0.9);
        for (Emb e : db.from(Emb.class).where(Emb::getGrp).lt(50).nearest(Emb::getCos, point(), 20).list()) {
            assertThat(e.getGrp()).isLessThan(50);
        }
        assertThat(db.from(Emb.class).where(Emb::getGrp).eq(1000).nearest(Emb::getCos, point(), 5).list()).isEmpty();
    }

    @Test
    void deletesSegmentsAndCompaction() {
        insert(2000, 0);
        db.flush();
        insert(2000, 2000);
        db.flush();
        insert(100, 4000);                                            // stays in the in-memory segment
        List<Emb> gone = db.from(Emb.class).where(Emb::getGrp).lt(30).list();
        WriteBatch del = db.batch();
        gone.forEach(del::delete);
        del.commit();
        Set<String> deleted = names(gone);
        reopen();
        String plan = db.from(Emb.class).nearest(Emb::getCos, point(), 10).explain();
        assertThat(plan).contains("HNSW, ef=100");
        for (int i = 0; i < 10; i++) {
            for (Emb e : db.from(Emb.class).nearest(Emb::getCos, point(), 10).list()) {
                assertThat(deleted).doesNotContain(e.getName());
            }
        }
        assertThat(recall(Emb::getCos, 10, 20, s -> s)).isGreaterThanOrEqualTo(0.95);

        db.compact();
        reopen();
        assertThat(db.from(Emb.class).nearest(Emb::getCos, point(), 10).explain()).contains("HNSW, ef=100");
        assertThat(recall(Emb::getL2, 10, 20, s -> s)).isGreaterThanOrEqualTo(0.95);
        assertThat(db.from(Emb.class).nearest(Emb::getCos, point()).count()).isEqualTo(4100 - gone.size());
    }

    @Test
    void candidatesExactAndOtherDistances() {
        insert(2500, 0);
        db.flush();
        reopen();
        float[] q = point();
        List<String> exact = db.from(Emb.class).nearest(Emb::getCos, q, 10).exact().list().stream().map(Emb::getName).toList();
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, 10).numCandidates(2000).list().stream().map(Emb::getName).toList())
                .isEqualTo(exact);
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, 10).numCandidates(400).explain()).contains("HNSW, ef=400");
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, 10).exact().explain()).contains("exact scan of the vector column");
        // a distance the graph was not built for: exact scan
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, VectorDistance.L2).limit(10).explain())
                .contains("exact scan of the vector column");
        List<String> l2 = db.from(Emb.class).nearest(Emb::getL2, q, 10).exact().list().stream().map(Emb::getName).toList();
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, VectorDistance.L2).limit(10).list().stream().map(Emb::getName).toList())
                .isEqualTo(l2);
        // k larger than ef: ef grows to k
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q, 300).list()).hasSize(300);
        // no limit: every vector, exactly
        assertThat(db.from(Emb.class).nearest(Emb::getCos, q).explain()).contains("exact scan of the vector column");

        assertThatThrownBy(() -> db.from(Emb.class).numCandidates(10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Emb.class).exact()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Emb.class).nearest(Emb::getCos, q, 10).numCandidates(0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void smallSegmentsHaveNoGraphAndValuesRoundTrip() {
        insert(200, 0);
        db.flush();
        reopen();
        assertThat(db.from(Emb.class).nearest(Emb::getCos, point(), 5).explain()).contains("200 vectors, no graph");
        Emb e = db.from(Emb.class).where(Emb::getName).eq("e7").first().orElseThrow();
        float[] v = e.getCos();
        v[0] += 1;                                                     // a copy: the stored vector is unchanged
        assertThat(db.from(Emb.class).where(Emb::getName).eq("e7").first().orElseThrow().getCos()[0]).isNotEqualTo(v[0]);
        assertThat(db.from(Emb.class).nearest(Emb::getCos, db.from(Emb.class).where(Emb::getName).eq("e7").first()
                .orElseThrow().getCos(), 1).first().orElseThrow().getName()).isEqualTo("e7");
    }

    @Test
    void int8() {
        insert(3000, 0);
        db.flush();
        reopen();
        assertThat(db.from(Emb.class).nearest(Emb::getQ8, point(), 10).explain()).contains("HNSW m=16, int8, 3000 vectors");
        // the graph search reads one byte per component; results are re-ranked with the floats
        assertThat(recall(Emb::getQ8, 10, 30, s -> s)).isGreaterThanOrEqualTo(0.95);
        float[] q = point();
        var near = db.from(Emb.class).nearest(Emb::getQ8, q, 5).listWithDistance();
        for (var n : near) {
            assertThat(n.distance()).isEqualTo(VectorDistance.L2.distance(n.value().getQ8(), q));      // exact distances
        }
        assertThat(near).extracting(n -> n.value().getName())
                .isEqualTo(db.from(Emb.class).nearest(Emb::getL2, q, 5).list().stream().map(Emb::getName).toList());
        assertThat(recall(Emb::getQ8, 10, 10, s -> s.where(Emb::getGrp).lt(50))).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void int8EdgeCases() {
        // all components equal: a zero-width range must not divide by zero
        WriteBatch b = db.batch();
        for (int i = 0; i < 600; i++) {
            float[] v = new float[DIM];
            Arrays.fill(v, 0.5f);
            b.insert(new Emb("same" + i, i % 3, v));
        }
        b.commit();
        db.flush();
        reopen();
        assertThat(db.from(Emb.class).nearest(Emb::getQ8, new float[DIM], 5).list()).hasSize(5);
        assertThat(db.from(Emb.class).nearest(Emb::getQ8, new float[DIM], 5).listWithDistance().get(0).distance())
                .isEqualTo(Math.sqrt(DIM * 0.25), within(1e-6));
        assertThat(TypeDef.vector(3, VectorDistance.COSINE, VectorQuantization.INT8).toString()).isEqualTo("VECTOR(3, COSINE INT8)");
    }
}
