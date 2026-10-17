package com.github.kookaburra.constraint;

import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Layout;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.mapping.SchemaIO;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.model.Listing;
import com.github.kookaburra.model.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@link ConstraintTest} case on disk (checkpoints flush, compact and reopen; {@code reopen} replays the WAL),
 * plus adding constraints to stored data: duplicates or dangling references make the registration fail.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class ConstraintDiskTest extends ConstraintTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), config());
    }

    @Override
    protected void checkpoint() {
        db.flush();
        db.compact();
        reopen();
    }

    @Override
    protected void reopen() {
        db.close();
        db = ObjectDB.open(dir.resolve("db"), config());
    }

    /** A database whose stored Member class has no constraints yet (as written by an older version of the class). */
    private ObjectDB legacy(Path at) throws IOException {
        String name = Member.class.getName();
        Layout old = new Layout(0, List.of(new FieldDef("name", TypeDef.scalar(ValueType.STRING), null),
                new FieldDef("email", TypeDef.scalar(ValueType.STRING), null)));
        Files.createDirectories(at.resolve("schema"));
        Files.write(at.resolve("schema").resolve("schema_1"),
                SchemaIO.write(new Schema(1, List.of(new ClassDef(1, name, null, name, "oid", "version", List.of(old))))));
        return ObjectDB.open(at, config());
    }

    @Test
    void addingUniqueToDuplicatesFails() throws IOException {
        db.close();
        Path at = dir.resolve("legacy");
        db = legacy(at);
        for (String email : List.of("a@x.io", "b@x.io", "a@x.io")) {
            DynamicObject o = db.dynamic("Member").newObject();
            o.set("email", email);
            db.dynamic("Member").insert(o);
        }
        db.flush();
        assertThatThrownBy(() -> db.insert(new Member("new", "c@x.io"))).isInstanceOf(SchemaException.class)
                .hasMessageContaining("cannot add @Unique to Member.email").hasMessageContaining("both have a@x.io");
        assertThat(db.schema().constraints()).isEmpty();               // the schema stays as it was
        DynamicObject dup = db.dynamic("Member").from().where("email").eq("a@x.io").list().get(1);
        db.dynamic("Member").delete(dup);
        db.insert(new Member("new", "c@x.io"));                         // clean data: the constraints are added
        assertThat(db.schema().constraints()).hasSize(4);
        assertThatThrownBy(() -> db.insert(new Member("dup", "a@x.io"))).isInstanceOf(ConstraintViolationException.class);
        db.close();
        db = ObjectDB.open(at, config());                                // schema format 2 persisted the constraints
        assertThat(db.schema().constraints()).hasSize(4);
        assertThatThrownBy(() -> db.insert(new Member("dup", "a@x.io"))).isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addingCompositeUniqueToDuplicatesFails() throws IOException {
        db.close();
        Path at = dir.resolve("legacy-listing");
        String name = Listing.class.getName();
        Layout old = new Layout(0, List.of(new FieldDef("tenant", TypeDef.scalar(ValueType.STRING), null),
                new FieldDef("code", TypeDef.scalar(ValueType.STRING), null)));
        Files.createDirectories(at.resolve("schema"));
        Files.write(at.resolve("schema").resolve("schema_1"),
                SchemaIO.write(new Schema(1, List.of(new ClassDef(1, name, null, name, "oid", "version", List.of(old))))));
        db = ObjectDB.open(at, config());
        for (String code : List.of("A", "B", "A")) {
            DynamicObject o = db.dynamic("Listing").newObject();
            o.set("tenant", "acme");
            o.set("code", code);
            db.dynamic("Listing").insert(o);
        }
        assertThatThrownBy(() -> db.insert(new Listing("acme", "C"))).isInstanceOf(SchemaException.class)
                .hasMessageContaining("cannot add @Unique Listing(tenant, code)").hasMessageContaining("[acme, A]");
        assertThat(db.schema().compositeUniques()).isEmpty();
        db.dynamic("Listing").delete(db.dynamic("Listing").from().where("code").eq("A").list().get(1));
        db.insert(new Listing("acme", "C"));
        assertThat(db.schema().compositeUniques()).hasSize(4);
        db.close();
        db = ObjectDB.open(at, config());                             // schema format 3 keeps them
        assertThat(db.schema().compositeUniques()).hasSize(4);
        assertThatThrownBy(() -> db.insert(new Listing("acme", "B"))).isInstanceOf(ConstraintViolationException.class);
    }
}
