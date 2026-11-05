package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.FullText;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Vector;

/**
 * Full-text test entity: an English body with an index, the same text without one (rows are split on the fly) and
 * with the multilingual {@code standard} analyzer, a language for filters and a small embedding for hybrid search.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Entity
public class Article {
    @Id long oid;
    @Index String lang;
    @FullText String body;
    String plain;
    @FullText(analyzer = "standard") String raw;
    @Vector(dim = 2) float[] embedding;

    public Article() {
    }

    public Article(String lang, String body, float... embedding) {
        this.lang = lang;
        this.body = body;
        this.plain = body;
        this.raw = body;
        this.embedding = embedding.length == 0 ? null : embedding;
    }

    public long getOid() { return oid; }
    public String getLang() { return lang; }
    public String getBody() { return body; }
    public String getPlain() { return plain; }
    public String getRaw() { return raw; }
    public float[] getEmbedding() { return embedding; }
    public Article body(String v) { body = v; plain = v; raw = v; return this; }
}
