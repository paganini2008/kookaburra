package com.github.kookaburra.mapping;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row encoding (design §5.4):
 * {@code varint classId · varint layoutId · nullBits · value*}; embedded values nest the same way.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class RecordCodec {

    private RecordCodec() {
    }

    public static byte[] encode(Record r) {
        ByteSink out = new ByteSink(128);
        encode(r, out);
        return out.toByteArray();
    }

    public static void encode(Record r, ByteSink out) {
        out.writeVarInt(r.classId());
        out.writeVarInt(r.layout().id());
        writeFields(out, r.values(), r.layout().fields());
    }

    public static Record decode(Schema schema, byte[] bytes) {
        return decode(schema, new ByteSource(bytes));
    }

    public static Record decode(Schema schema, ByteSource in) {
        int classId = in.readVarInt();
        Layout layout = schema.byId(classId).layout(in.readVarInt());
        return new Record(classId, layout, readFields(in, layout.fields()));
    }

    /**
     * The value at {@code path} (dot-separated through embedded values) without decoding the other fields:
     * preceding fields are skipped in place, nothing is allocated for them. Same result as
     * {@code decode(schema, in).get(path)}.
     */
    public static Object readPath(Schema schema, ByteSource in, String path) {
        int classId = in.readVarInt();
        Layout layout = schema.byId(classId).layout(in.readVarInt());
        List<FieldDef> fields = layout.fields();
        String rest = path;
        boolean top = true;
        while (true) {
            int dot = rest.indexOf('.');
            String head = dot < 0 ? rest : rest.substring(0, dot);
            int i = top ? layout.indexOf(head) : indexOf(fields, head);
            if (i < 0 || !seek(in, fields, i)) {
                return null;
            }
            TypeDef t = fields.get(i).type();
            if (dot < 0) {
                return readValue(in, t);
            }
            if (t.kind() == ValueType.EMBEDDED_LIST) {
                return Record.elementValues((List<?>) readValue(in, t), t.element(), rest.substring(dot + 1));
            }
            if (t.kind() != ValueType.EMBEDDED) {
                return null;
            }
            fields = t.fields();
            rest = rest.substring(dot + 1);
            top = false;
        }
    }

    private static int indexOf(List<FieldDef> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Positions {@code in} at field {@code target} of a field list; false when that field is null. */
    private static boolean seek(ByteSource in, List<FieldDef> fields, int target) {
        int nulls = in.position();
        in.skip((fields.size() + 7) >>> 3);
        for (int i = 0; i < target; i++) {
            if (!isNull(in, nulls, i)) {
                skipValue(in, fields.get(i).type());
            }
        }
        return !isNull(in, nulls, target);
    }

    private static boolean isNull(ByteSource in, int nulls, int i) {
        return (in.byteAt(nulls + (i >>> 3)) & (1 << (i & 7))) != 0;
    }

    private static void skipFields(ByteSource in, List<FieldDef> fields) {
        int nulls = in.position();
        in.skip((fields.size() + 7) >>> 3);
        for (int i = 0; i < fields.size(); i++) {
            if (!isNull(in, nulls, i)) {
                skipValue(in, fields.get(i).type());
            }
        }
    }

    private static void skipValue(ByteSource in, TypeDef t) {
        switch (t.kind()) {
            case BOOL -> in.skip(1);
            case FLOAT64 -> in.skip(8);
            case INT32, INT64, ENUM, DATE, REF -> in.readVarLong();
            case DATETIME, INSTANT -> {
                in.readVarLong();
                in.readVarLong();
            }
            case STRING, BYTES, OBJECT -> in.skip(in.readVarInt());
            case DECIMAL -> {
                in.readZigZag();
                in.skip(in.readVarInt());
            }
            case MULTI -> {
                int n = in.readVarInt();
                for (int i = 0; i < n; i++) {
                    skipValue(in, t.element());
                }
            }
            case EMBEDDED -> skipFields(in, t.fields());
            case EMBEDDED_LIST -> {
                int n = in.readVarInt();
                for (int i = 0; i < n; i++) {
                    skipFields(in, t.element().fields());
                }
            }
            case MAP -> {
                int n = in.readVarInt();
                for (int i = 0; i < n; i++) {
                    skipValue(in, t.key());
                    if (in.readByte() != 0) {
                        skipValue(in, t.element());
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- write

    private static void writeFields(ByteSink out, Object[] values, List<FieldDef> fields) {
        int n = fields.size();
        byte[] nulls = new byte[(n + 7) >>> 3];
        for (int i = 0; i < n; i++) {
            if (values[i] == null) {
                nulls[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        out.writeBytes(nulls);
        for (int i = 0; i < n; i++) {
            if (values[i] != null) {
                writeValue(out, values[i], fields.get(i).type());
            }
        }
    }

    private static void writeValue(ByteSink out, Object v, TypeDef t) {
        switch (t.kind()) {
            case BOOL -> out.writeByte((Boolean) v ? 1 : 0);
            case INT32 -> out.writeZigZag((Integer) v);
            case INT64 -> out.writeZigZag((Long) v);
            case FLOAT64 -> out.writeDouble((Double) v);
            case STRING -> out.writeString((String) v);
            case DECIMAL -> {
                BigDecimal d = (BigDecimal) v;
                out.writeZigZag(d.scale());
                out.writeBlob(d.unscaledValue().toByteArray());
            }
            case ENUM -> {
                int idx = t.constantIndex((String) v);
                if (idx < 0) {
                    throw new IllegalArgumentException("unknown enum constant " + v + " for " + t);
                }
                out.writeVarInt(idx);
            }
            case DATE -> out.writeZigZag(((LocalDate) v).toEpochDay());
            case DATETIME -> {
                LocalDateTime d = (LocalDateTime) v;
                out.writeZigZag(d.toEpochSecond(ZoneOffset.UTC));
                out.writeVarInt(d.getNano());
            }
            case INSTANT -> {
                Instant i = (Instant) v;
                out.writeZigZag(i.getEpochSecond());
                out.writeVarInt(i.getNano());
            }
            case REF -> out.writeVarLong((Long) v);
            case MULTI -> {
                List<?> list = (List<?>) v;
                out.writeVarInt(list.size());
                for (Object e : list) {
                    writeValue(out, e, t.element());
                }
            }
            case EMBEDDED -> writeFields(out, (Object[]) v, t.fields());
            case EMBEDDED_LIST -> {
                List<?> list = (List<?>) v;
                out.writeVarInt(list.size());
                for (Object e : list) {
                    writeFields(out, (Object[]) e, t.element().fields());
                }
            }
            case MAP -> {
                Map<?, ?> map = (Map<?, ?>) v;
                out.writeVarInt(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    writeValue(out, e.getKey(), t.key());
                    if (e.getValue() == null) {
                        out.writeByte(0);
                    } else {
                        out.writeByte(1);
                        writeValue(out, e.getValue(), t.element());
                    }
                }
            }
            case BYTES, OBJECT -> out.writeBlob((byte[]) v);
        }
    }

    // ---------------------------------------------------------------- read

    private static Object[] readFields(ByteSource in, List<FieldDef> fields) {
        int n = fields.size();
        byte[] nulls = in.readBytes((n + 7) >>> 3);
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            if ((nulls[i >>> 3] & (1 << (i & 7))) == 0) {
                values[i] = readValue(in, fields.get(i).type());
            }
        }
        return values;
    }

    private static Object readValue(ByteSource in, TypeDef t) {
        return switch (t.kind()) {
            case BOOL -> in.readByte() != 0;
            case INT32 -> (int) in.readZigZag();
            case INT64 -> in.readZigZag();
            case FLOAT64 -> in.readDouble();
            case STRING -> in.readString();
            case DECIMAL -> {
                int scale = (int) in.readZigZag();
                yield new BigDecimal(new BigInteger(in.readBlob()), scale);
            }
            case ENUM -> t.constants().get(in.readVarInt());
            case DATE -> LocalDate.ofEpochDay(in.readZigZag());
            case DATETIME -> LocalDateTime.ofEpochSecond(in.readZigZag(), in.readVarInt(), ZoneOffset.UTC);
            case INSTANT -> Instant.ofEpochSecond(in.readZigZag(), in.readVarInt());
            case REF -> in.readVarLong();
            case MULTI -> {
                int n = in.readVarInt();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(readValue(in, t.element()));
                }
                yield list;
            }
            case EMBEDDED -> readFields(in, t.fields());
            case EMBEDDED_LIST -> {
                int n = in.readVarInt();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(readFields(in, t.element().fields()));
                }
                yield list;
            }
            case MAP -> {
                int n = in.readVarInt();
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Object k = readValue(in, t.key());
                    map.put(k, in.readByte() == 0 ? null : readValue(in, t.element()));
                }
                yield map;
            }
            case BYTES, OBJECT -> in.readBlob();
        };
    }
}
