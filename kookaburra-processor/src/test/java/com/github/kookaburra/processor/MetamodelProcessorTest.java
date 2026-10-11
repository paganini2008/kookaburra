package com.github.kookaburra.processor;

import com.github.kookaburra.field.Fields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class MetamodelProcessorTest {

    @TempDir
    Path tmp;

    private static final Map<String, String> SOURCES = Map.of(
            "demo/Address.java", """
                    package demo;
                    import com.github.kookaburra.annotation.*;
                    @Embeddable public class Address {
                        String city; Geo geo;
                    }
                    """,
            "demo/Geo.java", """
                    package demo;
                    @com.github.kookaburra.annotation.Embeddable public class Geo { double lat; double lon; }
                    """,
            "demo/Level.java", "package demo; public enum Level { LOW, HIGH }",
            "demo/Person.java", """
                    package demo;
                    import com.github.kookaburra.Ref;
                    import com.github.kookaburra.annotation.*;
                    import java.util.*;
                    @Entity public class Person {
                        @Id long oid;
                        int age; Integer score; short s; boolean active; double w; long balance;
                        String name; Level level; java.time.LocalDate birthday;
                        Address home; Ref<Person> boss; List<String> tags; Set<Level> levels;
                        Map<String, String> attrs; List<Address> addresses;
                        @Transient String cache; transient String scratch; static int counter;
                    }
                    """,
            "demo/Animal.java", """
                    package demo;
                    @com.github.kookaburra.annotation.Entity public class Animal { String name; int age; }
                    """,
            "demo/Dog.java", """
                    package demo;
                    @com.github.kookaburra.annotation.Entity public class Dog extends Animal { String breed; }
                    """,
            "demo/Outer.java", """
                    package demo;
                    public class Outer { @com.github.kookaburra.annotation.Entity public static class Inner { int x; } }
                    """);

    @Test
    void generatedConstantsAreTheInternedFields() throws Exception {
        ClassLoader loader = compile(SOURCES);
        Class<?> person = loader.loadClass("demo.Person");
        Class<?> meta = loader.loadClass("demo.Person_");

        for (String f : List.of("oid", "age", "score", "s", "active", "w", "balance", "name", "level",
                "birthday", "boss", "tags", "levels", "attrs", "addresses")) {
            assertThat(meta.getField(f).get(null)).as(f).isSameAs(Fields.of(person, f));
        }
        assertThat(meta.getField("score").get(null).getClass().getSimpleName()).isEqualTo("IntField");
        assertThat(meta.getField("levels").get(null).getClass().getSimpleName()).isEqualTo("ListField");
        assertThat(meta.getField("addresses").get(null).getClass().getSimpleName()).isEqualTo("ObjectField");
        assertThat(meta.getFields()).extracting(Field::getName)
                .doesNotContain("cache", "scratch", "counter");

        Object home = meta.getField("home").get(null);
        Object city = home.getClass().getField("city").get(home);
        assertThat(city).isSameAs(Fields.of(person, "home.city"));
        Object geo = home.getClass().getField("geo").get(home);
        assertThat(geo.getClass().getField("lat").get(geo)).isSameAs(Fields.of(person, "home.geo.lat"));
    }

    @Test
    void subclassesAndNestedEntities() throws Exception {
        ClassLoader loader = compile(SOURCES);
        Class<?> dog = loader.loadClass("demo.Dog");
        Class<?> meta = loader.loadClass("demo.Dog_");
        assertThat(meta.getField("name").get(null)).isSameAs(Fields.of(dog, "name"));
        assertThat(meta.getField("breed").get(null)).isSameAs(Fields.of(dog, "breed"));
        assertThat(loader.loadClass("demo.Outer_Inner_").getField("x").get(null))
                .isSameAs(Fields.of(loader.loadClass("demo.Outer$Inner"), "x"));
    }

    @Test
    void recursiveEmbeddableIsAnError() throws Exception {
        String errors = compileErrors(Map.of(
                "demo/Node.java", "package demo; @com.github.kookaburra.annotation.Embeddable public class Node { Node next; }",
                "demo/Tree.java", "package demo; @com.github.kookaburra.annotation.Entity public class Tree { Node root; }"));
        assertThat(errors).contains("Recursive");
    }

    @Test
    void invalidEntitiesAreErrors() throws Exception {
        String errors = compileErrors(Map.of(
                "demo/Bad.java", """
                        package demo;
                        import com.github.kookaburra.annotation.Entity;
                        @Entity interface Iface { }
                        @Entity class Generic<T> { }
                        class Holder { @Entity class Inner { } }
                        """));
        assertThat(errors).contains("must be placed on a class", "cannot be generic", "must be static");
    }

    @Test
    void everyTypeKind() throws Exception {
        ClassLoader loader = compile(Map.of(
                "demo/Emb.java", "package demo; @com.github.kookaburra.annotation.Embeddable public class Emb { int x; }",
                "demo/All.java", """
                        package demo;
                        import java.util.*;
                        import java.time.*;
                        @com.github.kookaburra.annotation.Entity public class All {
                            @com.github.kookaburra.annotation.Id long id;
                            Boolean bo; Short sh; Byte by; Character ch; Long lo; Double db; Float fl; float f; byte b; char c; short s;
                            LocalDateTime ldt; Instant in; List raw; List<? extends Number> wild; List<Emb> embs;
                            Map<String, Integer> map; byte[] bytes; Optional<String> opt; com.github.kookaburra.Ref raw2;
                            Set<List<String>> nested;
                        }
                        """,
                "Top.java", "@com.github.kookaburra.annotation.Entity public class Top { @com.github.kookaburra.annotation.Id long id; String n; }"));
        Class<?> meta = loader.loadClass("demo.All_");
        Map<String, String> kinds = new TreeMap<>();
        for (Field f : meta.getFields()) {
            kinds.put(f.getName(), f.getType().getSimpleName());
        }
        assertThat(kinds).containsEntry("bo", "BoolField").containsEntry("sh", "IntField").containsEntry("by", "IntField")
                .containsEntry("ch", "IntField").containsEntry("lo", "LongField").containsEntry("db", "DoubleField")
                .containsEntry("fl", "DoubleField").containsEntry("f", "DoubleField").containsEntry("b", "IntField")
                .containsEntry("c", "IntField").containsEntry("s", "IntField").containsEntry("ldt", "ComparableField")
                .containsEntry("in", "ComparableField").containsEntry("raw", "ObjectField").containsEntry("wild", "ObjectField")
                .containsEntry("embs", "ObjectField").containsEntry("map", "ObjectField").containsEntry("bytes", "ObjectField")
                .containsEntry("opt", "ObjectField").containsEntry("raw2", "RefField").containsEntry("nested", "ObjectField");
        assertThat(loader.loadClass("Top_").getField("n").get(null).getClass().getSimpleName()).isEqualTo("StringField");
    }

    // ---------------------------------------------------------------- helpers

    private ClassLoader compile(Map<String, String> sources) throws IOException {
        String errors = compileErrors(sources);
        assertThat(errors).isEmpty();
        return new URLClassLoader(new URL[]{tmp.resolve("out").toUri().toURL()}, getClass().getClassLoader());
    }

    private String compileErrors(Map<String, String> sources) throws IOException {
        Path src = tmp.resolve("src");
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        for (var e : sources.entrySet()) {
            Path p = src.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, null)) {
            var units = fm.getJavaFileObjectsFromPaths(sources.keySet().stream().map(src::resolve).toList());
            var task = javac.getTask(null, fm, diagnostics,
                    List.of("-d", out.toString(), "-s", out.toString(),
                            "-classpath", System.getProperty("java.class.path")),
                    null, units);
            task.setProcessors(List.of(new MetamodelProcessor()));
            task.call();
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .collect(Collectors.joining("\n"));
    }
}
