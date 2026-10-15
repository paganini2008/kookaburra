package com.github.kookaburra.lob;

import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Large values (LOBs): long Unicode texts and binary images up to 16 MiB per value, through insert, query, update,
 * transactions, flush, compaction and reopen ({@link LobDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class LobTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true).withMemSegmentBytes(4 << 20);
    }

    /** Disk variant: flush, compact, close and reopen. */
    protected void checkpoint() {
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** A text of {@code chars} characters mixing ASCII, CJK, emoji (surrogate pairs) and line breaks. */
    static String text(int chars, long seed) {
        String[] parts = {"Kookaburra ", "笑翠鸟 ", "🐦", "\n", "naïve café ", "\t", "\"quoted\" ", "\\back\\slash "};
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder(chars + 16);
        while (sb.length() < chars) {
            sb.append(parts[r.nextInt(parts.length)]);
        }
        return sb.toString();
    }

    /** A PNG-like binary: PNG signature followed by random (incompressible) bytes, including every byte value. */
    static byte[] image(int bytes, long seed) {
        byte[] b = new byte[bytes];
        new Random(seed).nextBytes(b);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(png, 0, b, 0, Math.min(png.length, bytes));
        return b;
    }

    static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    @Test
    void largeTextAndImagesRoundTrip() {
        String body = text(2_000_000, 1);                        // ~2 M chars, multi-byte UTF-8
        byte[] img = image(5 << 20, 2);                          // 5 MiB
        long oid = db.insert(new Document("big", body, img));
        long small = db.insert(new Document("small", "", new byte[0]));
        long none = db.insert(new Document("none", null, null));
        checkpoint();
        Document d = db.get(Document.class, oid);
        assertThat(d.getBody()).isEqualTo(body);
        assertThat(d.getImage()).hasSize(5 << 20);
        assertThat(crc(d.getImage())).isEqualTo(crc(img));
        assertThat(db.get(Document.class, small).getBody()).isEmpty();
        assertThat(db.get(Document.class, small).getImage()).isEmpty();
        assertThat(db.get(Document.class, none).getBody()).isNull();
        assertThat(db.get(Document.class, none).getImage()).isNull();
    }

    @Test
    void queriesOverLargeValues() {
        for (int i = 0; i < 20; i++) {
            db.insert(new Document("doc" + i, text(100_000 + i, i) + (i % 5 == 0 ? " NEEDLE" : ""), image(200_000, i)));
        }
        checkpoint();
        assertThat(db.query(Document.class).where(Document::getTitle).eq("doc7").list().get(0).getImage()).hasSize(200_000);
        assertThat(db.oql("SELECT COUNT(*) FROM Document d WHERE d.body LIKE '%NEEDLE'").list().get(0)).isEqualTo(4L);
        assertThat(db.oql("SELECT d.title FROM Document d WHERE d.image IS NULL").list()).isEmpty();
        long expected = 0;
        for (int i = 0; i < 20; i++) {
            expected += text(100_000 + i, i).startsWith("Kookaburra") ? 1 : 0;
        }
        assertThat(db.query(Document.class).where(Document::getBody).startsWith("Kookaburra").count()).isEqualTo(expected);
        // projections of a small field do not need the large ones
        assertThat(db.oql("SELECT d.title FROM Document d ORDER BY d.title").list()).hasSize(20);
    }

    @Test
    void maximalValue() {
        byte[] img = image(16 << 20, 3);                         // 16 MiB in one value
        long oid = db.insert(new Document("max", text(1000, 3), img));
        checkpoint();
        assertThat(crc(db.get(Document.class, oid).getImage())).isEqualTo(crc(img));
    }

    @Test
    void updatesShrinkAndGrowAndMapsOfBinaries() throws Exception {
        long oid = db.insert(new Document("u", text(500_000, 4), image(1 << 20, 4)));
        checkpoint();
        Document d = db.get(Document.class, oid);
        db.update(d.body("short").image(image(10, 5)).thumbnails(Map.of("64", image(4_000, 6), "128", compressed(16_000))));
        checkpoint();
        d = db.get(Document.class, oid);
        assertThat(d.getBody()).isEqualTo("short");
        assertThat(d.getImage()).isEqualTo(image(10, 5));
        assertThat(d.getThumbnails().get("64")).isEqualTo(image(4_000, 6));
        assertThat(d.getThumbnails().get("128")).isEqualTo(compressed(16_000));
        db.update(d.body(text(3_000_000, 7)).image(image(3 << 20, 7)));
        checkpoint();
        d = db.get(Document.class, oid);
        assertThat(d.getBody()).isEqualTo(text(3_000_000, 7));
        assertThat(crc(d.getImage())).isEqualTo(crc(image(3 << 20, 7)));
        assertThat(d.getVersion()).isEqualTo(3);
    }

    private static byte[] compressed(int n) {
        Deflater z = new Deflater();
        z.setInput(text(n, 9).getBytes(StandardCharsets.UTF_8));
        z.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!z.finished()) {
            out.write(buf, 0, z.deflate(buf));
        }
        return out.toByteArray();
    }

    @Test
    void transactionsAndDeletes() {
        long keep;
        long drop;
        try (Transaction tx = db.begin()) {
            keep = tx.insert(new Document("keep", text(300_000, 10), image(2 << 20, 10)));
            drop = tx.insert(new Document("drop", text(300_000, 11), image(2 << 20, 11)));
            assertThat(tx.get(Document.class, keep).getImage()).hasSize(2 << 20);
            tx.commit();
        }
        try (Transaction tx = db.begin()) {
            tx.update(tx.get(Document.class, keep).image(image(1 << 20, 12)));
            // rolled back by close()
        }
        checkpoint();
        assertThat(crc(db.get(Document.class, keep).getImage())).isEqualTo(crc(image(2 << 20, 10)));
        assertThat(db.delete(Document.class, drop)).isTrue();
        checkpoint();
        assertThat(db.get(Document.class, drop)).isNull();
        assertThat(db.query(Document.class).count()).isEqualTo(1);
    }

    @Test
    void dynamicAccessSeesBytesAndText() {
        long oid = db.insert(new Document("dyn", text(50_000, 13), image(70_000, 13)));
        checkpoint();
        DynamicObject o = db.dynamic(Document.class.getName()).get(oid);
        assertThat((byte[]) o.get("image")).isEqualTo(image(70_000, 13));
        assertThat(o.get("body")).isEqualTo(text(50_000, 13));
    }
}
