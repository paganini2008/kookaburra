package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Durability per transaction and per batch (as PostgreSQL's per-transaction {@code synchronous_commit}): an ASYNC
 * commit in a SYNC database does not force the WAL, a SYNC one in an ASYNC database does; everything committed is
 * there after a clean reopen.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class DurabilityTest {

    @TempDir
    Path dir;

    private static long forces(ObjectDB db) {
        return ((Database) db).walForces();
    }

    @Test
    void syncDatabase() {
        Config config = Config.defaults().withTransactions(true).withDurability(Durability.SYNC);
        try (ObjectDB db = ObjectDB.open(dir.resolve("sync"), config)) {
            long f = forces(db);
            db.insert(new Person("a", "Perth", 1));                                   // the database's SYNC
            assertThat(forces(db)).isEqualTo(f + 1);
            db.batch().durability(Durability.ASYNC).insert(new Person("b", "Perth", 2)).commit();
            assertThat(forces(db)).isEqualTo(f + 1);                                 // no fsync for this batch
            db.inTransaction(TxOptions.defaults().durability(Durability.ASYNC), tx -> tx.insert(new Person("c", "Perth", 3)));
            assertThat(forces(db)).isEqualTo(f + 1);
            db.inTransaction(tx -> tx.insert(new Person("d", "Perth", 4)));
            assertThat(forces(db)).isEqualTo(f + 2);
            try (WriteBatch b = db.batch()) {
                b.durability(null).insert(new Person("e", "Perth", 5)).commit();        // null = the database's
            }
            assertThat(forces(db)).isEqualTo(f + 3);
        }
        try (ObjectDB db = ObjectDB.open(dir.resolve("sync"), config)) {
            assertThat(db.from(Person.class).count()).isEqualTo(5);                  // all there after a clean close
        }
    }

    @Test
    void asyncDatabase() {
        Config config = Config.defaults().withTransactions(true).withDurability(Durability.ASYNC);
        try (ObjectDB db = ObjectDB.open(dir.resolve("async"), config)) {
            long f = forces(db);
            db.insert(new Person("a", "Perth", 1));
            assertThat(forces(db)).isEqualTo(f);
            db.batch().durability(Durability.SYNC).insert(new Person("b", "Perth", 2)).commit();
            assertThat(forces(db)).isEqualTo(f + 1);
            try (Transaction tx = db.begin(TxOptions.defaults().durability(Durability.SYNC))) {
                tx.insert(new Person("c", "Perth", 3));
                assertThat(tx.toString()).isNotNull();
                tx.commit();
            }
            assertThat(forces(db)).isEqualTo(f + 2);
            assertThat(TxOptions.defaults().durability(Durability.SYNC).toString()).endsWith(" SYNC");
            assertThat(TxOptions.defaults().durability(Durability.ASYNC).durability()).isEqualTo(Durability.ASYNC);
            assertThat(TxOptions.defaults().durability()).isNull();
            assertThat(TxOptions.defaults().isolation(Isolation.SERIALIZABLE).durability(Durability.SYNC)
                    .timeout(Duration.ofSeconds(5)).readOnly(false).durability()).isEqualTo(Durability.SYNC);

            try (Transaction tx = db.begin()) {
                assertThatThrownBy(() -> ((TransactionImpl) tx).database().batch().durability(Durability.SYNC)).isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("commits with its transaction");
            }
            WriteBatch done = db.batch();
            done.commit();
            assertThatThrownBy(() -> done.durability(Durability.SYNC)).hasMessageContaining("already committed");
        }
    }

    @Test
    void inMemoryHasNoWal() {
        try (ObjectDB db = ObjectDB.inMemory(Config.defaults())) {
            db.batch().durability(Durability.SYNC).insert(new Person("a", "Perth", 1)).commit();
            assertThat(forces(db)).isZero();
            assertThat(db.from(Person.class).count()).isEqualTo(1);
        }
    }
}
