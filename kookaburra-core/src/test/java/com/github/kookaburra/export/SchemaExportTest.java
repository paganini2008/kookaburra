package com.github.kookaburra.export;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Comment;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.FullText;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Vector;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.annotation.VectorQuantization;
import com.github.kookaburra.annotation.Version;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaExport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code exportSchema}: the generated Java code compiles, and opening the database with the compiled classes reads
 * every stored object without adding a schema version (same fields, indexes, constraints and comments); with a target
 * package the classes move there and the stored classes are renamed, data kept. Also the text of the annotations,
 * nested classes and holders, warnings, an empty schema and writing the files.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class SchemaExportTest {

    @TempDir
    Path dir;

    public enum Tier { BRONZE, SILVER, GOLD }

    @Embeddable
    public static class Geo {
        double lat;
        double lon;
    }

    @Embeddable
    public static class Place {
        @Index @Comment("city or town") String city;
        String street;
        Geo geo;
    }

    @Embeddable
    public static class Line {
        String sku;
        int qty;
    }

    @Entity
    @Comment("A shop customer")
    @Unique(fields = {"email", "place.city"}, name = "email_city")
    public static class Shopper {
        @Id long oid;
        @Version long ver;
        @Index String name;
        @Unique String code;
        String email;
        int age;
        Integer score;
        boolean vip;
        BigDecimal balance;
        LocalDate born;
        LocalDateTime seen;
        Instant at;
        @Index(IndexKind.BITMAP) Tier tier;
        @Index List<String> tags;
        Set<Tier> tiers;
        Place place;
        List<Line> lines;
        Map<String, Place> addresses;
        Map<String, Integer> counts;
        byte[] photo;
        @Vector(dim = 3, distance = VectorDistance.L2, quantization = VectorQuantization.INT8) float[] emb;
        float[] free;
        UUID uuid;
        @FullText(analyzer = "standard") @Comment("says \"hi\"\nsecond line */") String bio;

        public Shopper() {
        }
    }

    @Entity
    public static class VipShopper extends Shopper {
        @FullText String note;

        public VipShopper() {
        }
    }

    @Entity
    public static class Order {
        @Id long oid;
        @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Shopper> buyer;
        @ForeignKey List<Ref<Shopper>> watchers;
        Ref<Shopper> plain;
        Blob blob;

        public Order() {
        }
    }

    private static Shopper shopper(String name, int i) {
        Shopper s = new Shopper();
        s.name = name;
        s.code = "C" + i;
        s.email = name.toLowerCase() + "@x.org";
        s.age = 20 + i;
        s.score = i % 2 == 0 ? null : i;
        s.vip = i % 2 == 0;
        s.balance = new BigDecimal("12.50").add(BigDecimal.valueOf(i));
        s.born = LocalDate.of(1990, 1, 1 + i);
        s.seen = LocalDateTime.of(2026, 11, 5, 10, i);
        s.at = Instant.ofEpochSecond(1_700_000_000L + i);
        s.tier = Tier.values()[i % 3];
        s.tags = List.of("t" + i, "all");
        s.tiers = Set.of(Tier.GOLD);
        s.place = new Place();
        s.place.city = i % 2 == 0 ? "Perth" : "Sydney";
        s.place.street = i + " Hay St";
        s.place.geo = new Geo();
        s.place.geo.lat = -31.9;
        s.lines = List.of(new Line());
        s.addresses = Map.of("work", s.place);
        s.counts = Map.of("a", i);
        s.photo = new byte[]{1, 2, (byte) i};
        s.emb = new float[]{i, 1, 0};
        s.free = new float[]{1, 2};
        s.uuid = new UUID(i, i);
        s.bio = "loves baking bread " + i;
        return s;
    }

    private Path writeDb() {
        Path db = dir.resolve("db");
        try (ObjectDB d = ObjectDB.open(db, Config.defaults())) {
            long ann = d.insert(shopper("Ann", 1));
            d.insert(shopper("Bob", 2));
            d.flush();
            VipShopper v = new VipShopper();
            Shopper base = shopper("Cid", 3);
            v.name = base.name;
            v.code = base.code;
            v.email = base.email;
            v.place = base.place;
            v.note = "gold member";
            long cid = d.insert(v);
            Order o = new Order();
            o.buyer = Ref.of(ann);
            o.watchers = List.of(Ref.of(cid));
            o.plain = Ref.of(cid);
            o.blob = new Blob();
            d.insert(o);
        }
        return db;
    }

    private static String source(SchemaExport e, String endsWith) {
        return e.files().stream().filter(f -> f.path().endsWith(endsWith)).findFirst().orElseThrow().source();
    }

    @Test
    void text() {
        try (ObjectDB d = ObjectDB.open(writeDb(), Config.defaults())) {
            SchemaExport e = d.exportSchema((String) null);
            assertThat(e.files()).extracting(SchemaExport.SourceFile::path)
                    .containsExactly("com/github/kookaburra/export/SchemaExportTest.java");
            String s = source(e, "SchemaExportTest.java");
            assertThat(s).startsWith("package com.github.kookaburra.export;")
                    .contains("public final class SchemaExportTest {", "private SchemaExportTest() {")
                    .contains("public enum Tier {", "BRONZE, SILVER, GOLD")
                    .contains("@Entity\n    @Comment(\"A shop customer\")\n    @Unique(fields = {\"email\", \"place.city\"}, name = \"email_city\")")
                    .contains("public static class VipShopper extends Shopper {")
                    .contains("@Id\n        private long oid;", "@Version\n        private long ver;")
                    .contains("@Index(IndexKind.BITMAP)\n        private Tier tier;")
                    .contains("@Unique\n        private String code;")
                    .contains("@Index\n        private List<String> tags;", "private Set<Tier> tiers;")
                    .contains("private Map<String, Place> addresses;", "private List<Line> lines;")
                    .contains("@Vector(dim = 3, distance = VectorDistance.L2, quantization = VectorQuantization.INT8)")
                    .contains("private float[] free;", "private byte[] photo;", "private UUID uuid;")
                    .contains("@FullText(analyzer = \"standard\")", "@Comment(\"says \\\"hi\\\"\\nsecond line */\")")
                    .contains(" * says \"hi\"\n", " * second line *&#47;")
                    .contains("@FullText\n        private String note;")
                    .contains("@ForeignKey(onDelete = OnDelete.CASCADE)\n        private Ref<Shopper> buyer;")
                    .contains("@ForeignKey\n        private List<Ref<Shopper>> watchers;")
                    .contains("private Integer age;", "private Boolean vip;", "private BigDecimal balance;")
                    .contains("public Integer getAge() {", "public void setAge(Integer age) {")
                    .contains("import java.time.LocalDateTime;", "import com.github.kookaburra.Ref;")
                    .contains("private Blob blob;");
            assertThat(s.substring(s.indexOf("class VipShopper"))).doesNotContain("@Id");      // inherited
            assertThat(e.warnings()).singleElement().asString().contains("com.github.kookaburra.export.Blob");
            assertThat(e.warnings().get(0)).contains("put that class on the classpath");
        }
    }

    @Test
    void compilesAndReopensWithoutNewSchemaVersion() throws Exception {
        Path db = writeDb();
        Schema before;
        SchemaExport e;
        try (ObjectDB d = ObjectDB.open(db, Config.defaults())) {
            before = d.schema();
            e = d.exportSchema((String) null);
        }
        Path classes = compile(e);
        String prefix = SchemaExportTest.class.getName();
        try (ChildFirst loader = new ChildFirst(classes, n -> n.equals(prefix) || n.startsWith(prefix + "$"));
             ObjectDB d = ObjectDB.open(db, Config.defaults().withClassLoader(loader))) {
            Class<?> shopper = loader.loadClass(prefix + "$Shopper");
            Class<?> vip = loader.loadClass(prefix + "$VipShopper");
            Class<?> order = loader.loadClass(prefix + "$Order");
            assertThat(shopper).isNotSameAs(Shopper.class);
            List<?> all = d.from(shopper).list();
            assertThat(all).hasSize(3);
            assertThat(all).extracting(o -> get(o, "getName")).containsExactlyInAnyOrder("Ann", "Bob", "Cid");
            Object cid = all.stream().filter(o -> "Cid".equals(get(o, "getName"))).findFirst().orElseThrow();
            assertThat(cid.getClass()).isSameAs(vip);
            assertThat(get(cid, "getNote")).isEqualTo("gold member");
            Object ann = all.stream().filter(o -> "Ann".equals(get(o, "getName"))).findFirst().orElseThrow();
            assertThat(get(ann, "getAge")).isEqualTo(21);
            assertThat(get(ann, "getBalance")).isEqualTo(new BigDecimal("13.5"));
            assertThat(get(get(ann, "getPlace"), "getCity")).isEqualTo("Sydney");
            assertThat(get(ann, "getTier").toString()).isEqualTo("SILVER");
            assertThat((float[]) get(ann, "getEmb")).containsExactly(1, 1, 0);
            assertThat(get(ann, "getUuid")).isEqualTo(new UUID(1, 1));
            assertThat(d.from(order).count()).isEqualTo(1);
            assertThat(d.oql("SELECT s.name FROM Shopper s WHERE MATCH(s.bio, 'bread') AND s.place.city = 'Perth'").list())
                    .containsExactly("Bob");

            // writing through the generated classes keeps the constraints
            Object dup = shopper.getConstructor().newInstance();
            shopper.getMethod("setName", String.class).invoke(dup, "Dup");
            shopper.getMethod("setCode", String.class).invoke(dup, "C1");
            assertThatThrownBy(() -> d.insert(dup)).hasMessageContaining("code");

            Schema after = d.schema();
            for (ClassDef b : before.classes()) {
                ClassDef a = after.byName(b.name());
                assertThat(a.layouts()).as(b.name()).hasSize(b.layouts().size());
                assertThat(a.current().fields()).as(b.name()).isEqualTo(b.current().fields());
                assertThat(a.uniques()).isEqualTo(b.uniques());
                assertThat(a.comment()).isEqualTo(b.comment());
                assertThat(a.versionField()).isEqualTo(b.versionField());
            }
        }
    }

    @Test
    void targetPackageRenamesTheClasses() throws Exception {
        Path db = writeDb();
        SchemaExport e;
        try (ObjectDB d = ObjectDB.open(db, Config.defaults())) {
            e = d.exportSchema("com.acme.shop");
        }
        assertThat(e.files()).extracting(SchemaExport.SourceFile::path).containsExactly("com/acme/shop/SchemaExportTest.java");
        assertThat(e.warnings().get(0)).contains("Classes move to package com.acme.shop");
        assertThat(source(e, ".java")).contains("@Renamed(\"com.github.kookaburra.export.SchemaExportTest$Shopper\")");
        Path classes = compile(e);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
             ObjectDB d = ObjectDB.open(db, Config.defaults().withClassLoader(loader))) {
            Class<?> shopper = loader.loadClass("com.acme.shop.SchemaExportTest$Shopper");
            Class<?> order = loader.loadClass("com.acme.shop.SchemaExportTest$Order");
            assertThat(d.from(shopper).count()).isEqualTo(3);
            Object o = d.from(order).first().orElseThrow();
            Ref<?> buyer = (Ref<?>) get(o, "getBuyer");
            assertThat(get(d.get(shopper, buyer.oid()), "getName")).isEqualTo("Ann");
            assertThat(d.schema().byName("com.acme.shop.SchemaExportTest$Shopper").formerNames())
                    .contains(Shopper.class.getName());
        }
    }

    @Test
    void emptyAndWritten() throws IOException {
        try (ObjectDB d = ObjectDB.inMemory(Config.defaults())) {
            assertThat(d.exportSchema("x.y").files()).isEmpty();
            assertThat(d.exportSchema("x.y").warnings()).isEmpty();
            d.insert(new Line());                                          // not an entity: refused, schema stays empty
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessageContaining("@Entity");
        }
        try (ObjectDB d = ObjectDB.inMemory(Config.defaults())) {
            Order o = new Order();
            d.insert(o);
            List<Path> written = d.exportSchema(dir.resolve("src"), "");
            assertThat(written).hasSize(1);
            assertThat(Files.readString(written.get(0))).contains("class Order");
            assertThat(written.get(0)).isEqualTo(dir.resolve("src/com/github/kookaburra/export/SchemaExportTest.java"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Path compile(SchemaExport e) throws IOException {
        Path src = dir.resolve("gen-src-" + System.nanoTime());
        Path out = dir.resolve("gen-classes-" + System.nanoTime());
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", System.getProperty("java.class.path"),
                "-proc:none", "-Xlint:none"));
        e.writeTo(src).forEach(p -> args.add(p.toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, args.toArray(String[]::new));
        assertThat(rc).as(err.toString() + "\n" + e.files().get(0).source()).isZero();
        try (Stream<Path> s = Files.walk(out)) {
            assertThat(s.filter(p -> p.toString().endsWith(".class")).count()).isPositive();
        }
        return out;
    }

    private static Object get(Object o, String getter) {
        try {
            Method m = o.getClass().getMethod(getter);
            return m.invoke(o);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Loads the generated classes itself (they share their names with the classes of this test). */
    private static final class ChildFirst extends URLClassLoader {
        private final Predicate<String> own;

        ChildFirst(Path classes, Predicate<String> own) throws IOException {
            super(new URL[]{classes.toUri().toURL()}, SchemaExportTest.class.getClassLoader());
            this.own = own;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!own.test(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                return c != null ? c : findClass(name);
            }
        }
    }
}
