package com.github.kookaburra.segment;

import com.github.kookaburra.Ref;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.ColumnDef;
import com.github.kookaburra.mapping.EntityType;
import com.github.kookaburra.mapping.Kitchen;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeRegistry;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Segment format round trip and column lookups, checked against the records themselves.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class SegmentTest {

    private static final String[] STRINGS = {"apple", "apricot", "banana", "", "Zed", "ápple", "😀", null};

    private TypeRegistry registry;
    private EntityType<Kitchen> type;
    private final List<Record> records = new ArrayList<>();
    private Segment seg;
    private RoaringBitmap all;

    @BeforeEach
    void build() {
        registry = new TypeRegistry(Schema.empty(), null, s -> { });
        type = registry.entity(Kitchen.class);
        Random r = new Random(7);
        List<SegmentWriter.Entry> entries = new ArrayList<>();
        for (int n = 0; n < 500; n++) {
            Kitchen k = new Kitchen();
            k.b = r.nextBoolean();
            k.i = r.nextInt(50) - 25;
            k.ii = r.nextInt(4) == 0 ? null : r.nextInt(1000);
            k.l = r.nextInt(5);
            k.d = new double[]{-0.0, 0.0, Double.NaN, 1.5, -7}[r.nextInt(5)];
            k.s = STRINGS[r.nextInt(STRINGS.length)];
            k.level = r.nextInt(4) == 0 ? null : Level.values()[r.nextInt(3)];
            k.levels = r.nextInt(4) == 0 ? null : new LinkedHashSet<>(List.of(Level.values()[r.nextInt(3)], Level.values()[r.nextInt(3)]));
            k.date = r.nextInt(5) == 0 ? null : LocalDate.of(2000, 1, 1).plusDays(r.nextInt(100));
            k.dateTime = r.nextInt(5) == 0 ? null : LocalDateTime.of(2020, 1, 1, 0, 0).plusSeconds(r.nextInt(5)).plusNanos(r.nextInt(3));
            k.instant = r.nextInt(5) == 0 ? null : Instant.ofEpochSecond(r.nextInt(5) - 2, r.nextInt(3));
            k.ref = r.nextInt(3) == 0 ? null : Ref.of(1 + r.nextInt(10));
            k.address = r.nextInt(3) == 0 ? null : new Address(STRINGS[r.nextInt(STRINGS.length)], "z");
            Record rec = type.toRecord(k);
            records.add(rec);
            entries.add(new SegmentWriter.Entry(1000 + n * 3L, n + 1, rec, RecordCodec.encode(rec)));
        }
        byte[] bytes = SegmentWriter.write(entries, columns(), 0.01);
        seg = new Segment(9, MappedFile.heap(bytes, "test"), registry::schema, new SegmentDeletes(new RoaringBitmap()));
        all = new RoaringBitmap();
        all.add(0L, records.size());
    }

    private Collection<ColumnDef> columns() {
        return registry.schema().columns(Kitchen.class.getName()).values();
    }

    @Test
    void rowsOidsAndVersions() {
        assertThat(seg.size()).isEqualTo(500);
        assertThat(seg.label()).isEqualTo("seg#9");
        assertThat(seg.id()).isEqualTo(9);
        assertThat(seg.oidMin()).isEqualTo(1000);
        assertThat(seg.oidMax()).isEqualTo(1000 + 499 * 3);
        assertThat(seg.fileSize()).isPositive();
        for (int d = 0; d < 500; d += 37) {
            assertThat(seg.record(d)).isEqualTo(records.get(d));
            assertThat(RecordCodec.decode(registry.schema(), seg.row(d))).isEqualTo(records.get(d));
            assertThat(seg.oid(d)).isEqualTo(1000 + d * 3L);
            assertThat(seg.version(d)).isEqualTo(d + 1);
            assertThat(seg.classId(d)).isEqualTo(type.classId());
            assertThat(seg.docOf(1000 + d * 3L)).isEqualTo(d);
            assertThat(seg.find(1000 + d * 3L, 0)).isEqualTo(d);
        }
        assertThat(seg.docOf(1001)).isEqualTo(-1);
        assertThat(seg.docOf(5)).isEqualTo(-1);
        assertThat(seg.docOf(Long.MAX_VALUE)).isEqualTo(-1);
        assertThat(seg.classes(Set.of(type.classId()), all).getCardinality()).isEqualTo(500);
        assertThat(seg.classes(Set.of(12345), all).isEmpty()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void columnValuesMatchRecords() {
        for (ColumnDef c : columns()) {
            for (int d = 0; d < records.size(); d++) {
                Object expected = records.get(d).get(c.path());
                Object actual = seg.value(d, c.path());
                if (c.multi() && expected != null) {
                    assertThat((List<Object>) actual).as(c.path()).containsExactlyInAnyOrderElementsOf(
                            new LinkedHashSet<Object>((List<?>) expected));
                } else {
                    assertThat(actual).as(c.path() + " doc " + d).isEqualTo(expected);
                }
            }
        }
        assertThat(seg.value(3, "bb")).isEqualTo(records.get(3).get("bb"));   // not a column: from the row
        assertThat(seg.columns()).containsKeys("i", "s", "levels", "dateTime", "instant", "ref", "address.city");
        assertThat(seg.columns().get("l").describe()).startsWith("postings");
        assertThat(seg.columns().get("ii").describe()).startsWith("sorted docs");
        assertThat(seg.columns().get("levels").multi()).isTrue();
        assertThat(seg.columns().get("l").cardinality()).isEqualTo(5);
    }

    @Test
    void valueOrderedIteration() {
        for (ColumnDef c : columns()) {
            SegmentColumn col = seg.columns().get(c.path());
            for (boolean desc : new boolean[]{false, true}) {
                List<Integer> docs = new ArrayList<>();
                boolean supported = col.forEachInOrder(desc, docs::add);
                assertThat(supported).as(c.path()).isEqualTo(!c.multi());
                if (!supported) {
                    continue;
                }
                assertThat(docs).as(c.path()).hasSize((int) (records.size() - col.nulls().getLongCardinality()));
                for (int i = 1; i < docs.size(); i++) {
                    int byValue = Integer.compare(col.ordinal(docs.get(i - 1)), col.ordinal(docs.get(i)));
                    assertThat(desc ? -byValue : byValue).as(c.path()).isLessThanOrEqualTo(0);
                    if (byValue == 0) {
                        assertThat(docs.get(i - 1)).isLessThan(docs.get(i));          // ties in doc order
                    }
                }
                List<Integer> first = new ArrayList<>();
                col.forEachInOrder(desc, d -> first.add(d) && first.size() < 3);   // early stop
                assertThat(first).isEqualTo(docs.subList(0, Math.min(3, docs.size())));
            }
            assertThat(col.ordinals()).isEqualTo(c.multi() ? -1 : col.cardinality());
        }
    }

    @Test
    void lookupsAgreeWithScans() {
        for (ColumnDef c : columns()) {
            SegmentColumn col = seg.columns().get(c.path());
            List<Object> keys = new ArrayList<>();
            for (int o = 0; o < col.cardinality(); o++) {
                keys.add(col.key(o));
            }
            check(col.nulls(), d -> records.get(d).get(c.path()) == null, c.path() + " IS NULL");
            for (Object k : keys) {
                check(col.eq(k, all), d -> matches(records.get(d).get(c.path()), k, c.multi()), c.path() + " = " + k);
                if (!c.multi()) {
                    for (Object hi : keys) {
                        RoaringBitmap got = col.range(k, true, hi, false, all);
                        check(got, d -> inRange(c, records.get(d).get(c.path()), k, true, hi, false), c.path() + " [" + k + "," + hi + ")");
                    }
                    check(col.range(k, false, null, false, all), d -> inRange(c, records.get(d).get(c.path()), k, false, null, false),
                            c.path() + " > " + k);
                    check(col.range(null, false, k, true, all), d -> inRange(c, records.get(d).get(c.path()), null, false, k, true),
                            c.path() + " <= " + k);
                } else {
                    assertThat(col.range(k, true, null, false, all)).isNull();
                }
            }
            if (c.scalarType().kind() != ValueType.BOOL) {
                assertThat(col.find(notPresent(c))).as(c.path()).isEqualTo(-1);
            }
        }
        SegmentColumn s = seg.columns().get("s");
        for (String p : new String[]{"ap", "a", "", "b", "zz", "\uD83D"}) {
            check(s.prefix(p, all), d -> records.get(d).get("s") instanceof String v && v.startsWith(p), "prefix " + p);
        }
        assertThat(seg.columns().get("i").prefix("x", all)).isNull();
        assertThat(seg.columns().get("levels").prefix("x", all)).isNull();
    }

    private static Object notPresent(ColumnDef c) {
        return switch (c.scalarType().kind()) {
            case INT32 -> 99999;
            case INT64, REF -> 99999L;
            case FLOAT64 -> 12345.5;
            case DATE -> LocalDate.of(1900, 1, 1);
            case DATETIME -> LocalDateTime.of(1900, 1, 1, 0, 0);
            case INSTANT -> Instant.ofEpochSecond(-999);
            case ENUM -> "ADDED_LATER";
            default -> "nope";
        };
    }

    private static boolean matches(Object v, Object k, boolean multi) {
        return v != null && (multi ? ((List<?>) v).contains(k) : v.equals(k));
    }

    private static boolean inRange(ColumnDef c, Object v, Object lo, boolean loInc, Object hi, boolean hiInc) {
        return new Cond.Range(c.path(), c.scalarType(), lo, loInc, hi, hiInc).test(v);
    }

    private void check(RoaringBitmap got, Predicate<Integer> expected, String what) {
        RoaringBitmap exp = new RoaringBitmap();
        for (int d = 0; d < records.size(); d++) {
            if (expected.test(d)) {
                exp.add(d);
            }
        }
        assertThat(RoaringBitmap.and(got, all)).as(what).isEqualTo(exp);
    }

    @Test
    void enumRangeWithLaterConstant() {
        SegmentColumn level = seg.columns().get("level");
        long nonNull = records.stream().filter(r -> r.get("level") != null).count();
        assertThat(level.range(null, false, "ADDED_LATER", false, all).getCardinality()).isEqualTo(nonNull);
        assertThat(level.range("ADDED_LATER", true, null, false, all).isEmpty()).isTrue();
        assertThat(level.eq("ADDED_LATER", all).isEmpty()).isTrue();
    }

    @Test
    void deletesAreSnapshotted() {
        SegmentDeletes del = seg.deletes();
        del.add(10, 3);
        del.add(20, 4);
        del.add(20, 5);
        assertThat(seg.live(5).getCardinality()).isEqualTo(500);
        assertThat(seg.live(10).contains(3)).isFalse();
        assertThat(seg.live(25).getCardinality()).isEqualTo(497);
        assertThat(seg.live(15).getCardinality()).isEqualTo(499);    // older than the cached prefix
        assertThat(seg.find(seg.oid(4), 25)).isEqualTo(SegmentView.DELETED);
        assertThat(seg.find(seg.oid(4), 15)).isEqualTo(4);
        assertThat(del.isDeleted(5)).isTrue();
        assertThat(del.logged()).isEqualTo(3);
        assertThat(del.entries(15)).hasDimensions(1, 2);
        assertThat(del.base().isEmpty()).isTrue();
        for (int i = 0; i < 20; i++) {
            del.add(30 + i, 100 + i);
        }
        assertThat(seg.live(100).getCardinality()).isEqualTo(477);
        RoaringBitmap base = RoaringBitmap.bitmapOf(1, 2);
        SegmentDeletes withBase = new SegmentDeletes(base);
        assertThat(withBase.isDeleted(1)).isTrue();
        assertThat(withBase.deleted(0)).isEqualTo(base);
    }

    @Test
    void emptySegmentAndValidation() {
        byte[] empty = SegmentWriter.write(List.of(), columns(), 0.01);
        Segment e = new Segment(1, MappedFile.heap(empty, "e"), registry::schema, new SegmentDeletes(new RoaringBitmap()));
        assertThat(e.size()).isZero();
        assertThat(e.live(10).isEmpty()).isTrue();
        assertThat(e.docOf(1)).isEqualTo(-1);
        assertThat(e.find(1, 1)).isEqualTo(-1);

        Record r = records.get(0);
        assertThatThrownBy(() -> SegmentWriter.write(List.of(new SegmentWriter.Entry(5, 1, r, RecordCodec.encode(r)),
                new SegmentWriter.Entry(5, 1, r, RecordCodec.encode(r))), columns(), 0.01)).hasMessageContaining("increasing");

        byte[] bytes = SegmentWriter.write(List.of(new SegmentWriter.Entry(5, 1, r, RecordCodec.encode(r))), columns(), 0.01);
        byte[] badFooter = bytes.clone();
        badFooter[bytes.length - 25] ^= 1;
        assertThatThrownBy(() -> new Segment(1, MappedFile.heap(badFooter, "b"), registry::schema, new SegmentDeletes(new RoaringBitmap())))
                .hasMessageContaining("checksum");
        byte[] notSeg = new byte[64];
        assertThatThrownBy(() -> new Segment(1, MappedFile.heap(notSeg, "n"), registry::schema, new SegmentDeletes(new RoaringBitmap())))
                .hasMessageContaining("not a segment");
    }

    @Test
    void mappedFilesFromDisk(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("seg_1.seg");
        Record r = records.get(1);
        Files.write(p, SegmentWriter.write(List.of(new SegmentWriter.Entry(5, 1, r, RecordCodec.encode(r))), columns(), 0.01));
        MappedFile mf = MappedFile.map(p);
        assertThat(mf.isMapped()).isTrue();
        assertThat(mf.name()).isEqualTo("seg_1.seg");
        Segment s = new Segment(1, mf, registry::schema, new SegmentDeletes(new RoaringBitmap()));
        assertThat(s.record(0)).isEqualTo(r);
        assertThat(mf.getByte(0)).isEqualTo((byte) 'K');
        s.close();
        assertThat(mf.isClosed()).isTrue();
        mf.close();
        assertThatThrownBy(() -> MappedFile.map(dir.resolve("missing"))).isInstanceOf(UncheckedIOException.class);
        assertThat(MappedFile.heap(new byte[]{1}, "h").isMapped()).isFalse();
    }

    @Test
    void bloomPackedAndKeys() {
        long[] keys = new long[1000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = i * 7L + 1;
        }
        long bits = Bloom.bits(keys.length, 0.01);
        int k = Bloom.hashes(keys.length, bits);
        long[] words = Bloom.build(keys, bits, k);
        ByteSink out = new ByteSink();
        for (long w : words) {
            out.writeLong(w);
        }
        MappedFile f = MappedFile.heap(out.toByteArray(), "bloom");
        for (long key : keys) {
            assertThat(Bloom.mightContain(f, 0, bits, k, key)).isTrue();
        }
        int fp = 0;
        for (long key = 1_000_000; key < 1_010_000; key++) {
            fp += Bloom.mightContain(f, 0, bits, k, key) ? 1 : 0;
        }
        assertThat(fp).isLessThan(300);
        assertThat(Bloom.bits(0, 0.01)).isEqualTo(64);

        int[] values = {0, 1, 5, 31, 17, 0, 31};
        ByteSink p = new ByteSink();
        PackedInts.write(p, values, PackedInts.bitsFor(31));
        MappedFile pf = MappedFile.heap(p.toByteArray(), "p");
        for (int i = 0; i < values.length; i++) {
            assertThat(PackedInts.get(pf, 0, 5, i)).isEqualTo(values[i]);
        }
        assertThat(PackedInts.bitsFor(0)).isEqualTo(1);

        double[] ds = {Double.NEGATIVE_INFINITY, -2, -0.0, 0.0, 1e-300, 3, Double.POSITIVE_INFINITY, Double.NaN};
        for (int i = 1; i < ds.length; i++) {
            assertThat(Keys.sortable(ds[i - 1])).isLessThan(Keys.sortable(ds[i]));
            assertThat(Keys.unsortable(Keys.sortable(ds[i]))).isEqualTo(Double.doubleToLongBits(ds[i]));
        }
        assertThat(Keys.kind(ValueType.STRING)).isEqualTo(Keys.Kind.STRING);
        assertThatThrownBy(() -> Keys.toLong(ValueType.STRING, "x", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.fromLong(ValueType.STRING, 1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(SegmentWriter.columnar(ValueType.MULTI)).isFalse();
        assertThat(Keys.fromLong(ValueType.BOOL, 1, List.of())).isEqualTo(true);
    }
}
