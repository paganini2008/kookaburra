package com.github.kookaburra.embed;

import com.github.kookaburra.Config;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.model.Doc;
import com.github.kookaburra.oql.OqlException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Ollama embedder against a local stub of {@code /api/embed}: batching, settings, error answers, and text
 * search through {@code nearest(field, "text", k)} and OQL {@code EMBED()}.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class EmbedderTest {

    private HttpServer server;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();
    /** Next answer: status and body, or null for the normal embedding answer. */
    private volatile Object[] canned;

    /** A 3-dimensional "embedding": counts of the words book / phone / garden. */
    static float[] fake(String text) {
        String t = text.toLowerCase();
        return new float[]{count(t, "book") + 0.01f, count(t, "phone") + 0.01f, count(t, "garden") + 0.01f};
    }

    private static int count(String t, String w) {
        int n = 0;
        for (int i = t.indexOf(w); i >= 0; i = t.indexOf(w, i + 1)) {
            n++;
        }
        return n;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", ex -> {
            requests.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String out;
            Object[] c = canned;
            if (c != null) {
                status = (int) c[0];
                out = (String) c[1];
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = (Map<String, Object>) Json.parse(body);
                StringBuilder sb = new StringBuilder("{\"model\":\"" + req.get("model") + "\",\"embeddings\":[");
                List<?> input = (List<?>) req.get("input");
                for (int i = 0; i < input.size(); i++) {
                    float[] v = fake((String) input.get(i));
                    sb.append(i == 0 ? "" : ",").append('[').append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append(']');
                }
                out = sb.append("]}").toString();
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void embedsInBatches() {
        Embedder e = Embedder.ollama(url, "test-model");
        assertThat(e.embed("a book about a phone")).containsExactly(1.01f, 1.01f, 0.01f);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            texts.add(i % 2 == 0 ? "garden " + i : "book \"quoted\"\n\t\\ é " + i);
        }
        requests.set(0);
        List<float[]> all = e.embedAll(texts);
        assertThat(all).hasSize(150);
        assertThat(requests.get()).isEqualTo(3);                                // 64 + 64 + 22
        assertThat(all.get(0)).containsExactly(0.01f, 0.01f, 1.01f);
        assertThat(all.get(149)).containsExactly(1.01f, 0.01f, 0.01f);
        assertThat(e.describe()).isEqualTo("ollama test-model at http://127.0.0.1:" + server.getAddress().getPort());

        Embedder custom = text -> new float[]{1};                               // the interface's own defaults
        assertThat(custom.embedAll(List.of("a", "b"))).hasSize(2);
        assertThat(custom.describe()).isNotBlank();
    }

    @Test
    void settings() {
        Map<String, String> s = new HashMap<>();
        s.put(Embedder.EMBEDDER, "none");
        assertThat(Embedder.fromSettings(s)).isNull();
        assertThat(Config.fromSettings(s).embedder()).isNull();
        s.put(Embedder.EMBEDDER, "Ollama");
        s.put(Embedder.URL, url);
        s.put(Embedder.MODEL, "m1");
        s.put(Embedder.TIMEOUT, "5s");
        assertThat(Embedder.fromSettings(s).describe()).startsWith("ollama m1 at http://127.0.0.1");
        assertThat(Config.fromSettings(s).embedder().embed("phone")).containsExactly(0.01f, 1.01f, 0.01f);
        s.put(Embedder.URL, "");
        assertThat(Embedder.fromSettings(s).describe()).endsWith("at " + OllamaEmbedder.DEFAULT_URL);
        assertThat(Config.defaults().embedder().describe()).contains("ollama");      // bundled default: Ollama, lazily

        s.put(Embedder.EMBEDDER, "magic");
        assertThatThrownBy(() -> Embedder.fromSettings(s)).hasMessageContaining("expected ollama or none");
        s.put(Embedder.EMBEDDER, "ollama");
        s.put(Embedder.MODEL, " ");
        assertThatThrownBy(() -> Embedder.fromSettings(s)).hasMessageContaining("is required");
        assertThatThrownBy(() -> Embedder.ollama(url, null)).hasMessageContaining("model is required");
    }

    @Test
    void errorAnswers() throws IOException {
        Embedder e = new OllamaEmbedder(url, "missing", Duration.ofSeconds(5));
        canned = new Object[]{404, "{\"error\":\"model \\\"missing\\\" not found\"}"};
        assertThatThrownBy(() -> e.embed("x")).isInstanceOf(KookaburraException.class)
                .hasMessageContaining("model \"missing\" not found").hasMessageContaining("ollama pull missing");
        canned = new Object[]{500, "<html>oops</html>"};
        assertThatThrownBy(() -> e.embed("x")).hasMessageContaining("not JSON");
        canned = new Object[]{500, "{\"other\":1}"};
        assertThatThrownBy(() -> e.embed("x")).hasMessageContaining("Ollama: {\"other\":1}");
        canned = new Object[]{200, "{\"embeddings\":[]}"};
        assertThatThrownBy(() -> e.embed("x")).hasMessageContaining("no embeddings");
        canned = new Object[]{200, "{\"embeddings\":[[]]}"};
        assertThatThrownBy(() -> e.embed("x")).hasMessageContaining("not a list of numbers");
        canned = new Object[]{200, "[1, 2]"};
        assertThatThrownBy(() -> e.embed("x")).hasMessageContaining("no embeddings");

        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        assertThatThrownBy(() -> Embedder.ollama("http://127.0.0.1:" + port, "m").embed("x"))
                .hasMessageContaining("Ollama is not reachable").hasMessageContaining("ollama serve");
    }

    @Test
    void timeout() {
        server.removeContext("/api/embed");
        server.createContext("/api/embed", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        assertThatThrownBy(() -> new OllamaEmbedder(url, "m", Duration.ofMillis(200)).embed("x"))
                .hasMessageContaining("did not answer within");
    }

    @Test
    void json() {
        assertThat(Json.parse(" {\"a\": [1, -2.5e1, true, false, null, \"x\\u0041\\n\\t\\r\\b\\f\\/\"], \"b\": {}, \"c\": []} "))
                .isEqualTo(Map.of("a", Arrays.asList(1.0, -25.0, true, false, null, "xA\n\t\r\b\f/"), "b", Map.of(), "c", List.of()));
        for (String bad : List.of("", "{", "[1,", "{\"a\" 1}", "{\"a\":1,}", "[1 2]", "\"abc", "tru", "{1:2}", "[1]x", "-", "\"\\u12\"", "1e", "{\"a\":1 \"b\":2}", "\"\\")) {
            assertThatThrownBy(() -> Json.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Json.quote("a\"b\\c\n\r\t\u0001")).isEqualTo("\"a\\\"b\\\\c\\n\\r\\t\\u0001\"");
    }

    @Test
    void textSearch() {
        try (ObjectDB db = TestDbs.create(Config.defaults().withEmbedder(Embedder.ollama(url, "m")))) {
            db.insert(new Doc("reading", "en", fake("book book")));
            db.insert(new Doc("mobile", "en", fake("phone")));
            db.insert(new Doc("outdoor", "en", fake("garden garden")));
            assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "a garden in spring", 1).first().orElseThrow().getTitle())
                    .isEqualTo("outdoor");
            assertThat(db.from(Doc.class).nearest(Doc::getEmbedding, "phone").list().get(0).getTitle()).isEqualTo("mobile");

            requests.set(0);
            var rows = db.oql("SELECT d.title FROM Doc d ORDER BY cosine_distance(d.embedding, embed('my book club')) LIMIT 2").rows();
            assertThat(rows.get(0).get(0)).isEqualTo("reading");
            assertThat(db.oql("SELECT d.title, cosine_distance(d.embedding, embed('phone')) AS x FROM Doc d WHERE d.title = 'mobile'")
                    .rows()).hasSize(1);
            assertThat(db.oql("SELECT VECTOR_DIMS(embed(:t)) FROM Doc d WHERE d.title = 'mobile'").param("t", "x").rows().get(0).get(0))
                    .isEqualTo(3L);
            assertThat(requests.get()).isEqualTo(3);                          // one per distinct text and statement
            assertThatThrownBy(() -> db.oql("SELECT embed('a', 'b') FROM Doc d").list()).isInstanceOf(OqlException.class)
                    .hasMessageContaining("EMBED takes 1 argument");
            canned = new Object[]{404, "{\"error\":\"model not found\"}"};
            assertThatThrownBy(() -> db.oql("SELECT d FROM Doc d ORDER BY cosine_distance(d.embedding, embed('x')) LIMIT 1").list())
                    .isInstanceOf(OqlException.class).hasMessageContaining("EMBED: Ollama: model not found");
        }
        try (ObjectDB db = TestDbs.create(Config.defaults().withEmbedder(null))) {
            assertThatThrownBy(() -> db.from(Doc.class).nearest(Doc::getEmbedding, "x", 1))
                    .hasMessageContaining("no embedder configured");
            db.insert(new Doc("a", "en", 1, 0, 0));
            assertThatThrownBy(() -> db.oql("SELECT d FROM Doc d ORDER BY cosine_distance(d.embedding, embed('x')) LIMIT 1").list())
                    .hasMessageContaining("no embedder configured");
        }
    }
}
