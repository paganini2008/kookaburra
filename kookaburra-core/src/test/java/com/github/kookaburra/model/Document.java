package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Version;

import java.util.Map;

/**
 * Entity with large values: a long text (CLOB-like) and binary content (BLOB-like images).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Entity
public class Document {
    @Id long oid;
    @Version long version;
    @Index String title;
    String body;
    byte[] image;
    Map<String, byte[]> thumbnails;

    public Document() {
    }

    public Document(String title, String body, byte[] image) {
        this.title = title;
        this.body = body;
        this.image = image;
    }

    public long getOid() { return oid; }
    public long getVersion() { return version; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public byte[] getImage() { return image; }
    public Map<String, byte[]> getThumbnails() { return thumbnails; }

    public Document body(String v) { body = v; return this; }
    public Document image(byte[] v) { image = v; return this; }
    public Document thumbnails(Map<String, byte[]> v) { thumbnails = v; return this; }
}
