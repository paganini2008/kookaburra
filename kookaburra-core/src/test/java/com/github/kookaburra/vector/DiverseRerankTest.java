package com.github.kookaburra.vector;

import com.github.kookaburra.Config;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.embed.Embedder;
import com.github.kookaburra.embed.Reranker;
import com.github.kookaburra.model.Doc;
import com.github.kookaburra.query.Neighbor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Re-ordering of the closest candidates: maximal marginal relevance ({@code diverse}) and a reranker model
 * ({@code rerank}) against a local stub of Ollama's {@code /api/generate} with log-probabilities
 * ({@link DiverseRerankDiskTest} runs them on disk).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class DiverseRerankTest {

    protected ObjectDB db;
    private HttpServer server;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();
    /** Next answer: status and body, or null for the normal judgement. */
    private volatile Object[] canned;

    protected ObjectDB create(Config config) {
        return TestDbs.create(config);
    }

    /** Disk variant: flush into segments first. */
    protected void checkpoint() {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", ex -> {
            requests.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object[] c = canned;
            int status = 200;
            String out;
            if (c != null) {
                status = (int) c[0];
                out = (String) c[1];
            } else {
                // "yes" when the document mentions bread, the stronger the more often
                String doc = body.substring(body.indexOf("<Document>:"));
                int hits = doc.split("bread", -1).length - 1;
                double yes = hits == 0 ? -4 : -1.0 / hits;
                out = "{\"response\":\"yes\",\"logprobs\":[{\"token\":\"yes\",\"logprob\":" + yes + ",\"top_logprobs\":["
                        + "{\"token\":\"yes\",\"logprob\":" + yes + "},{\"token\":\" No\",\"logprob\":-1.0},{\"token\":\"maybe\",\"logprob\":-3}]}]}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        Embedder embedder = text -> text.contains("bread") ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
        db = create(Config.defaults().withEmbedder(embedder).withReranker(Reranker.ollama(url, "judge")));
        // five near-duplicates of the x axis, two variants a bit apart, one far away
        float[][] v = {{1, 0, 0}, {1, 0.01f, 0}, {1, 0, 0.01f}, {1, 0.02f, 0}, {1, 0, 0.02f}, {1, 0.5f, 0}, {1, 0, 0.5f}, {0, 1, 0}};
        String[] titles = {"x1 toast", "x2 toast", "x3 toast", "x4 toast", "x5 toast", "xy bread bread", "xz bread", "y salad"};
        for (int i = 0; i < v.length; i++) {
            db.insert(new Doc(titles[i], "en", v[i]));
        }
        checkpoint();
    }

    @AfterEach
    void stop() {
        db.close();
        server.stop(0);
    }

    private static List<String> titles(List<Doc> l) {
        return l.stream().map(d -> d.getTitle().split(" ")[0]).toList();
    }

    @Test
    void diverse() {
        float[] q = {1, 0, 0};
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).list())).containsExactly("x1", "x2", "x3");
        // score = -0.3 · distance to the query + 0.7 · distance to the closest pick: the near-duplicates lose
        // (second pick: y scores -0.3 · 1 + 0.7 · 1 = 0.4, xy -0.3 · 0.106 + 0.7 · 0.106 = 0.042; third: xy and xz tie
        // at 0.042, storage order decides)
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(0.3, 0).list())).containsExactly("x1", "y", "xy");
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(1, 0).list())).containsExactly("x1", "x2", "x3");
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(0, 0).list()).get(1)).isEqualTo("y");
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(0.3, 4).list())).startsWith("x1", "x4")
                .doesNotContain("xy", "xz", "y");                                       // only the 4 closest are considered
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, q).limit(4).skip(1).diverse(0.3, 0).list()))
                .containsExactly("y", "xy", "xz");
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse().list()).hasSize(3).first()
                .extracting(Doc::getTitle).isEqualTo("x1 toast");
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse().count()).isEqualTo(3);
        assertThat(db.from(Doc.class).where(Doc::getLang).eq("de").nearest(Doc::getEmbedding, q, 3).diverse().list()).isEmpty();
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse().explain())
                .contains("Diverse  maximal marginal relevance, lambda=0.5, from max(20, 4 k) candidates");
        List<Neighbor<Doc>> n = db.from(Doc.class).nearest(Doc::getEmbedding, q, 2).diverse().listWithDistance();
        assertThat(n.get(1).distance()).isGreaterThan(0);
        assertThat(n.get(1).score()).isNaN();

        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, q).diverse().list())
                .hasMessageContaining("diverse() needs a limit");
        assertThatThrownBy(() -> db.from(Doc.class).diverse()).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(1.5, 0)).hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, q, 3).diverse(0.5, -1)).hasMessageContaining("negative fetch");
    }

    @Test
    void rerank() {
        // vector order: x1 … x5 first; the reranker prefers the documents about bread
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, "fresh bread", 2).list())).containsExactly("x1", "x2");
        requests.set(0);
        List<Neighbor<Doc>> top = db.from(Doc.class).nearest(Doc::getEmbedding, "fresh bread", 2).rerank(Doc::getTitle).listWithDistance();
        assertThat(top).extracting(x -> x.value().getTitle()).containsExactly("xy bread bread", "xz bread");
        assertThat(top.get(0).score()).isGreaterThan(top.get(1).score()).isLessThanOrEqualTo(1);
        assertThat(requests.get()).isEqualTo(8);                                 // all 8 objects (fewer than 20) re-scored
        requests.set(0);
        assertThat(titles(db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}, 1).rerank(Doc::getTitle, "bread", 6).list()))
                .containsExactly("xy");
        assertThat(requests.get()).isEqualTo(6);
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank("title", null, 0).list()).hasSize(2);
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).explain())
                .contains("Rerank  by title with ollama judge at " + url + ", max(20, 4 k) candidates");
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).diverse().list()).hasSize(2);  // last one wins
        assertThat(db.from(Doc.class).where(Doc::getLang).eq("fr").nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).list()).isEmpty();

        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, new float[]{1, 0, 0}, 2).rerank(Doc::getTitle))
                .hasMessageContaining("needs the query text");
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread").rerank(Doc::getTitle).list())
                .hasMessageContaining("rerank() needs a limit");
        assertThatThrownBy(() -> db.from(Doc.class).rerank(Doc::getTitle)).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle, null, -1))
                .hasMessageContaining("negative fetch");
    }

    @Test
    void rerankErrors() throws IOException {
        canned = new Object[]{404, "{\"error\":\"model \\\"judge\\\" not found\"}"};
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).list())
                .isInstanceOf(KookaburraException.class).hasMessageContaining("not found").hasMessageContaining("ollama pull judge");
        canned = new Object[]{200, "{\"response\":\"yes\"}"};
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).list())
                .hasMessageContaining("no token probabilities");
        canned = new Object[]{500, "oops"};
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).list())
                .hasMessageContaining("not JSON");
        canned = new Object[]{500, "{\"other\":1}"};
        assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).list())
                .hasMessageContaining("Ollama: {\"other\":1}");
        canned = new Object[]{200, "{\"logprobs\":[{\"token\":\"maybe\",\"logprob\":-0.1}]}"};    // neither yes nor no: 0
        assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "bread", 2).rerank(Doc::getTitle).listWithDistance().get(0).score())
                .isZero();

        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Reranker down = Reranker.ollama("http://127.0.0.1:" + port + "/", "m");
        assertThat(down.describe()).isEqualTo("ollama m at http://127.0.0.1:" + port);
        assertThatThrownBy(() -> down.score("q", List.of("a", "b"))).hasMessageContaining("Ollama is not reachable");
        assertThat(down.score("q", List.of())).isEmpty();

        try (ObjectDB none = TestDbs.create(Config.defaults().withEmbedder(text -> new float[]{1, 0, 0}).withReranker(null))) {
            none.insert(new Doc("a", "en", 1, 0, 0));
            assertThatThrownBy(() -> none.from(Doc.class).nearest(Doc::getEmbedding, "x", 1).rerank(Doc::getTitle).list())
                    .hasMessageContaining("no reranker configured");
            assertThat(none.from(Doc.class).nearest(Doc::getEmbedding, "x", 1).rerank(Doc::getTitle).explain())
                    .contains("no reranker configured");
        }
    }

    @Test
    void settings() {
        Map<String, String> s = new HashMap<>();
        s.put(Reranker.RERANKER, "none");
        assertThat(Reranker.fromSettings(s)).isNull();
        s.put(Reranker.RERANKER, "Ollama");
        s.put(Reranker.MODEL, "qwen3:4b");
        s.put(Reranker.URL, url);
        s.put(Reranker.TIMEOUT, "5s");
        assertThat(Reranker.fromSettings(s).describe()).isEqualTo("ollama qwen3:4b at " + url);
        s.put(Reranker.URL, "");
        assertThat(Reranker.fromSettings(s).describe()).endsWith("at http://localhost:11434");
        assertThat(Config.defaults().reranker().describe()).startsWith("ollama ");      // bundled default, contacted lazily
        s.put(Reranker.RERANKER, "cohere");
        assertThatThrownBy(() -> Reranker.fromSettings(s)).hasMessageContaining("expected ollama or none");
        s.put(Reranker.RERANKER, "ollama");
        s.put(Reranker.MODEL, "");
        assertThatThrownBy(() -> Reranker.fromSettings(s)).hasMessageContaining("is required");
        assertThatThrownBy(() -> Reranker.ollama(null, " ")).hasMessageContaining("model is required");
        Reranker custom = (q, docs) -> new double[docs.size()];
        assertThat(custom.describe()).isNotBlank();
    }
}
