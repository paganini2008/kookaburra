package com.github.kookaburra.constraint;

import com.github.kookaburra.Config;
import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.mapping.UniqueDef;
import com.github.kookaburra.model.Contact;
import com.github.kookaburra.model.Desk;
import com.github.kookaburra.model.Listing;
import com.github.kookaburra.model.Member;
import com.github.kookaburra.model.Membership;
import com.github.kookaburra.model.Note;
import com.github.kookaburra.model.PromoListing;
import com.github.kookaburra.model.Task;
import com.github.kookaburra.model.Team;
import com.github.kookaburra.model.VipMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Unique} and {@code @ForeignKey}: duplicates across autocommit writes, batches, transactions,
 * subclasses, embedded paths and decimals; references that must exist; ON DELETE RESTRICT / CASCADE (recursive) /
 * SET_NULL (ref, list of refs, embedded ref); backlinks ({@code referrers}); WAL replay of cascades
 * ({@link ConstraintDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class ConstraintTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true);
    }

    /** Disk variant: flush, compact, close and reopen. */
    protected void checkpoint() {
    }

    /** Disk variant: close and reopen without flushing (state comes back from the WAL). */
    protected void reopen() {
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static ConstraintViolationException.Kind kind(Throwable t) {
        return ((ConstraintViolationException) t).kind();
    }

    @Test
    void uniqueValues() {
        long ann = db.insert(new Member("ann", "ann@x.io").badge(1).code("1.50").contact(new Contact("+61 1", "Perth")));
        db.insert(new Member("bob", "bob@x.io").badge(2));
        db.insert(new Member("n1", null));                           // nulls never conflict
        db.insert(new Member("n2", null).contact(new Contact(null, "Perth")));
        checkpoint();

        assertThatThrownBy(() -> db.insert(new Member("ann2", "ann@x.io")))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessage("duplicate value 'ann@x.io' for unique Member.email (already used by object " + ann + ")")
                .satisfies(t -> {
                    ConstraintViolationException e = (ConstraintViolationException) t;
                    assertThat(e.kind()).isEqualTo(ConstraintViolationException.Kind.UNIQUE);
                    assertThat(e.className()).isEqualTo(Member.class.getName());
                    assertThat(e.field()).isEqualTo("email");
                    assertThat(e.oid()).isEqualTo(ann);
                });
        assertThatThrownBy(() -> db.insert(new Member("x", "x@x.io").badge(1))).hasMessageContaining("Member.badge");
        assertThatThrownBy(() -> db.insert(new Member("x", "x@x.io").code("1.5000"))).hasMessageContaining("Member.code");  // numeric
        assertThatThrownBy(() -> db.insert(new Member("x", "x@x.io").contact(new Contact("+61 1", "Hobart"))))
                .hasMessageContaining("Member.contact.phone");
        assertThatThrownBy(() -> db.insert(new VipMember("vip", "bob@x.io")))           // subclasses share the constraint
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(db.from(Member.class).count()).isEqualTo(4);   // nothing of the failed writes is stored

        Member bob = db.from(Member.class).where(Member::getName).eq("bob").first().orElseThrow();
        assertThatThrownBy(() -> db.update(bob.email("ann@x.io"))).isInstanceOf(ConstraintViolationException.class);
        db.update(bob.email("bob@x.io").badge(2));                  // own value: fine

        // a batch may swap values; duplicates inside one batch fail it as a whole
        Member a = db.get(Member.class, ann);
        Member b = db.from(Member.class).where(Member::getName).eq("bob").first().orElseThrow();
        try (WriteBatch batch = db.batch()) {
            batch.update(a.email("swap@x.io")).update(b.email("ann@x.io")).commit();
        }
        Member a2 = db.get(Member.class, ann);
        try (WriteBatch batch = db.batch()) {
            batch.update(a2.email("bob@x.io")).insert(new Member("c", "c@x.io")).insert(new Member("d", "c@x.io"));
            assertThatThrownBy(batch::commit).isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("'c@x.io'");
        }
        assertThat(db.get(Member.class, ann).getEmail()).isEqualTo("swap@x.io");
        // delete and re-use a value in the same batch
        Member b2 = db.from(Member.class).where(Member::getName).eq("bob").first().orElseThrow();
        try (WriteBatch batch = db.batch()) {
            batch.delete(b2).insert(new Member("bob2", "ann@x.io").badge(2)).commit();
        }
        reopen();
        assertThat(db.from(Member.class).where(Member::getEmail).eq("ann@x.io").first().orElseThrow().getName()).isEqualTo("bob2");
        // dynamic writes are checked too
        DynamicObject dup = db.dynamic("Member").newObject();
        dup.set("email", "swap@x.io");
        assertThatThrownBy(() -> db.dynamic("Member").insert(dup)).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> db.oql("UPDATE Member m SET m.badge = 9").execute()).isInstanceOf(ConstraintViolationException.class);
        assertThat(db.oql("SELECT COUNT(*) FROM Member m WHERE m.badge = 9").list()).containsExactly(0L);
    }

    @Test
    void uniqueInTransactions() {
        db.insert(new Member("ann", "ann@x.io"));
        checkpoint();
        try (Transaction t1 = db.begin(); Transaction t2 = db.begin()) {
            t1.insert(new Member("t1", "new@x.io"));
            t2.insert(new Member("t2", "new@x.io"));
            t2.insert(new Member("t2b", "ann@x.io"));               // deferred: checked at commit
            assertThat(t2.from(Member.class).where(Member::getEmail).eq("ann@x.io").count()).isEqualTo(2);
            t1.commit();
            assertThatThrownBy(t2::commit).isInstanceOf(ConstraintViolationException.class);
            assertThat(t2.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        }
        // not retried: the same writes would fail again
        int[] attempts = new int[1];
        assertThatThrownBy(() -> db.inTransaction(tx -> {
            attempts[0]++;
            tx.insert(new Member("again", "new@x.io"));
        })).isInstanceOf(ConstraintViolationException.class);
        assertThat(attempts[0]).isEqualTo(1);
        // within one transaction: free a value and take it again
        try (Transaction tx = db.begin()) {
            Member ann = tx.from(Member.class).where(Member::getEmail).eq("ann@x.io").first().orElseThrow();
            tx.update(ann.email("old-ann@x.io"));
            tx.insert(new Member("ann-new", "ann@x.io"));
            tx.commit();
        }
        checkpoint();
        assertThat(db.from(Member.class).where(Member::getEmail).in("ann@x.io", "old-ann@x.io", "new@x.io").count()).isEqualTo(3);
    }

    @Test
    void foreignKeysMustPointAtExistingObjects() {
        long ann = db.insert(new Member("ann", "ann@x.io"));
        long red = db.insert(new Team("red"));
        db.insert(new Membership(ann, red, "lead"));
        assertThatThrownBy(() -> db.insert(new Membership(ann, ann, "x")))             // a Member where a Team belongs
                .isInstanceOf(ConstraintViolationException.class).hasMessageContaining("which is not a " + Team.class.getName());
        long gone = db.insert(new Team("gone"));
        db.delete(Team.class, gone);
        checkpoint();
        assertThatThrownBy(() -> db.insert(new Membership(ann, gone, "x")))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("references object " + gone + " through Membership.team, which does not exist")
                .satisfies(t -> assertThat(kind(t)).isEqualTo(ConstraintViolationException.Kind.FOREIGN_KEY));
        assertThatThrownBy(() -> db.insert(new Task("t").watchers(List.of(Ref.of(ann), Ref.of(gone + 1000)))))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> db.insert(new Task("t").desk(new Desk(gone, 1)))).hasMessageContaining("Task.desk.room");
        assertThatThrownBy(() -> db.insert(new Task("t").assignee(Long.MAX_VALUE >>> 2))).isInstanceOf(ConstraintViolationException.class);
        db.insert(new Task("plain").author(gone));                   // an unchecked reference may dangle

        // a transaction may insert the target and the reference together; deleting the target in the same commit fails
        try (Transaction tx = db.begin()) {
            Member bob = new Member("bob", "bob@x.io");
            long bobOid = tx.insert(bob);
            tx.insert(new Membership(bobOid, red, "dev"));
            tx.commit();
        }
        try (Transaction tx = db.begin()) {
            long cid = tx.insert(new Member("cid", "cid@x.io"));
            tx.insert(new Task("t").assignee(cid));
            tx.delete(Member.class, cid);                               // inserted and deleted: SET_NULL clears the new task
            tx.commit();
        }
        assertThat(db.from(Task.class).where(Task::getTitle).eq("t").first().orElseThrow().getAssignee()).isNull();
        reopen();
        assertThat(db.from(Membership.class).count()).isEqualTo(2);
    }

    @Test
    void onDeleteRestrictCascadeAndSetNull() {
        long ann = db.insert(new Member("ann", "ann@x.io"));
        long bob = db.insert(new Member("bob", "bob@x.io"));
        long red = db.insert(new Team("red"));
        long blue = db.insert(new Team("blue"));
        long m1 = db.insert(new Membership(ann, red, "lead"));
        long m2 = db.insert(new Membership(ann, blue, "dev"));
        long m3 = db.insert(new Membership(bob, red, "dev"));
        db.insert(new Note(m1, "first"));
        db.insert(new Note(m1, "second"));
        long keep = db.insert(new Note(m3, "bob's"));
        long t1 = db.insert(new Task("t1").assignee(ann).watchers(new ArrayList<>(List.of(Ref.of(ann), Ref.of(bob), Ref.of(ann))))
                .desk(new Desk(blue, 4)).author(ann));
        long t2 = db.insert(new Task("t2").assignee(bob).desk(new Desk(red, 5)));
        checkpoint();

        // backlinks
        assertThat(db.referrers(ann)).extracting(DynamicObject::oid).containsExactlyInAnyOrder(m1, m2, t1);
        assertThat(db.referrers(Membership.class, ann)).extracting(Membership::getOid).containsExactlyInAnyOrder(m1, m2);
        assertThat(db.referrers(Task.class, blue)).extracting(Task::getOid).containsExactly(t1);
        assertThat(db.referrers(Team.class, ann)).isEmpty();
        assertThat(db.referrers(keep)).isEmpty();

        // RESTRICT: a team with memberships cannot go; nothing changes
        assertThatThrownBy(() -> db.delete(Team.class, red))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("cannot delete object " + red)
                .hasMessageContaining("Membership.team (ON DELETE RESTRICT)");
        assertThat(db.get(Team.class, red)).isNotNull();

        // CASCADE (two levels) + SET_NULL in one atomic commit
        Task before = db.get(Task.class, t1);
        db.delete(db.get(Member.class, ann));
        assertThat(db.get(Membership.class, m1)).isNull();
        assertThat(db.get(Membership.class, m2)).isNull();
        assertThat(db.from(Note.class).list()).extracting(Note::getOid).containsExactly(keep);
        Task after = db.get(Task.class, t1);
        assertThat(after.getAssignee()).isNull();
        assertThat(after.getWatchers()).containsExactly(Ref.of(bob));
        assertThat(after.getAuthor()).isEqualTo(Ref.of(ann));            // unchecked reference: left dangling
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThatThrownBy(() -> db.update(before)).hasMessageContaining("has version");   // the cleared task changed

        reopen();                                                       // cascades replay from the WAL
        assertThat(db.from(Membership.class).count()).isEqualTo(1);
        assertThat(db.get(Task.class, t1).getWatchers()).containsExactly(Ref.of(bob));

        // SET_NULL inside an embedded object; RESTRICT passes once the referrers are gone in the same batch
        db.delete(Membership.class, m3);
        db.delete(Team.class, blue);
        assertThat(db.get(Task.class, t1).getDesk().getRoom()).isNull();
        assertThat(db.get(Task.class, t1).getDesk().getNumber()).isEqualTo(4);
        try (WriteBatch batch = db.batch()) {
            long m4 = db.insert(new Membership(bob, red, "x"));
            batch.delete(Membership.class, m4).delete(Team.class, red).commit();
        }
        assertThat(db.get(Team.class, red)).isNull();
        assertThat(db.get(Task.class, t2).getDesk().getRoom()).isNull();
        checkpoint();
        assertThat(db.from(Note.class).count()).isZero();                 // m3's note went with m3
        assertThat(db.from(Team.class).count()).isZero();
    }

    @Test
    void cascadesInsideTransactions() {
        long ann = db.insert(new Member("ann", "ann@x.io"));
        long red = db.insert(new Team("red"));
        long m1 = db.insert(new Membership(ann, red, "lead"));
        long task = db.insert(new Task("t").assignee(ann));
        checkpoint();
        try (Transaction tx = db.begin(); Transaction other = db.begin()) {
            tx.delete(Member.class, ann);
            assertThat(tx.get(Membership.class, m1)).isNotNull();          // deferred: applied at commit
            assertThat(tx.referrers(ann)).extracting(DynamicObject::oid).containsExactlyInAnyOrder(m1, task);
            assertThat(tx.oql("SELECT COUNT(*) FROM Membership m").list()).containsExactly(1L);
            Task t = other.get(Task.class, task);
            tx.commit();
            assertThat(db.get(Membership.class, m1)).isNull();
            other.update(t);                                             // the cascade changed the task: conflict
            assertThatThrownBy(other::commit).isInstanceOf(com.github.kookaburra.ConflictException.class);
        }
        try (Transaction tx = db.begin()) {
            tx.delete(Team.class, red);
            tx.insert(new Membership(db.insert(new Member("bob", "bob@x.io")), red, "late"));
            assertThatThrownBy(tx::commit).isInstanceOf(ConstraintViolationException.class);
        }
        assertThat(db.get(Team.class, red)).isNotNull();
    }

    @Test
    void schemaRules() {
        assertThatThrownBy(() -> db.insert(new Bad.UniqueList())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("@Unique on");
        assertThatThrownBy(() -> db.insert(new Bad.KeyOnString())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("@ForeignKey on");
        assertThatThrownBy(() -> db.insert(new Bad.InList())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("inside a list or map of embedded objects");
        db.insert(new Member("ann", "a@x.io"));
        assertThat(db.schema().constraints(Member.class.getName())).extracting(Object::toString)
                .containsExactly("Member.email", "Member.badge", "Member.code", "Member.contact.phone");
        assertThat(db.schema().toString()).contains("email STRING @AUTO UNIQUE");
        db.insert(new Membership(db.from(Member.class).first().orElseThrow().getOid(), db.insert(new Team("t")), "r"));
        assertThat(db.schema().toString()).contains("member REF(Member) @AUTO FOREIGN KEY ON DELETE CASCADE");
    }

    @Test
    void compositeUniqueConstraints() {
        long ann = db.insert(new Member("ann", "ann@x.io"));
        long a1 = db.insert(new Listing("acme", "A-1").seller(ann, "2026-10-17").place("Perth", "1 Main St").price("9.90"));
        db.insert(new Listing("other", "A-1"));                                  // same code, other tenant
        db.insert(new Listing(null, "A-1"));                                     // a null: not compared
        db.insert(new Listing(null, "A-1"));
        db.insert(new Listing("acme", null));
        db.insert(new Listing("acme", null));
        checkpoint();

        assertThatThrownBy(() -> db.insert(new Listing("acme", "A-1")))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessage("duplicate value ('acme', 'A-1') for unique Listing(tenant, code) (already used by object " + a1 + ")")
                .satisfies(t -> {
                    ConstraintViolationException e = (ConstraintViolationException) t;
                    assertThat(e.kind()).isEqualTo(ConstraintViolationException.Kind.UNIQUE);
                    assertThat(e.field()).isEqualTo("tenant, code");
                    assertThat(e.oid()).isEqualTo(a1);
                });
        assertThatThrownBy(() -> db.insert(new Listing("acme", "B-1").seller(ann, "2026-10-17")))
                .hasMessageContaining("for unique one_per_day: Listing(seller, day)");
        db.insert(new Listing("acme", "B-2").seller(ann, "2026-10-18"));         // another day
        assertThatThrownBy(() -> db.insert(new Listing("x", "C-1").place("Perth", "1 Main St")))   // embedded paths
                .hasMessageContaining("Listing(place.city, place.street)");
        db.insert(new Listing("x", "C-2").place("Hobart", "1 Main St"));
        assertThatThrownBy(() -> db.insert(new Listing("acme", "D-1").price("9.9000")))           // decimals by value
                .hasMessageContaining("Listing(tenant, price)");
        assertThatThrownBy(() -> db.insert(new PromoListing("acme", "A-1")))                       // subclasses share it
                .isInstanceOf(ConstraintViolationException.class);

        // update into a duplicate; swap inside one batch; duplicates inside one batch
        Listing other = db.from(Listing.class).where(Listing::getTenant).eq("other").first().orElseThrow();
        assertThatThrownBy(() -> db.update(other.tenant("acme"))).isInstanceOf(ConstraintViolationException.class);
        Listing first = db.get(Listing.class, a1);
        Listing again = db.from(Listing.class).where(Listing::getTenant).eq("other").first().orElseThrow();
        try (WriteBatch batch = db.batch()) {
            batch.update(first.tenant("other")).update(again.tenant("acme")).commit();
        }
        try (WriteBatch batch = db.batch()) {
            batch.insert(new Listing("t", "Z")).insert(new Listing("t", "Z"));
            assertThatThrownBy(batch::commit).isInstanceOf(ConstraintViolationException.class);
        }
        reopen();
        assertThat(db.from(Listing.class).where(Listing::getTenant).eq("t").count()).isZero();

        // transactions: deferred to commit, first committer wins
        try (Transaction t1 = db.begin(); Transaction t2 = db.begin()) {
            t1.insert(new Listing("tx", "T-1"));
            t2.insert(new Listing("tx", "T-1"));
            t1.commit();
            assertThatThrownBy(t2::commit).isInstanceOf(ConstraintViolationException.class);
        }
        // OQL bulk updates and dynamic writes are checked too
        assertThatThrownBy(() -> db.oql("UPDATE Listing l SET l.code = 'A-1' WHERE l.tenant = 'acme'").execute())
                .isInstanceOf(ConstraintViolationException.class);
        DynamicObject dyn = db.dynamic("Listing").newObject();
        dyn.set("tenant", "tx");
        dyn.set("code", "T-1");
        assertThatThrownBy(() -> db.dynamic("Listing").insert(dyn)).isInstanceOf(ConstraintViolationException.class);
        checkpoint();

        // schema: the first field is indexed when none was; constraints are listed
        assertThat(db.schema().toString()).contains("tenant STRING @AUTO", "UNIQUE (tenant, code)",
                "one_per_day UNIQUE (seller, day)", "UNIQUE (place.city, place.street)");
        assertThat(db.schema().compositeUniques()).extracting(Object::toString)
                .containsExactly("Listing(tenant, code)", "one_per_day: Listing(seller, day)",
                        "Listing(place.city, place.street)", "Listing(tenant, price)");
        assertThatThrownBy(() -> db.insert(new Listing("acme", "B-2"))).isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void compositeSchemaRules() {
        assertThatThrownBy(() -> db.insert(new Bad.ClassWithoutFields())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("needs fields = {...}");
        assertThatThrownBy(() -> db.insert(new Bad.UnknownField())).hasMessageContaining("no field 'missing'");
        assertThatThrownBy(() -> db.insert(new Bad.ListInCombination())).hasMessageContaining("scalar fields only");
        assertThatThrownBy(() -> db.insert(new Bad.Twice())).hasMessageContaining("listed twice");
        assertThatThrownBy(() -> db.insert(new Bad.FieldsOnField())).hasMessageContaining("belongs on the class");
        assertThatThrownBy(() -> new UniqueDef("x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
