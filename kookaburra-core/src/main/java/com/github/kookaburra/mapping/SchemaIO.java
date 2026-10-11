package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.IndexKind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Binary schema file: {@code magic · body · crc32c(body)}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SchemaIO {

    private static final long MAGIC = 0x4B4B4253_43483031L;   // "KKBSCH01"

    private SchemaIO() {
    }

    public static byte[] write(Schema schema) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.writeInt(schema.version());
            out.writeInt(schema.classes().size());
            for (ClassDef c : schema.classes()) {
                out.writeInt(c.id());
                out.writeUTF(c.name());
                writeNullable(out, c.parent());
                out.writeUTF(c.collection());
                out.writeUTF(c.idField());
                writeNullable(out, c.versionField());
                out.writeInt(c.layouts().size());
                for (Layout l : c.layouts()) {
                    out.writeInt(l.id());
                    writeFields(out, l.fields());
                }
            }
            out.flush();
            byte[] b = body.toByteArray();
            CRC32C crc = new CRC32C();
            crc.update(b);
            ByteArrayOutputStream file = new ByteArrayOutputStream(b.length + 16);
            DataOutputStream f = new DataOutputStream(file);
            f.writeLong(MAGIC);
            f.write(b);
            f.writeInt((int) crc.getValue());
            f.flush();
            return file.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Schema read(byte[] file) {
        if (file.length < 12) {
            throw new SchemaException("schema file truncated");
        }
        try {
            DataInputStream head = new DataInputStream(new ByteArrayInputStream(file));
            if (head.readLong() != MAGIC) {
                throw new SchemaException("not a schema file");
            }
            CRC32C crc = new CRC32C();
            crc.update(file, 8, file.length - 12);
            int stored = new DataInputStream(new ByteArrayInputStream(file, file.length - 4, 4)).readInt();
            if ((int) crc.getValue() != stored) {
                throw new SchemaException("schema file checksum mismatch");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(file, 8, file.length - 12));
            int version = in.readInt();
            int n = in.readInt();
            List<ClassDef> classes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int id = in.readInt();
                String name = in.readUTF();
                String parent = readNullable(in);
                String collection = in.readUTF();
                String idField = in.readUTF();
                String versionField = readNullable(in);
                int nl = in.readInt();
                List<Layout> layouts = new ArrayList<>(nl);
                for (int j = 0; j < nl; j++) {
                    layouts.add(new Layout(in.readInt(), readFields(in)));
                }
                classes.add(new ClassDef(id, name, parent, collection, idField, versionField, layouts));
            }
            return new Schema(version, classes);
        } catch (IOException e) {
            throw new SchemaException("corrupt schema file", e);
        }
    }

    private static void writeFields(DataOutputStream out, List<FieldDef> fields) throws IOException {
        out.writeInt(fields.size());
        for (FieldDef f : fields) {
            out.writeUTF(f.name());
            writeNullable(out, f.index() == null ? null : f.index().name());
            writeType(out, f.type());
        }
    }

    private static List<FieldDef> readFields(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<FieldDef> fields = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = in.readUTF();
            String index = readNullable(in);
            fields.add(new FieldDef(name, readType(in), index == null ? null : IndexKind.valueOf(index)));
        }
        return fields;
    }

    private static void writeType(DataOutputStream out, TypeDef t) throws IOException {
        out.writeUTF(t.kind().name());
        writeNullable(out, t.name());
        out.writeInt(t.constants().size());
        for (String c : t.constants()) {
            out.writeUTF(c);
        }
        out.writeBoolean(t.element() != null);
        if (t.element() != null) {
            writeType(out, t.element());
        }
        out.writeBoolean(t.key() != null);
        if (t.key() != null) {
            writeType(out, t.key());
        }
        writeFields(out, t.fields());
        out.writeBoolean(t.set());
    }

    private static TypeDef readType(DataInputStream in) throws IOException {
        ValueType kind = ValueType.valueOf(in.readUTF());
        String name = readNullable(in);
        int nc = in.readInt();
        List<String> constants = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            constants.add(in.readUTF());
        }
        TypeDef element = in.readBoolean() ? readType(in) : null;
        TypeDef key = in.readBoolean() ? readType(in) : null;
        List<FieldDef> fields = readFields(in);
        return new TypeDef(kind, name, constants, element, key, fields, in.readBoolean());
    }

    private static void writeNullable(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
