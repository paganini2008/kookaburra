package com.github.kookaburra.lifecycle;

import com.github.kookaburra.Config;
import com.github.kookaburra.EntityListener;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.model.Audited;
import com.github.kookaburra.model.AuditedChild;
import com.github.kookaburra.model.BadCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle callbacks ({@code @PrePersist} … {@code @PostLoad}) and {@link EntityListener}s: autocommit writes,
 * batches, transactions (post events after commit, nothing on rollback), queries, OQL, inheritance, listener
 * order and removal, exceptions ({@link LifecycleDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class LifecycleTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true);
    }

    /** Disk variant: flush, compact, close and reopen (listeners are registered again by the caller). */
    protected void checkpoint() {
    }

    @BeforeEach
    void open() {
        db = create();
        Audited.EVENTS.clear();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void callbacksOnAutocommitWritesAndLoads() {
        Audited a = new Audited("ann");
        long oid = db.insert(a);
        assertThat(a.getUpper()).isEqualTo("ANN");
        assertThat(Audited.EVENTS).containsExactly("prePersist ann", "postPersist ann oid=true");
        checkpoint();
        Audited loaded = db.get(Audited.class, oid);
        assertThat(loaded.isLoaded()).isTrue();                       // @PostLoad
        assertThat(loaded.getCreated()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));   // stored after @PrePersist
        assertThat(loaded.getUpper()).isEqualTo("ANN");
        assertThat(db.from(Audited.class).list()).allMatch(Audited::isLoaded);
        assertThat(db.oql("SELECT a FROM Audited a", Audited.class).list()).allMatch(Audited::isLoaded);

        Audited.EVENTS.clear();
        db.update(loaded.name("anna"));
        assertThat(Audited.EVENTS).containsExactly("preUpdate anna", "postUpdate anna v2");
        checkpoint();
        Audited updated = db.get(Audited.class, oid);
        assertThat(updated.getModified()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(updated.getUpper()).isEqualTo("ANNA");

        Audited.EVENTS.clear();
        db.delete(updated);
        assertThat(Audited.EVENTS).containsExactly("preRemove anna", "postRemove anna");
        assertThat(db.get(Audited.class, oid)).isNull();
    }

    @Test
    void bulkOqlDeletesByOidAndDynamicObjectsSkipCallbacks() {
        long a = db.insert(new Audited("ann"));
        long b = db.insert(new Audited("bob"));
        db.insert(new Audited("cid"));
        checkpoint();
        Audited.EVENTS.clear();
        assertThat(db.oql("UPDATE Audited a SET a.name = 'x' WHERE a.name = 'ann'").execute()).isEqualTo(1);
        assertThat(db.oql("DELETE FROM Audited a WHERE a.name = 'cid'").execute()).isEqualTo(1);
        assertThat(db.delete(Audited.class, b)).isTrue();
        var o = db.dynamic("Audited").get(a);
        o.set("name", "dyn");
        db.dynamic("Audited").update(o);
        assertThat(Audited.EVENTS).isEmpty();                      // like JPA bulk statements
        assertThat(db.get(Audited.class, a).getUpper()).isEqualTo("ANN");
    }

    @Test
    void batchesAndTransactions() {
        try (WriteBatch batch = db.batch()) {
            batch.insert(new Audited("ann")).insert(new Audited("bob"));
            assertThat(Audited.EVENTS).containsExactly("prePersist ann", "prePersist bob");
            batch.commit();
        }
        assertThat(Audited.EVENTS).containsExactly("prePersist ann", "prePersist bob", "postPersist ann oid=true",
                "postPersist bob oid=true");
        checkpoint();

        Audited.EVENTS.clear();
        Audited bob = db.from(Audited.class).where(Audited::getName).eq("bob").first().orElseThrow();
        try (Transaction tx = db.begin()) {
            tx.insert(new Audited("cid"));
            tx.update(bob.name("bobby"));
            Audited ann = tx.from(Audited.class).where(Audited::getName).eq("ann").first().orElseThrow();
            assertThat(ann.isLoaded()).isTrue();
            tx.delete(ann);
            Audited temp = new Audited("temp");
            tx.insert(temp);
            tx.delete(temp);                                       // inserted and deleted: no post events
            assertThat(Audited.EVENTS).containsExactly("prePersist cid", "preUpdate bobby", "preRemove ann",
                    "prePersist temp", "preRemove temp");
            tx.commit();
        }
        assertThat(Audited.EVENTS).containsSubsequence("postPersist cid oid=true", "postUpdate bobby v2", "postRemove ann")
                .doesNotContain("postPersist temp oid=true", "postRemove temp").hasSize(8);

        Audited.EVENTS.clear();
        try (Transaction tx = db.begin()) {
            tx.insert(new Audited("gone"));
            tx.rollback();
        }
        assertThat(Audited.EVENTS).containsExactly("prePersist gone");   // rolled back: no post event
        checkpoint();
        assertThat(db.from(Audited.class).list()).extracting(Audited::getName).containsExactlyInAnyOrder("bobby", "cid");
    }

    @Test
    void listenersInheritanceAndErrors() {
        List<String> seen = new ArrayList<>();
        EntityListener<Object> all = new EntityListener<>() {
            @Override
            public void prePersist(Object e) {
                seen.add("any:" + e.getClass().getSimpleName());
            }
        };
        EntityListener<Audited> audit = new EntityListener<>() {
            @Override
            public void prePersist(Audited a) {
                seen.add("audit:" + a.getName());
                assertThat(a.getUpper()).isNull();                  // listeners run before the entity's own methods
            }

            @Override
            public void postLoad(Audited a) {
                seen.add("load:" + a.getName());
            }

            @Override
            public void postUpdate(Audited a) {
                seen.add("updated:" + a.getName());
            }

            @Override
            public void preRemove(Audited a) {
                seen.add("remove:" + a.getName());
            }

            @Override
            public void postRemove(Audited a) {
                seen.add("removed:" + a.getName());
            }

            @Override
            public void postPersist(Audited a) {
                seen.add("persisted:" + a.getName());
            }

            @Override
            public void preUpdate(Audited a) {
                seen.add("update:" + a.getName());
            }
        };
        db.addListener(Object.class, all);
        db.addListener(Audited.class, audit);
        AuditedChild c = new AuditedChild("kid");
        long oid = db.insert(c);
        assertThat(seen).containsExactly("any:AuditedChild", "audit:kid", "persisted:kid");
        assertThat(Audited.EVENTS).containsExactly("prePersist kid", "childPrePersist kid", "postPersist kid oid=true");
        assertThat(c.getLevel()).isEqualTo(7);
        checkpoint();
        db.removeListener(Object.class, all);                       // the disk variant reopened the database:
        db.removeListener(Audited.class, audit);                    // register once on whichever instance is open
        db.addListener(Object.class, all);
        db.addListener(Audited.class, audit);
        seen.clear();
        AuditedChild back = db.get(AuditedChild.class, oid);
        assertThat(back.getLevel()).isEqualTo(7);
        db.update(back);
        db.delete(back);
        assertThat(seen).contains("load:kid", "update:kid", "updated:kid", "remove:kid", "removed:kid");
        assertThat(db.removeListener(Audited.class, audit)).isTrue();
        assertThat(db.removeListener(Audited.class, audit)).isFalse();
        seen.clear();
        db.insert(new Audited("quiet"));
        assertThat(seen).containsExactly("any:Audited");

        Audited.EVENTS.clear();
        assertThatThrownBy(() -> db.insert(new Audited("boom"))).hasMessage("rejected by @PrePersist");
        assertThat(db.from(Audited.class).where(Audited::getName).eq("boom").count()).isZero();
        assertThat(Audited.EVENTS).isEmpty();
        assertThatThrownBy(() -> db.addListener(null, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.insert(new BadCallback())).isInstanceOf(SchemaException.class)
                .hasMessageContaining("must be a non-static method without parameters");
        try (Transaction tx = db.begin()) {
            EntityListener<Audited> inTx = new EntityListener<>() {
            };
            // OQL / queries inside a transaction see a facade database: listeners registered there are the database's
            tx.oql("SELECT COUNT(*) FROM Audited a").list();
            db.addListener(Audited.class, inTx);
            assertThat(db.removeListener(Audited.class, inTx)).isTrue();
        }
    }
}
