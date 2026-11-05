package com.github.kookaburra.embed;

import com.github.kookaburra.Config;
import com.github.kookaburra.Settings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Re-scores the candidates of a search against the query text with a model that reads both (a cross-encoder or an
 * LLM judging relevance): slower than vector distances, more accurate. Used by {@code nearest(...).rerank(...)}.
 * Built in: Ollama with any instruct or reranker model (the yes / no relevance judgement of Qwen3-Reranker);
 * implement it to plug in anything else and pass it with {@code Config.withReranker(...)}.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public interface Reranker {

    String RERANKER = "kookaburra.reranker";
    String URL = "kookaburra.reranker-url";
    String MODEL = "kookaburra.reranker-model";
    String TIMEOUT = "kookaburra.reranker-timeout";

    /** Relevance of each document to the query, in order; higher is more relevant (any scale). */
    double[] score(String query, List<String> documents);

    /** Short description, e.g. {@code ollama qwen3:4b}. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** Ollama at {@code url} (null = {@code http://localhost:11434}) with an instruct or reranker model. */
    static Reranker ollama(String url, String model) {
        return new OllamaReranker(url, model, Duration.ofSeconds(60));
    }

    /**
     * The reranker of the settings ({@code kookaburra.reranker=ollama|none}, {@code kookaburra.reranker-url},
     * {@code -model}, {@code -timeout}), or null for {@code none}. Nothing is contacted until the first rerank.
     */
    static Reranker fromSettings(Map<String, String> settings) {
        String kind = Settings.get(settings, RERANKER).strip().toLowerCase(Locale.ROOT);
        if (kind.isEmpty() || kind.equals("none")) {
            return null;
        }
        if (!kind.equals("ollama")) {
            throw new IllegalArgumentException(RERANKER + ": expected ollama or none, got '" + kind + "'");
        }
        String model = Settings.get(settings, MODEL).strip();
        if (model.isEmpty()) {
            throw new IllegalArgumentException(MODEL + " is required with " + RERANKER + "=" + kind);
        }
        String url = Settings.get(settings, URL).strip();
        Duration timeout = Duration.ofMillis(Config.millis(Settings.get(settings, TIMEOUT), TIMEOUT));
        return new OllamaReranker(url.isEmpty() ? null : url, model, timeout);
    }
}
