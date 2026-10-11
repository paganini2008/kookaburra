package com.github.kookaburra.face.model;

import com.github.kookaburra.dynamic.DynamicObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the objects of a Data-tab query (class, filter, sort; all pages) to a file.
 * JSON / JSONL use the editor's JSON form plus {@code $oid} / {@code $version} / {@code $type};
 * CSV uses the grid columns (refs as OIDs, collections as compact JSON; RFC 4180 quoting, UTF-8).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Export {

    /** Objects fetched per query while exporting. */
    static final int CHUNK = 5_000;

    /** Output formats. */
    public enum Format {
        JSON("json"), JSONL("jsonl"), CSV("csv");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    private Export() {
    }

    /** Exports every object matching the query; returns the number written. */
    public static long write(Session s, String className, String where, String orderBy, boolean descending,
                             Format format, Path file) {
        List<Column> columns = s.columns(className);
        long n = 0;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create directory for " + file + ": " + e.getMessage(), e);
        }
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (format == Format.JSON) {
                w.write('[');
            } else if (format == Format.CSV) {
                for (int i = 0; i < columns.size(); i++) {
                    w.write((i > 0 ? "," : "") + csv(columns.get(i).path()));
                }
                w.write("\r\n");
            }
            for (int page = 0; ; page++) {
                List<DynamicObject> rows = s.page(className, where, orderBy, descending, page, CHUNK).rows();
                for (DynamicObject o : rows) {
                    switch (format) {
                        case JSON -> w.write((n > 0 ? ",\n" : "\n") + Json.compact(json(s, o)));
                        case JSONL -> w.write(Json.compact(json(s, o)) + "\n");
                        case CSV -> {
                            for (int i = 0; i < columns.size(); i++) {
                                w.write((i > 0 ? "," : "") + csv(ObjectJson.text(s.cell(o, columns.get(i)))));
                            }
                            w.write("\r\n");
                        }
                    }
                    n++;
                }
                if (rows.size() < CHUNK) {
                    break;
                }
            }
            if (format == Format.JSON) {
                w.write(n > 0 ? "\n]\n" : "]\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    static Map<String, Object> json(Session s, DynamicObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$oid", o.oid());
        m.put("$version", o.version());
        m.put("$type", simple(o.className()));
        m.putAll(ObjectJson.toMap(o, s.classDef(o.className())));
        return m;
    }

    static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
