package com.github.kookaburra.mapping;

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
import com.github.kookaburra.annotation.Renamed;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Vector;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.annotation.VectorQuantization;
import com.github.kookaburra.annotation.Version;
import com.github.kookaburra.text.Analyzers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The stored schema as Java source code: one file per top-level class with the entity classes (hierarchy, {@code @Id},
 * {@code @Version}, class-level {@code @Unique}), the embeddables and the enums they use, every field with its
 * annotations ({@code @Index}, {@code @Unique}, {@code @ForeignKey}, {@code @FullText}, {@code @Vector},
 * {@code @Comment}) and getters and setters. Compiled, the classes map onto the stored data as they are: opening the
 * database with them adds no schema version. Nested classes ({@code Outer$Inner}) are written nested.
 * <p>
 * With a target package the classes move there and carry {@code @Renamed} with their stored names, so opening the
 * database with them renames the stored classes and keeps their data. Fields are written with wrapper types
 * ({@code Integer}, {@code Long} …): the schema keeps no difference between {@code int} and {@code Integer}, and
 * wrappers read missing values as null.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class SchemaExport {

    /**
     * One generated file.
     *
     * @param className fully qualified name of its top-level class
     * @param path      relative path of the file, e.g. {@code com/acme/Customer.java}
     * @param source    the Java source
     */
    public record SourceFile(String className, String path, String source) {
    }

    private static final String INDENT = "    ";

    private final List<SourceFile> files;
    private final List<String> warnings;

    private SchemaExport(List<SourceFile> files, List<String> warnings) {
        this.files = List.copyOf(files);
        this.warnings = List.copyOf(warnings);
    }

    /** The generated files, sorted by path. */
    public List<SourceFile> files() {
        return files;
    }

    /** What the generated code cannot reproduce by itself (types to put on the classpath, renamed classes …). */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Writes every file under {@code dir} (package directories created); existing files are replaced.
     *
     * @return the written files
     */
    public List<Path> writeTo(Path dir) {
        List<Path> out = new ArrayList<>();
        try {
            for (SourceFile f : files) {
                Path p = dir.resolve(f.path());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.source(), StandardCharsets.UTF_8);
                out.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * Generates the code of {@code schema}.
     *
     * @param targetPackage package for all classes, or null / empty to keep the stored packages
     */
    public static SchemaExport of(Schema schema, String targetPackage) {
        return new Generator(schema, targetPackage == null || targetPackage.isBlank() ? null : targetPackage.trim()).run();
    }

    // ---------------------------------------------------------------- generator

    private enum Kind { ENTITY, EMBEDDABLE, ENUM }

    /** A class to write: its stored name, its name in the code, and what it holds. */
    private static final class Type {
        final Kind kind;
        final String stored;
        String name;
        ClassDef def;                 // ENTITY
        List<FieldDef> fields;        // EMBEDDABLE
        List<String> constants;       // ENUM
        final List<Type> nested = new ArrayList<>();
        boolean holder;               // a plain class only holding nested classes

        Type(Kind kind, String stored) {
            this.kind = kind;
            this.stored = stored;
            this.name = stored;
        }

        String pkg() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(0, dot);
        }

        /** Simple name of the class itself ({@code Inner} of {@code a.Outer$Inner}). */
        String simple() {
            String s = name.substring(name.lastIndexOf('.') + 1);
            return s.substring(s.lastIndexOf('$') + 1);
        }

        /** Name of the top-level class. */
        String top() {
            int d = name.indexOf('$');
            return d < 0 ? name : name.substring(0, d);
        }

        /** How code refers to it from its package: {@code Outer.Inner}. */
        String reference() {
            return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
        }
    }

    private static final class Generator {
        private final Schema schema;
        private final String target;
        private final Map<String, Type> types = new TreeMap<>();
        private final List<String> warnings = new ArrayList<>();

        Generator(Schema schema, String target) {
            this.schema = schema;
            this.target = target;
        }

        SchemaExport run() {
            for (ClassDef c : schema.classes()) {
                Type t = new Type(Kind.ENTITY, c.name());
                t.def = c;
                types.put(c.name(), t);
            }
            for (ClassDef c : schema.classes()) {
                collect(c.current().fields(), c.name());
            }
            rename();
            Map<String, Type> tops = new TreeMap<>();
            for (Type t : List.copyOf(types.values())) {
                place(t, tops);
            }
            List<SourceFile> files = new ArrayList<>();
            for (Type top : tops.values()) {
                files.add(new SourceFile(top.name, top.name.replace('.', '/') + ".java", new FileWriter(top).write()));
            }
            files.sort(Comparator.comparing(SourceFile::path));
            if (target != null && !schema.classes().isEmpty()) {
                warnings.add(0, "Classes move to package " + target + ": opening the database with them renames the stored"
                        + " classes (@Renamed keeps their data); applications using the old names cannot open it afterwards.");
            }
            return new SchemaExport(files, warnings);
        }

        /** Embeddables and enums used by {@code fields}, at any depth. */
        private void collect(List<FieldDef> fields, String where) {
            for (FieldDef f : fields) {
                collect(f.type(), where + "." + f.name());
            }
        }

        private void collect(TypeDef t, String where) {
            if (t == null) {
                return;
            }
            switch (t.kind()) {
                case ENUM -> {
                    Type e = types.get(t.name());
                    if (e == null) {
                        e = new Type(Kind.ENUM, t.name());
                        e.constants = t.constants();
                        types.put(t.name(), e);
                    } else if (e.kind == Kind.ENUM && t.constants().size() > e.constants.size()) {
                        e.constants = t.constants();
                    }
                }
                case EMBEDDED -> {
                    Type e = types.get(t.name());
                    if (e == null) {
                        e = new Type(Kind.EMBEDDABLE, t.name());
                        e.fields = t.fields();
                        types.put(t.name(), e);
                        collect(t.fields(), where);
                    } else if (e.kind == Kind.EMBEDDABLE
                            && !TypeRegistry.withoutComments(e.fields).equals(TypeRegistry.withoutComments(t.fields()))) {
                        warnings.add("Embeddable " + t.name() + " is stored with different fields at " + where
                                + "; the code has the fields of its first use.");
                    }
                }
                case MULTI, EMBEDDED_LIST -> collect(t.element(), where);
                case MAP -> {
                    collect(t.key(), where);
                    collect(t.element(), where);
                }
                case OBJECT -> {
                    if (!t.name().startsWith("java.")) {
                        warnings.add(where + " holds serialized " + t.name() + ": put that class on the classpath.");
                    }
                }
                default -> {
                }
            }
        }

        /** New names in the target package; clashes get a number. */
        private void rename() {
            if (target == null) {
                return;
            }
            Set<String> used = new TreeSet<>();
            Map<String, String> tops = new LinkedHashMap<>();       // stored top-level name → new one
            for (Type t : types.values()) {
                String storedTop = t.top();
                String newTop = tops.get(storedTop);
                if (newTop == null) {
                    String simple = storedTop.substring(storedTop.lastIndexOf('.') + 1);
                    newTop = target + "." + simple;
                    for (int n = 2; used.contains(newTop); n++) {
                        newTop = target + "." + simple + n;
                    }
                    if (!newTop.endsWith("." + simple)) {
                        warnings.add(storedTop + " is written as " + newTop + " (the simple name is taken).");
                    }
                    used.add(newTop);
                    tops.put(storedTop, newTop);
                }
                t.name = newTop + t.stored.substring(storedTop.length());
            }
        }

        /** Puts {@code t} into its top-level class, creating holders for missing outer classes. */
        private void place(Type t, Map<String, Type> tops) {
            int d = t.name.lastIndexOf('$');
            if (d < 0) {
                Type existing = tops.get(t.name);
                if (existing != null && existing.holder) {         // a holder made before: the real class takes its nested
                    t.nested.addAll(existing.nested);
                }
                tops.put(t.name, t);
                return;
            }
            String outerName = t.name.substring(0, d);
            Type outer = byNewName(outerName);
            if (outer == null) {
                outer = holder(outerName, tops);
            }
            outer.nested.add(t);
            if (t.simple().isEmpty() || !Character.isJavaIdentifierStart(t.simple().charAt(0))) {
                warnings.add(t.stored + " is not a named class; its code will not compile.");
            }
        }

        private Type byNewName(String name) {
            for (Type t : types.values()) {
                if (t.name.equals(name)) {
                    return t;
                }
            }
            return null;
        }

        private final Map<String, Type> holders = new TreeMap<>();

        private Type holder(String name, Map<String, Type> tops) {
            Type h = holders.get(name);
            if (h != null) {
                return h;
            }
            h = new Type(Kind.EMBEDDABLE, name);
            h.holder = true;
            h.name = name;
            holders.put(name, h);
            int d = name.lastIndexOf('$');
            if (d < 0) {
                tops.putIfAbsent(name, h);
            } else {
                Type outer = byNewName(name.substring(0, d));
                (outer != null ? outer : holder(name.substring(0, d), tops)).nested.add(h);
            }
            return h;
        }

        /** Name a stored class has in the code. */
        private Type typeOf(String stored) {
            Type t = types.get(stored);
            if (t == null) {
                ClassDef c = schema.byName(stored);
                t = c == null ? null : types.get(c.name());
            }
            return t;
        }

        // ------------------------------------------------------------ one file

        private final class FileWriter {
            private final Type top;
            private final Map<String, String> imports = new TreeMap<>();      // simple name → fully qualified
            private final StringBuilder body = new StringBuilder();

            FileWriter(Type top) {
                this.top = top;
            }

            String write() {
                writeType(top, 0);
                StringBuilder sb = new StringBuilder();
                if (!top.pkg().isEmpty()) {
                    sb.append("package ").append(top.pkg()).append(";\n\n");
                }
                List<String> ordered = new ArrayList<>(new TreeSet<>(imports.values()));
                ordered.removeIf(i -> i.startsWith("java.lang.") && i.indexOf('.', 10) < 0);
                List<String> java = ordered.stream().filter(i -> i.startsWith("java.")).toList();
                List<String> other = ordered.stream().filter(i -> !i.startsWith("java.")).toList();
                other.forEach(i -> sb.append("import ").append(i).append(";\n"));
                if (!other.isEmpty() && !java.isEmpty()) {
                    sb.append('\n');
                }
                java.forEach(i -> sb.append("import ").append(i).append(";\n"));
                if (!ordered.isEmpty()) {
                    sb.append('\n');
                }
                return sb.append(body).toString();
            }

            /** The name to write for {@code fqn} (imported when free; nested types through their top class). */
            private String use(String fqn) {
                if (fqn.startsWith("java.lang.") && fqn.indexOf('.', 10) < 0) {
                    return fqn.substring(10);
                }
                int dot = fqn.lastIndexOf('.');
                String simple = fqn.substring(dot + 1);
                if (dot > 0 && fqn.substring(0, dot).equals(top.pkg()) && !imports.containsKey(simple)
                        && !clashesWithOwnTypes(simple, fqn)) {
                    return simple;                                          // same package: no import
                }
                String taken = imports.get(simple);
                if (taken == null && !clashesWithOwnTypes(simple, fqn)) {
                    imports.put(simple, fqn);
                    return simple;
                }
                return fqn.equals(taken) ? simple : fqn;
            }

            private boolean clashesWithOwnTypes(String simple, String fqn) {
                return named(top, simple) && !fqn.equals(top.name.replace('$', '.'));
            }

            private boolean named(Type t, String simple) {
                if (t.simple().equals(simple)) {
                    return true;
                }
                for (Type n : t.nested) {
                    if (named(n, simple)) {
                        return true;
                    }
                }
                return false;
            }

            /** Reference to a generated class. */
            private String useType(Type t) {
                if (t.top().equals(top.name)) {                           // same file: members of the top class are in scope
                    String ref = t.reference();
                    String outer = top.simple() + ".";
                    return ref.startsWith(outer) ? ref.substring(outer.length()) : ref;
                }
                String topName = t.top();
                String ref = use(topName);
                String rest = t.name.substring(topName.length()).replace('$', '.');
                return ref + rest;
            }

            private String annotation(Class<?> a) {
                return "@" + use(a.getName());
            }

            private void line(int depth, String text) {
                body.append(INDENT.repeat(depth)).append(text).append('\n');
            }

            private void javadoc(int depth, String text) {
                if (text == null || text.isBlank()) {
                    return;
                }
                String safe = text.replace("*/", "*&#47;");
                String[] lines = safe.split("\\R");
                if (lines.length == 1) {
                    line(depth, "/** " + lines[0] + " */");
                    return;
                }
                line(depth, "/**");
                for (String l : lines) {
                    line(depth, " * " + l);
                }
                line(depth, " */");
            }

            private void writeType(Type t, int depth) {
                String mods = depth == 0 ? "public " : "public static ";
                switch (t.kind) {
                    case ENUM -> {
                        line(depth, "/** Enum stored as " + t.stored + ". */");
                        line(depth, "public enum " + t.simple() + " {");
                        line(depth + 1, String.join(", ", t.constants));
                        if (!t.nested.isEmpty()) {
                            body.setLength(body.length() - 1);
                            body.append(";\n");
                            nested(t, depth);
                        }
                        line(depth, "}");
                    }
                    case EMBEDDABLE -> {
                        if (t.holder) {
                            line(depth, "/** Holds the nested classes of " + t.name.replace('$', '.') + ". */");
                            line(depth, mods + "final class " + t.simple() + " {");
                            body.append('\n');
                            line(depth + 1, "private " + t.simple() + "() {");
                            line(depth + 1, "}");
                            nested(t, depth);
                            line(depth, "}");
                            return;
                        }
                        line(depth, "/** Embedded value stored as " + t.stored + ". */");
                        line(depth, annotation(Embeddable.class));
                        line(depth, mods + "class " + t.simple() + " {");
                        members(t, depth, null, null, t.fields);
                        nested(t, depth);
                        line(depth, "}");
                    }
                    case ENTITY -> entity(t, depth, mods);
                }
            }

            private void nested(Type t, int depth) {
                for (Type n : t.nested) {
                    body.append('\n');
                    writeType(n, depth + 1);
                }
            }

            private void entity(Type t, int depth, String mods) {
                ClassDef c = t.def;
                ClassDef parent = c.parent() == null ? null : schema.byName(c.parent());
                javadoc(depth, c.comment() != null ? c.comment() : null);
                if (c.comment() == null) {
                    line(depth, "/** Entity stored as " + c.name() + ". */");
                }
                line(depth, annotation(Entity.class));
                if (c.comment() != null) {
                    line(depth, annotation(Comment.class) + "(" + literal(c.comment()) + ")");
                }
                if (target != null) {
                    line(depth, annotation(Renamed.class) + "(" + literal(c.name()) + ")");
                }
                for (UniqueDef u : c.uniques()) {
                    StringBuilder a = new StringBuilder(annotation(Unique.class))
                            .append("(fields = {");
                    for (int i = 0; i < u.paths().size(); i++) {
                        a.append(i == 0 ? "" : ", ").append(literal(u.paths().get(i)));
                    }
                    a.append('}');
                    if (!u.name().isEmpty()) {
                        a.append(", name = ").append(literal(u.name()));
                    }
                    line(depth, a.append(')').toString());
                }
                String ext = "";
                List<FieldDef> fields = c.current().fields();
                if (parent != null) {
                    Type p = typeOf(parent.name());
                    ext = " extends " + useType(p);
                    Set<String> inherited = new TreeSet<>();
                    parent.current().fields().forEach(f -> inherited.add(f.name()));
                    fields = fields.stream().filter(f -> !inherited.contains(f.name())).toList();
                }
                line(depth, mods + "class " + t.simple() + ext + " {");
                String id = parent != null && c.idField().equals(parent.idField()) ? null : c.idField();
                String version = c.versionField() == null || parent != null && c.versionField().equals(parent.versionField())
                        ? null : c.versionField();
                members(t, depth, id, version, fields);
                nested(t, depth);
                line(depth, "}");
            }

            private void members(Type t, int depth, String id, String version, List<FieldDef> fields) {
                int d = depth + 1;
                body.append('\n');
                if (id != null) {
                    line(d, annotation(Id.class));
                    line(d, "private long " + id + ";");
                }
                if (version != null) {
                    line(d, annotation(Version.class));
                    line(d, "private long " + version + ";");
                }
                if (id != null || version != null) {
                    body.append('\n');
                }
                List<String[]> props = new ArrayList<>();
                for (FieldDef f : fields) {
                    javadoc(d, f.comment());
                    for (String a : annotations(f)) {
                        line(d, a);
                    }
                    String type = javaType(f.type());
                    line(d, "private " + type + " " + f.name() + ";");
                    body.append('\n');
                    props.add(new String[]{type, f.name()});
                }
                line(d, "public " + t.simple() + "() {");
                line(d, "}");
                if (id != null) {
                    accessors(d, "long", id);
                }
                if (version != null) {
                    accessors(d, "long", version);
                }
                for (String[] p : props) {
                    accessors(d, p[0], p[1]);
                }
            }

            private void accessors(int d, String type, String name) {
                String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                body.append('\n');
                line(d, "public " + type + " get" + cap + "() {");
                line(d + 1, "return " + name + ";");
                line(d, "}");
                body.append('\n');
                line(d, "public void set" + cap + "(" + type + " " + name + ") {");
                line(d + 1, "this." + name + " = " + name + ";");
                line(d, "}");
            }

            private List<String> annotations(FieldDef f) {
                List<String> out = new ArrayList<>();
                TypeDef t = f.type();
                IndexKind index = f.index();
                if (index == IndexKind.FULLTEXT) {
                    String analyzer = t.name() == null ? Analyzers.DEFAULT : t.name();
                    out.add(annotation(FullText.class)
                            + (analyzer.equals(Analyzers.DEFAULT) ? "" : "(analyzer = " + literal(analyzer) + ")"));
                } else if (index != null && !(index == IndexKind.AUTO && (f.unique() || f.onDelete() != null))) {
                    out.add(annotation(Index.class)
                            + (index == IndexKind.AUTO ? "" : "(" + use(IndexKind.class.getName()) + "." + index.name() + ")"));
                }
                if (f.unique()) {
                    out.add(annotation(Unique.class));
                }
                if (f.onDelete() != null) {
                    out.add(annotation(ForeignKey.class) + (f.onDelete() == OnDelete.RESTRICT ? ""
                            : "(onDelete = " + use(OnDelete.class.getName()) + "." + f.onDelete().name() + ")"));
                }
                if (t.kind() == ValueType.VECTOR) {
                    List<String> args = new ArrayList<>();
                    if (t.dim() > 0) {
                        args.add("dim = " + t.dim());
                    }
                    if (t.distance() != VectorDistance.COSINE) {
                        args.add("distance = " + use(VectorDistance.class.getName()) + "." + t.distance().name());
                    }
                    if (t.quantization() != VectorQuantization.NONE) {
                        args.add("quantization = " + use(VectorQuantization.class.getName()) + "." + t.quantization().name());
                    }
                    if (!args.isEmpty()) {
                        out.add(annotation(Vector.class) + "(" + String.join(", ", args) + ")");
                    }
                }
                if (f.comment() != null) {
                    out.add(annotation(Comment.class) + "(" + literal(f.comment()) + ")");
                }
                return out;
            }

            private String javaType(TypeDef t) {
                return switch (t.kind()) {
                    case BOOL -> "Boolean";
                    case INT32 -> "Integer";
                    case INT64 -> "Long";
                    case FLOAT64 -> "Double";
                    case DECIMAL -> use("java.math.BigDecimal");
                    case STRING -> "String";
                    case DATE -> use("java.time.LocalDate");
                    case DATETIME -> use("java.time.LocalDateTime");
                    case INSTANT -> use("java.time.Instant");
                    case BYTES -> "byte[]";
                    case VECTOR -> "float[]";
                    case ENUM, EMBEDDED -> useType(typeOf(t.name()));
                    case REF -> use(Ref.class.getName()) + "<" + refTarget(t.name()) + ">";
                    case MULTI, EMBEDDED_LIST -> use(t.set() ? "java.util.Set" : "java.util.List") + "<" + javaType(t.element()) + ">";
                    case MAP -> use("java.util.Map") + "<" + javaType(t.key()) + ", " + javaType(t.element()) + ">";
                    case OBJECT -> use(t.name().replace('$', '.'));
                };
            }

            private String refTarget(String stored) {
                Type t = typeOf(stored);
                if (t != null) {
                    return useType(t);
                }
                if (!stored.equals(Object.class.getName())) {
                    warnings.add("A reference points at " + stored + ", which is not stored: put that class on the classpath.");
                }
                return use(stored.replace('$', '.'));
            }
        }
    }

    /** A Java string literal. */
    static String literal(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
