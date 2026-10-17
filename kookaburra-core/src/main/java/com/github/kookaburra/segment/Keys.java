package com.github.kookaburra.segment;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.mapping.ValueType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Dictionary key encodings (storage-design §4.2). LONG keys preserve the canonical order under
 * {@link Long#compare}; FIXED128 keys are (epoch second UTC, nano); STRING keys are compared with
 * {@link String#compareTo}; DECIMAL keys are variable-length like STRING ({@link #decimalBytes}) and compared
 * by numeric value.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Keys {

    /** Physical dictionary layout. */
    public enum Kind { LONG, FIXED128, STRING, DECIMAL }


    private Keys() {
    }

    public static Kind kind(ValueType t) {
        return switch (t) {
            case STRING -> Kind.STRING;
            case DECIMAL -> Kind.DECIMAL;
            case DATETIME, INSTANT -> Kind.FIXED128;
            default -> Kind.LONG;
        };
    }

    /**
     * Order-preserving long for a canonical value. An enum constant unknown to {@code constants} was added
     * later (constants are append-only), so it sorts after all known ones: {@code Long.MAX_VALUE}.
     */
    public static long toLong(ValueType t, Object c, List<String> constants) {
        return switch (t) {
            case BOOL -> (Boolean) c ? 1 : 0;
            case INT32, INT64, REF -> ((Number) c).longValue();
            case FLOAT64 -> sortable((Double) c);
            case DATE -> ((LocalDate) c).toEpochDay();
            case ENUM -> {
                int i = constants.indexOf(c);
                yield i < 0 ? Long.MAX_VALUE : i;
            }
            default -> throw new IllegalArgumentException("not a LONG key type: " + t);
        };
    }

    public static Object fromLong(ValueType t, long k, List<String> constants) {
        return switch (t) {
            case BOOL -> k != 0;
            case INT32 -> (int) k;
            case INT64, REF -> k;
            case FLOAT64 -> Double.longBitsToDouble(unsortable(k));
            case DATE -> LocalDate.ofEpochDay(k);
            case ENUM -> constants.get((int) k);
            default -> throw new IllegalArgumentException("not a LONG key type: " + t);
        };
    }

    /** {@code [seconds, nanos]} of a DATETIME / INSTANT value. */
    public static long[] toFixed(Object c) {
        if (c instanceof Instant i) {
            return new long[]{i.getEpochSecond(), i.getNano()};
        }
        LocalDateTime d = (LocalDateTime) c;
        return new long[]{d.toEpochSecond(ZoneOffset.UTC), d.getNano()};
    }

    public static Object fromFixed(ValueType t, long seconds, long nanos) {
        return t == ValueType.INSTANT ? Instant.ofEpochSecond(seconds, nanos)
                : LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC);
    }

    /** Dictionary bytes of a canonical decimal: zig-zag varint scale, then the two's-complement unscaled value. */
    public static byte[] decimalBytes(BigDecimal d) {
        return new ByteSink(16).writeZigZag(d.scale()).writeBytes(d.unscaledValue().toByteArray()).toByteArray();
    }

    public static BigDecimal decimal(byte[] b) {
        ByteSource in = new ByteSource(b);
        int scale = (int) in.readZigZag();
        return new BigDecimal(new BigInteger(in.readBytes(b.length - in.position())), scale);
    }

    /** Signed-long encoding of a double whose order equals {@link Double#compare}. */
    public static long sortable(double d) {
        long bits = Double.doubleToLongBits(d);
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    static long unsortable(long k) {
        return k ^ ((k >> 63) & Long.MAX_VALUE);
    }
}
