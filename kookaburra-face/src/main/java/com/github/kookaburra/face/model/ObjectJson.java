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

    public static String toJson(DynamicObject o, ClassDef def) {
        return Json.write(toMap(o, def));
    }

    /** The stored fields of {@code o} as JSON-ready values (refs → OIDs, temporals → ISO text, bytes → Base64). */
    public static Map<String, Object> toMap(DynamicObject o, ClassDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldDef f : def.current().fields()) {
            m.put(f.name(), out(o.values().get(f.name())));
        }
        return m;
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
                out.set(name, in(f.type(), e.getValue(), name));
            }
        }
        return out;
    }

    private static Object in(TypeDef t, Object v, String where) {
        if (v == null) {
            return null;
        }
        switch (t.kind()) {
            case BYTES:
            case OBJECT:
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException(where + ": expected a Base64 string");
                }
                return Base64.getDecoder().decode(s);
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
                    out.put(f.name(), in(f.type(), e.getValue(), where + "." + f.name()));
                }
                return out;
            }
            case EMBEDDED_LIST: {
                if (!(v instanceof List<?> l)) {
                    throw new IllegalArgumentException(where + ": expected an array");
                }
                List<Object> out = new ArrayList<>();
                l.forEach(x -> out.add(in(t.element(), x, where)));
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
