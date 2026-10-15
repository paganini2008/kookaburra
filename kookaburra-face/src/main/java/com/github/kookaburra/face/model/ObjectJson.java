package com.github.kookaburra.face.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.TypeDef;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DynamicObject} ⇄ editable JSON, using the stored schema: references as OIDs, temporals as ISO
 * strings (UTC), bytes as Base64. OID and version are not part of the JSON.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ObjectJson {

    private ObjectJson() {
    }

    /** Binaries larger than this are shown as a placeholder in the editor's JSON (Base64 would be megabytes of text). */
    public static final int EDITOR_INLINE_MAX = 64 << 10;

    public static String toJson(DynamicObject o, ClassDef def) {
        return Json.write(toMap(o, def));
    }

    /**
     * JSON for the object editor: like {@link #toJson}, but binaries over {@link #EDITOR_INLINE_MAX} become a
     * placeholder text, recorded in {@code kept}; {@link #fromJson(DynamicObject, ClassDef, String, Map)} turns an
     * unchanged placeholder back into the original bytes. Exports always carry the full Base64.
     */
    public static String toEditorJson(DynamicObject o, ClassDef def, Map<String, byte[]> kept) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldDef f : def.current().fields()) {
            m.put(f.name(), editorValue(o.values().get(f.name()), f.name(), kept));
        }
        return Json.write(m);
    }

    private static Object editorValue(Object v, String path, Map<String, byte[]> kept) {
        if (v instanceof byte[] b && b.length > EDITOR_INLINE_MAX) {
            String format = Display.format(b);
            String placeholder = "<" + path + ": " + (format == null ? "binary" : format) + " · " + Display.size(b.length, "bytes")
                    + " — kept unchanged; use Load… / Save… in the Form tab>";
            kept.put(placeholder, b);
            return placeholder;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), editorValue(x, path + "." + k, kept)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                out.add(editorValue(l.get(i), path + "[" + i + "]", kept));
            }
            return out;
        }
        return out(v);
    }

    /** The stored fields of {@code o} as JSON-ready values (refs → OIDs, temporals → ISO text, bytes → Base64). */
    public static Map<String, Object> toMap(DynamicObject o, ClassDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldDef f : def.current().fields()) {
            m.put(f.name(), out(o.values().get(f.name())));
        }
        return m;
    }

    /** One value in its JSON form (refs → OID, temporals → ISO text, bytes → Base64, embedded → nested). */
    public static Object jsonValue(Object v) {
        return out(v);
    }

    /** One value as plain text for CSV: refs → OID, temporals → ISO text, collections / maps → compact JSON. */
    public static String text(Object v) {
        Object x = out(v);
        if (x == null) {
            return null;
        }
        return x instanceof Map<?, ?> || x instanceof List<?> ? Json.compact(x) : String.valueOf(x);
    }

    private static Object out(Object v) {
        if (v instanceof Ref<?> r) {
            return r.oid();
        }
        if (v instanceof Temporal t) {
            return t.toString();
        }
        if (v instanceof byte[] b) {
            return Base64.getEncoder().encodeToString(b);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), out(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(out(x)));
            return out;
        }
        return v;
    }

    /** A copy of {@code o} (same OID / version) whose values are replaced by the JSON object. */
    public static DynamicObject fromJson(DynamicObject o, ClassDef def, String json) {
        return fromJson(o, def, json, Map.of());
    }

    /** {@link #fromJson(DynamicObject, ClassDef, String)} where placeholders in {@code kept} stand for their bytes. */
    public static DynamicObject fromJson(DynamicObject o, ClassDef def, String json, Map<String, byte[]> kept) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("the object must be a JSON object { ... }");
        }
        DynamicObject out = new DynamicObject(o.className());
        out.assign(o.oid(), o.version());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            FieldDef f = def.current().field(name);
            if (f == null) {
                throw new IllegalArgumentException(def.simpleName() + " has no field '" + name + "'");
            }
            if (e.getValue() != null) {
                out.set(name, in(f.type(), e.getValue(), name, kept));
            }
        }
        return out;
    }

    private static Object in(TypeDef t, Object v, String where, Map<String, byte[]> kept) {
        if (v == null) {
            return null;
        }
        switch (t.kind()) {
            case BYTES:
            case OBJECT:
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException(where + ": expected a Base64 string");
                }
                if (kept.containsKey(s)) {
                    return kept.get(s);
                }
                try {
                    return Base64.getDecoder().decode(s);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + ": not valid Base64 (" + e.getMessage() + ")");
                }
            case EMBEDDED: {
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(where + ": expected an object");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    FieldDef f = t.field(String.valueOf(e.getKey()));
                    if (f == null) {
                        throw new IllegalArgumentException(where + " has no field '" + e.getKey() + "'");
                    }
                    out.put(f.name(), in(f.type(), e.getValue(), where + "." + f.name(), kept));
                }
                return out;
            }
            case EMBEDDED_LIST: {
                if (!(v instanceof List<?> l)) {
                    throw new IllegalArgumentException(where + ": expected an array");
                }
                List<Object> out = new ArrayList<>();
                l.forEach(x -> out.add(in(t.element(), x, where, kept)));
                return out;
            }
            case MAP: {
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(where + ": expected an object");
                }
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(e.getKey(), in(t.element(), e.getValue(), where + "." + e.getKey(), kept));
                }
                return out;
            }
            case REF:
                if (v instanceof Number n) {
                    return Ref.of(n.longValue());
                }
                throw new IllegalArgumentException(where + ": expected an OID number");
            default:
                return v;
        }
    }
}
