package com.github.kookaburra.embed;

import com.github.kookaburra.Config;
import com.github.kookaburra.Settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns text into an embedding vector, for {@code nearest(field, "text", k)}, OQL {@code EMBED('text')} and Studio.
 * Built in: a local Ollama server ({@code /api/embed}), over the JDK's HTTP client without extra dependencies.
 * Implement it to plug in anything else (another local server, LangChain4j, Spring AI, an in-house service) and pass
 * it with {@code Config.withEmbedder(...)}.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public interface Embedder {

    String EMBEDDER = "kookaburra.embedder";
    String URL = "kookaburra.embedder-url";
    String MODEL = "kookaburra.embedder-model";
    String TIMEOUT = "kookaburra.embedder-timeout";

    /** The embedding of {@code text}. */
    float[] embed(String text);

    /** Embeddings of several texts, in order (one request where the provider supports it). */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        texts.forEach(t -> out.add(embed(t)));
        return out;
    }

    /** Short description, e.g. {@code ollama nomic-embed-text}. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** Ollama at {@code url} (null = {@code http://localhost:11434}) with an embedding model, e.g. {@code nomic-embed-text}. */
    static Embedder ollama(String url, String model) {
        return new OllamaEmbedder(url, model, Duration.ofSeconds(60));
    }

    /**
     * The embedder of the settings ({@code kookaburra.embedder=ollama|none}, {@code kookaburra.embedder-url},
     * {@code -model}, {@code -timeout}), or null for {@code none}. Nothing is contacted until the first embedding.
     */
    static Embedder fromSettings(Map<String, String> settings) {
        String kind = Settings.get(settings, EMBEDDER).strip().toLowerCase(Locale.ROOT);
        if (kind.isEmpty() || kind.equals("none")) {
            return null;
        }
        if (!kind.equals("ollama")) {
            throw new IllegalArgumentException(EMBEDDER + ": expected ollama or none, got '" + kind + "'");
        }
        String model = Settings.get(settings, MODEL).strip();
        if (model.isEmpty()) {
            throw new IllegalArgumentException(MODEL + " is required with " + EMBEDDER + "=" + kind);
        }
        String url = Settings.get(settings, URL).strip();
        Duration timeout = Duration.ofMillis(Config.millis(Settings.get(settings, TIMEOUT), TIMEOUT));
        return new OllamaEmbedder(url.isEmpty() ? null : url, model, timeout);
    }
}
