package com.github.kookaburra.embed;

import com.github.kookaburra.KookaburraException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Embedder} for Ollama's {@code POST /api/embed} ({@code {"model": m, "input": [texts]}} →
 * {@code {"embeddings": [[...], ...]}}); texts are sent in batches of 64.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class OllamaEmbedder implements Embedder {

    static final String DEFAULT_URL = "http://localhost:11434";
    private static final int BATCH = 64;

    private final URI endpoint;
    private final String base;
    private final String model;
    private final Duration timeout;
    private volatile HttpClient client;

    OllamaEmbedder(String url, String model, Duration timeout) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("an embedding model is required, e.g. nomic-embed-text");
        }
        String b = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.endpoint = URI.create(base + "/api/embed");
        this.model = model.strip();
        this.timeout = timeout;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            out.addAll(request(texts.subList(i, Math.min(texts.size(), i + BATCH))));
        }
        return out;
    }

    @Override
    public String describe() {
        return "ollama " + model + " at " + base;
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            client = c;
        }
        return c;
    }

    private List<float[]> request(List<String> texts) {
        StringBuilder body = new StringBuilder("{\"model\":").append(Json.quote(model)).append(",\"input\":[");
        for (int i = 0; i < texts.size(); i++) {
            body.append(i == 0 ? "" : ",").append(Json.quote(texts.get(i)));
        }
        body.append("]}");
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> res;
        try {
            res = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new KookaburraException("Ollama is not reachable at " + base + ": start it (ollama serve) or set "
                    + URL + " (" + describe() + ")", e);
        } catch (HttpTimeoutException e) {
            throw new KookaburraException("Ollama did not answer within " + timeout.toSeconds() + " s (" + TIMEOUT + ")", e);
        } catch (IOException e) {
            throw new KookaburraException("embedding request to " + base + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KookaburraException("interrupted while embedding", e);
        }
        Object json;
        try {
            json = Json.parse(res.body());
        } catch (IllegalArgumentException e) {
            throw new KookaburraException("Ollama answered HTTP " + res.statusCode() + " with something that is not JSON", e);
        }
        if (res.statusCode() / 100 != 2) {
            String error = json instanceof Map<?, ?> m && m.get("error") != null ? String.valueOf(m.get("error")) : res.body();
            String hint = res.statusCode() == 404 ? " (pull it first: ollama pull " + model + ")" : "";
            throw new KookaburraException("Ollama: " + error + hint);
        }
        if (!(json instanceof Map<?, ?> m) || !(m.get("embeddings") instanceof List<?> list) || list.size() != texts.size()) {
            throw new KookaburraException("unexpected answer from Ollama: no embeddings for " + texts.size() + " text(s)");
        }
        List<float[]> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (!(e instanceof List<?> nums) || nums.isEmpty()) {
                throw new KookaburraException("unexpected answer from Ollama: an embedding is not a list of numbers");
            }
            float[] v = new float[nums.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = ((Number) nums.get(i)).floatValue();
            }
            out.add(v);
        }
        return out;
    }
}
