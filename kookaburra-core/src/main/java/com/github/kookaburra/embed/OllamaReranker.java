package com.github.kookaburra.embed;

import com.github.kookaburra.KookaburraException;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link Reranker} on Ollama's {@code POST /api/generate}: per document the model is asked whether the document
 * meets the query and answers one token; the score is {@code P(yes) / (P(yes) + P(no))} from the token's
 * log-probabilities (the Qwen3-Reranker method; works with reranker and general instruct models). Requests are sent
 * concurrently; Ollama queues them.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class OllamaReranker implements Reranker {

    /** Longest document text sent (characters); longer texts are cut. */
    static final int MAX_CHARS = 4000;

    private static final String SYSTEM = "Judge whether the Document meets the requirements based on the Query. "
            + "Note that the answer can only be \"yes\" or \"no\".";

    private final URI endpoint;
    private final String base;
    private final String model;
    private final Duration timeout;
    private volatile HttpClient client;

    OllamaReranker(String url, String model, Duration timeout) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("a reranker model is required, e.g. qwen3:4b");
        }
        String b = url == null || url.isBlank() ? OllamaEmbedder.DEFAULT_URL : url.strip();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.endpoint = URI.create(base + "/api/generate");
        this.model = model.strip();
        this.timeout = timeout;
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

    @Override
    public double[] score(String query, List<String> documents) {
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (String d : documents) {
            pending.add(client().sendAsync(request(query, d), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }
        double[] out = new double[documents.size()];
        for (int i = 0; i < out.length; i++) {
            HttpResponse<String> res;
            try {
                res = pending.get(i).join();
            } catch (CompletionException e) {
                pending.forEach(f -> f.cancel(true));
                throw failure(e.getCause() == null ? e : e.getCause());
            }
            out[i] = parse(res);
        }
        return out;
    }

    private HttpRequest request(String query, String document) {
        String doc = document == null ? "" : document.length() > MAX_CHARS ? document.substring(0, MAX_CHARS) : document;
        String prompt = "<Query>: " + query + "\n<Document>: " + doc + "\nDoes the Document meet the Query? Answer yes or no.";
        String body = "{\"model\":" + Json.quote(model) + ",\"system\":" + Json.quote(SYSTEM) + ",\"prompt\":" + Json.quote(prompt)
                + ",\"stream\":false,\"think\":false,\"logprobs\":true,\"top_logprobs\":10,"
                + "\"options\":{\"num_predict\":1,\"temperature\":0}}";
        return HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private KookaburraException failure(Throwable t) {
        if (t instanceof ConnectException) {
            return new KookaburraException("Ollama is not reachable at " + base + ": start it (ollama serve) or set " + URL, t);
        }
        if (t instanceof HttpTimeoutException) {
            return new KookaburraException("Ollama did not answer within " + timeout.toSeconds() + " s (" + TIMEOUT + ")", t);
        }
        return new KookaburraException("rerank request to " + base + " failed: " + t, t);
    }

    /** P(yes) / (P(yes) + P(no)) of the first answer token. */
    private double parse(HttpResponse<String> res) {
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
        if (!(json instanceof Map<?, ?> m) || !(m.get("logprobs") instanceof List<?> tokens) || tokens.isEmpty()
                || !(tokens.get(0) instanceof Map<?, ?> first)) {
            throw new KookaburraException("Ollama returned no token probabilities: it needs a version with logprobs support"
                    + " (0.12.11 or later)");
        }
        double yes = 0;
        double no = 0;
        List<?> top = first.get("top_logprobs") instanceof List<?> l ? l : List.of(first);
        for (Object o : top) {
            if (o instanceof Map<?, ?> t && t.get("token") instanceof String token && t.get("logprob") instanceof Number lp) {
                String w = token.strip().toLowerCase(Locale.ROOT);
                if (w.equals("yes")) {
                    yes += Math.exp(lp.doubleValue());
                } else if (w.equals("no")) {
                    no += Math.exp(lp.doubleValue());
                }
            }
        }
        return yes + no == 0 ? 0 : yes / (yes + no);
    }
}
