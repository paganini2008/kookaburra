package com.github.kookaburra.engine;

import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.model.Animal;
import com.github.kookaburra.model.Cat;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.kookaburra.query.Q.instanceOf;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD, optimistic locking, batches, polymorphism, snapshots and concurrency of the in-memory database.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class DatabaseTest {

    private final ObjectDB db = TestDbs.create();

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void insertGetUpdateDelete() {
        Person ann = new Person("Ann", "Sydney", 30);
        long oid = db.insert(ann);
        assertThat(ann.getOid()).isEqualTo(oid);
        assertThat(Oids.classId(oid)).isEqualTo(db.schema().byName(Person.class.getName()).id());
        assertThat(Oids.seq(oid)).isEqualTo(1);

        Person copy = db.get(Person.class, oid);
        assertThat(copy).isNotSameAs(ann);
        assertThat(copy.getName()).isEqualTo("Ann");
        assertThat(db.getAll(Person.class, List.of(oid, oid + 1))).containsOnlyKeys(oid);

        copy.score(5);
        db.update(copy);
        assertThat(db.get(Person.class, oid).getScore()).isEqualTo(5);
        assertThatThrownBy(() -> db.update(ann)).isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("expected 1").extracting(e -> ((OptimisticLockException) e).oid()).isEqualTo(oid);
        assertThatThrownBy(() -> db.delete(ann)).isInstanceOf(OptimisticLockException.class);

        db.delete(copy);
        assertThat(db.get(Person.class, oid)).isNull();
        assertThatThrownBy(() -> db.update(copy)).isInstanceOf(OptimisticLockException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> db.delete(copy)).isInstanceOf(OptimisticLockException.class);
        assertThat(db.delete(Person.class, oid)).isFalse();
        assertThat(db.from(Person.class).count()).isZero();
    }

    @Test
    void rejectsMisuse() {
        Person p = new Person("A", "B", 1);
        db.insert(p);
        assertThatThrownBy(() -> db.insert(p)).hasMessageContaining("already has OID");
        assertThatThrownBy(() -> db.update(new Person())).hasMessageContaining("no OID");
        assertThatThrownBy(() -> db.delete(new Person())).hasMessageContaining("no OID");
        assertThat(db.get(Person.class, 999_999)).isNull();
        assertThat(db.delete(Person.class, Oids.make(1, 12345))).isFalse();
        assertThat(db.path() == null).isEqualTo(!TestDbs.onDisk());
    }

    @Test
    void unversionedEntitiesUpdateUnconditionally() {
        Dog d = new Dog("Rex", 3, "Lab");
        long oid = db.insert(d);
        Dog a = db.get(Dog.class, oid);
        Dog b = db.get(Dog.class, oid);
        db.update(a);
        db.update(b);                          // no @Version → no conflict
        db.delete(b);
        assertThat(db.get(Dog.class, oid)).isNull();
        assertThat(db.delete(Dog.class, oid)).isFalse();
    }

    @Test
    void polymorphism() {
        long dog = db.insert(new Dog("Rex", 3, "Lab"));
        long cat = db.insert(new Cat("Tom", 2, true));
        long animal = db.insert(new Animal("Generic", 9));
        assertThat(db.from(Animal.class).count()).isEqualTo(3);
        assertThat(db.from(Dog.class).toList()).extracting(Animal::getName).containsExactly("Rex");
        assertThat(db.from(Animal.class).filter(instanceOf(Cat.class)).toList()).singleElement().isInstanceOf(Cat.class);
        assertThat(db.get(Animal.class, dog)).isInstanceOf(Dog.class);
        assertThat(db.get(Cat.class, dog)).isNull();
        assertThat(db.get(Dog.class, animal)).isNull();
        assertThat(db.get(Animal.class, cat)).isInstanceOf(Cat.class);
        assertThat(db.from(Animal.class).filter(where(Animal::getName).eq("Rex")).toList()).singleElement().isInstanceOf(Dog.class);
        assertThat(db.from(Dog.class).filter(where(Dog::getBreed).eq("Lab")).count()).isEqualTo(1);
    }

    @Test
    void batchesAreAtomic() {
        Person a = new Person("A", "X", 1);
        Dog d = new Dog("D", 1, "Pug");
        try (WriteBatch b = db.batch()) {
            b.insert(a).insert(d);
            assertThat(b.size()).isEqualTo(2);
            b.commit();
            assertThatThrownBy(b::commit).isInstanceOf(KookaburraException.class);
        }
        assertThat(a.getOid()).isPositive();
        assertThat(db.from(Dog.class).count()).isEqualTo(1);

        Person stale = db.get(Person.class, a.getOid());
        Person fresh = db.get(Person.class, a.getOid());
        db.update(fresh);
        Person b2 = new Person("B", "Y", 2);
        WriteBatch failing = db.batch().insert(b2).update(stale);
        assertThatThrownBy(failing::commit).isInstanceOf(OptimisticLockException.class);
        assertThat(db.from(Person.class).count()).isEqualTo(1);           // insert rolled back
        assertThat(b2.getOid()).isZero();

        Person p = db.get(Person.class, a.getOid());
        WriteBatch twice = db.batch().update(p).update(p);                // same expected version twice
        assertThatThrownBy(twice::commit).isInstanceOf(OptimisticLockException.class);
        db.batch().update(p).delete(Person.class, p.getOid()).commit();  // update then unconditional delete
        assertThat(db.get(Person.class, p.getOid())).isNull();

        WriteBatch closed = db.batch();
        closed.close();
        assertThatThrownBy(() -> closed.insert(new Person())).isInstanceOf(KookaburraException.class);
        db.batch().commit();                                               // empty batch is fine
        try (WriteBatch b = db.batch()) {
            assertThatThrownBy(() -> b.delete(new Dog())).hasMessageContaining("no OID");
        }
    }

    @Test
    void snapshotsAreIsolated() {
        Person a = new Person("A", "X", 1);
        db.insert(a);
        try (Snapshot s = db.snapshot()) {
            Person upd = db.get(Person.class, a.getOid());
            upd.score(99);
            db.update(upd);
            db.insert(new Person("B", "Y", 2));
            Person gone = new Person("C", "Z", 3);
            db.insert(gone);
            db.delete(Person.class, gone.getOid());

            assertThat(s.get(Person.class, a.getOid()).getScore()).isNull();
            assertThat(s.getAll(Person.class, List.of(a.getOid()))).hasSize(1);
            assertThat(s.from(Person.class).count()).isEqualTo(1);
            assertThat(s.from(Person.class).filter(where(Person::getScore).eq(99)).count()).isZero();
            assertThat(s.seq()).isLessThan(db.snapshot().seq());
        }
        assertThat(db.from(Person.class).count()).isEqualTo(2);
        assertThat(db.get(Person.class, a.getOid()).getScore()).isEqualTo(99);
        Snapshot s = db.snapshot();
        s.close();
        s.close();
        assertThatThrownBy(() -> s.from(Person.class)).isInstanceOf(KookaburraException.class);
    }

    @Test
    void historyAcrossManyVersions() {
        Person p = new Person("V", "X", 0);
        db.insert(p);
        List<Snapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            snaps.add(db.snapshot());
            Person cur = db.get(Person.class, p.getOid());
            cur.score(i);
            db.update(cur);
        }
        for (int i = 0; i < 5; i++) {
            Integer expected = i == 0 ? null : i;
            assertThat(snaps.get(i).get(Person.class, p.getOid()).getScore()).isEqualTo(expected);
            assertThat(snaps.get(i).from(Person.class).count()).isEqualTo(1);
            snaps.get(i).close();
        }
    }

    @Test
    void snapshotsSurviveFlushesAndSegmentDeletes() {
        try (ObjectDB small = TestDbs.create(Config.defaults().withMemSegmentBytes(2048))) {
            List<Person> people = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                Person p = new Person("p" + i, "C" + (i % 5), i);
                small.insert(p);
                people.add(p);
            }
            Snapshot before = small.snapshot();
            small.flush();                                     // rows move from MemSegments to segments
            for (int i = 0; i < 200; i += 2) {
                Person cur = small.get(Person.class, people.get(i).getOid());
                cur.score(i);
                small.update(cur);                             // deletes land in segment delete logs
            }
            for (int i = 1; i < 200; i += 10) {
                small.delete(Person.class, people.get(i).getOid());
            }
            Snapshot middle = small.snapshot();
            small.flush();
            small.insert(new Person("late", "C0", 1));

            assertThat(before.from(Person.class).count()).isEqualTo(200);
            assertThat(before.from(Person.class).filter(where(Person::getScore).isNotNull()).count()).isZero();
            assertThat(before.get(Person.class, people.get(1).getOid())).isNotNull();
            assertThat(middle.from(Person.class).count()).isEqualTo(180);
            assertThat(middle.from(Person.class).filter(where(Person::getScore).isNotNull()).count()).isEqualTo(100);
            assertThat(middle.get(Person.class, people.get(1).getOid())).isNull();
            assertThat(middle.get(Person.class, people.get(2).getOid()).getScore()).isEqualTo(2);
            assertThat(small.from(Person.class).count()).isEqualTo(181);
            assertThat(small.from(Person.class).filter(where(Person::getCity).eq("C0")).count()).isEqualTo(41);
            before.close();
            middle.close();
        }
    }

    @Test
    void writesDuringCompactionAreKept() throws Exception {
        Config cfg = Config.defaults().withMemSegmentBytes(4096).withCompactionFanIn(2).withMaxSegments(16);
        try (ObjectDB small = TestDbs.create(cfg)) {
            Map<Long, Integer> model = new HashMap<>();
            Random r = new Random(11);
            for (int i = 0; i < 3000; i++) {
                Person p = new Person("p" + i, "C" + (i % 7), i);
                small.insert(p);
                model.put(p.getOid(), null);
            }
            AtomicBoolean stop = new AtomicBoolean();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> compactor = pool.submit(() -> {
                while (!stop.get()) {
                    small.compact();
                }
            });
            List<Long> oids = new ArrayList<>(model.keySet());
            for (int i = 0; i < 1500; i++) {
                long oid = oids.get(r.nextInt(oids.size()));
                if (!model.containsKey(oid)) {
                    continue;
                }
                if (r.nextInt(4) == 0) {
                    small.delete(Person.class, oid);
                    model.remove(oid);
                } else {
                    Person cur = small.get(Person.class, oid);
                    cur.score(i);
                    small.update(cur);
                    model.put(oid, i);
                }
            }
            stop.set(true);
            compactor.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            small.compact();
            assertThat(small.from(Person.class).count()).isEqualTo(model.size());
            for (var e : model.entrySet()) {
                assertThat(small.get(Person.class, e.getKey()).getScore()).isEqualTo(e.getValue());
            }
            assertThat(small.from(Person.class).filter(where(Person::getScore).isNull()).count())
                    .isEqualTo(model.values().stream().filter(Objects::isNull).count());
        }
    }

    @Test
    void concurrentWritersAndReaders() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicBoolean done = new AtomicBoolean();
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            int id = t;
            writers.add(pool.submit(() -> {
                for (int i = 0; i < 400; i++) {
                    db.insert(new Person("w" + id, "C" + (i % 7), i));
                }
            }));
        }
        Future<?> reader = pool.submit(() -> {
            long last = 0;
            while (!done.get()) {
                try (Snapshot s = db.snapshot()) {
                    long n = s.from(Person.class).count();
                    assertThat(n).isGreaterThanOrEqualTo(last);
                    assertThat(s.from(Person.class).filter(where(Person::getCity).eq("C3")).count()).isLessThanOrEqualTo(n);
                    assertThat(s.from(Person.class).count()).isEqualTo(n);
                    last = n;
                }
            }
        });
        for (Future<?> f : writers) {
            f.get(60, TimeUnit.SECONDS);
        }
        done.set(true);
        reader.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(db.from(Person.class).count()).isEqualTo(2400);
        assertThat(db.from(Person.class).filter(where(Person::getCity).eq("C3")).count()).isEqualTo(6 * 57);
    }

    @Test
    void closedDatabaseRejectsCalls() {
        ObjectDB other = TestDbs.create(Config.defaults().withDurability(Durability.ASYNC));
        other.close();
        other.close();
        assertThatThrownBy(() -> other.insert(new Person())).isInstanceOf(KookaburraException.class);
        assertThatThrownBy(() -> other.from(Person.class)).isInstanceOf(KookaburraException.class);
        assertThatThrownBy(other::batch).isInstanceOf(KookaburraException.class);
        assertThatThrownBy(other::snapshot).isInstanceOf(KookaburraException.class);
    }

    @Test
    void configAndOids() {
        Config c = Config.defaults().withMemSegmentBytes(1 << 20).withSegmentMaxBytes(1 << 30).withCompactionFanIn(4)
                .withMaxSegments(32).withGroupCommitMicros(0).withClassLoader(getClass().getClassLoader());
        assertThat(c.compactionFanIn()).isEqualTo(4);
        assertThatThrownBy(() -> Config.defaults().withMemSegmentBytes(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Config.defaults().withCompactionFanIn(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Oids.make(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Oids.make(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Oids.make(1, Oids.MAX_SEQ + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Oids.seq(Oids.make(7, 99))).isEqualTo(99);
        assertThat(new KookaburraException("x", new RuntimeException()).getCause()).isNotNull();
    }

    @Test
    void chunksGrowAcrossChunkBoundaries() {
        Chunks.Longs l = new Chunks.Longs(-1);
        Chunks.Ints in = new Chunks.Ints();
        Chunks.Refs<String> refs = new Chunks.Refs<>();
        Chunks.IntList list = new Chunks.IntList();
        assertThat(list.last()).isEqualTo(-1);
        for (int i = 0; i < Chunks.SIZE * 2 + 5; i++) {
            l.set(i, i);
            in.set(i, -i);
            refs.set(i, "v" + i);
            list.add(i);
        }
        Chunks.Longs fresh = new Chunks.Longs(-1);
        fresh.set(Chunks.SIZE + 1, 5);
        assertThat(fresh.get(0)).isEqualTo(-1);
        assertThat(l.get(Chunks.SIZE * 2 + 4)).isEqualTo(Chunks.SIZE * 2 + 4);
        assertThat(in.get(Chunks.SIZE)).isEqualTo(-Chunks.SIZE);
        assertThat(refs.get(Chunks.SIZE + 3)).isEqualTo("v" + (Chunks.SIZE + 3));
        RoaringBitmap bm = new RoaringBitmap();
        list.addTo(bm, 10);
        assertThat(bm.getCardinality()).isEqualTo(10);
        assertThat(list.last()).isEqualTo(Chunks.SIZE * 2 + 4);
    }
}
