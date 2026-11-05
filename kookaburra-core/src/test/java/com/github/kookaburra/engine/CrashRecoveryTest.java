package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.model.Article;
import com.github.kookaburra.model.Emb;
import com.github.kookaburra.text.TextQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash images (a copy of the directory while the database is open, as after a power cut) with the search
 * features: full-text indexes and vector columns rebuilt from the WAL for what was not flushed, int8 vectors,
 * phrases and highlighting, and transactions committed with ASYNC durability (all or nothing).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class CrashRecoveryTest {

    @TempDir
    Path tmp;

    private static Config config() {
        return Config.defaults().withTransactions(true).withVectorIndexMinRows(100);
    }

    private static float[] vector(Random r) {
        float[] v = new float[16];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) r.nextGaussian();
        }
        return v;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        Files.deleteIfExists(to.resolve("LOCK"));
    }

    @Test
    void searchIndexesAfterACrash() throws IOException {
        Path dir = tmp.resolve("db");
        Path crash = tmp.resolve("crash");
        Random r = new Random(4);
        float[] q = vector(r);
        List<String> expectedNear;
        List<String> expectedText;
        try (ObjectDB db = ObjectDB.open(dir, config())) {
            WriteBatch b = db.batch();
            for (int i = 0; i < 300; i++) {
                b.insert(new Emb("e" + i, i % 10, vector(r)));
            }
            b.insert(new Article("en", "Fresh bread from the oven every morning", 1, 0));
            b.commit();
            db.flush();                                                     // in segments: graphs, int8, inverted index
            WriteBatch more = db.batch();
            for (int i = 300; i < 400; i++) {
                more.insert(new Emb("e" + i, i % 10, vector(r)));
            }
            more.insert(new Article("en", "Bread, bread and more bread: a baker's diary", 0, 1));
            more.commit();                                                  // only in the WAL (SYNC: on disk)
            expectedNear = db.from(Emb.class).nearest(Emb::getQ8, q, 10).exact().list().stream().map(Emb::getName).toList();
            expectedText = db.from(Article.class).search(Article::getBody, "\"more bread\" oven", 5).list().stream()
                    .map(Article::getBody).toList();
            copy(dir, crash);                                               // power cut: no close, no flush
        }
        try (ObjectDB recovered = ObjectDB.open(crash, config())) {
            assertThat(recovered.from(Emb.class).count()).isEqualTo(400);
            assertThat(recovered.from(Emb.class).nearest(Emb::getQ8, q, 10).numCandidates(400).list().stream().map(Emb::getName).toList())
                    .isEqualTo(expectedNear);
            assertThat(recovered.from(Article.class).search(Article::getBody, "\"more bread\" oven", 5).list().stream()
                    .map(Article::getBody).toList()).isEqualTo(expectedText);
            assertThat(recovered.from(Article.class).where(Article::getBody).matches("\"baker's diary\"").count()).isEqualTo(1);
            String body = recovered.from(Article.class).where(Article::getBody).matches("diary").first().orElseThrow().getBody();
            assertThat(TextQuery.parse("diary").highlight(body)).contains("<em>diary</em>");
            recovered.flush();                                              // the replayed rows become segments too
            assertThat(recovered.from(Emb.class).nearest(Emb::getQ8, q, 10).explain()).contains("int8");
        }
    }

    @Test
    void asyncTransactionsAreAllOrNothing() throws IOException {
        Path dir = tmp.resolve("db");
        Path crash = tmp.resolve("crash");
        try (ObjectDB db = ObjectDB.open(dir, config())) {
            db.insert(new Article("en", "before"));
            db.inTransaction(TxOptions.defaults().durability(Durability.ASYNC), tx -> {
                for (int i = 0; i < 3; i++) {
                    tx.insert(new Article("tx", "async " + i));
                }
            });
            copy(dir, crash);
        }
        // a torn tail: half of a record after the last complete one
        Path wal;
        try (Stream<Path> s = Files.list(crash.resolve("wal"))) {
            wal = s.max(Path::compareTo).orElseThrow();
        }
        Files.write(wal, new byte[]{40, 0, 0, 0, 9, 9}, StandardOpenOption.APPEND);
        try (ObjectDB recovered = ObjectDB.open(crash, config())) {
            assertThat(recovered.from(Article.class).where(Article::getLang).eq("en").count()).isEqualTo(1);
            assertThat(recovered.from(Article.class).where(Article::getLang).eq("tx").count()).isIn(0L, 3L);
        }
    }
}
