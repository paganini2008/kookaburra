package com.github.kookaburra.bench;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.query.Q;
import com.github.kookaburra.query.QueryStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.kookaburra.query.Q.where;

/**
 * Load and stress test: bulk load, durable single writes, point reads, indexed queries, aggregation, OQL,
 * a mixed read/write phase, compaction and crash-free reopen. Prints a Markdown report with targets.
 *
 * <pre>java -Xmx8g -jar kookaburra-bench.jar --n=10000000 --threads=16 --seconds=20 --dir=target/bench-db [--keep]</pre>
 * The database directory is deleted after the run unless {@code --keep} is given.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Bench {

    /** Run parameters. */
    public record Options(long n, Path dir, int threads, int seconds, int iterations, Path out, boolean keep) {

        public static Options parse(String... args) {
            long n = 1_000_000;
            Path dir = Path.of("target", "bench-db");
            int threads = 16;
            int seconds = 10;
            int iterations = 30;
            Path out = Path.of("target", "bench-results.md");
            boolean keep = false;
            for (String a : args) {
                String[] kv = a.replaceFirst("^--", "").split("=", 2);
                String v = kv.length > 1 ? kv[1] : "";
                switch (kv[0]) {
                    case "n" -> n = Long.parseLong(v.replace("_", ""));
                    case "dir" -> dir = Path.of(v);
                    case "threads" -> threads = Integer.parseInt(v);
                    case "seconds" -> seconds = Integer.parseInt(v);
                    case "iterations" -> iterations = Integer.parseInt(v);
                    case "out" -> out = Path.of(v);
                    case "keep" -> keep = true;
                    default -> throw new IllegalArgumentException("unknown option: " + a);
                }
            }
            return new Options(n, dir, threads, seconds, iterations, out, keep);
        }
    }

    /** One line of the report. */
    public record Result(String section, String metric, String value, String target, Boolean met) {

        String row() {
            String verdict = met == null ? "" : met ? "✅" : "❌";
            return "| " + section + " | " + metric + " | " + value + " | " + target + " | " + verdict + " |";
        }
    }

    /** Time budget per measured query (warm-up included); at least 3 measured runs are always taken. */
    static final long MAX_NANOS_PER_QUERY = 20_000_000_000L;

    private final Options opt;
    private final List<Result> results = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private long expected;
    private volatile long sink;

    public Bench(Options opt) {
        this.opt = opt;
    }

    public static void main(String[] args) {
        Bench bench = new Bench(Options.parse(args));
        String report = bench.run();
        System.out.println(report);
    }

    public List<Result> results() {
        return results;
    }

    public List<String> problems() {
        return problems;
    }

    /** Runs every phase on a fresh database directory and returns (and writes) the Markdown report. */
    public String run() {
        delete(opt.dir);
        load();
        ObjectDB db = timedOpen("reopen after bulk load (segments only)");
        try {
            storage(db, "after load");
            pointReads(db, "segment");
            queries(db, "segments");
            fsync();
            durableWrites(db);
            pointReads(db, "MemSegment + segments");
            mixed(db);
            check(db, "after mixed phase");
            transactions(db);
            check(db, "after transactions");
            long t = System.nanoTime();
            db.compact();
            record("Maintenance", "compact() all collections", Stats.format(System.nanoTime() - t), "", null);
            storage(db, "after compaction");
            queries(db, "after compaction");
            check(db, "after compaction");
        } finally {
            db.close();
        }
        db = timedOpen("reopen (no WAL to replay)");
        try {
            check(db, "after reopen");
            heap(db);
            deleteEverything(db);
        } finally {
            db.close();
        }
        if (!opt.keep) {
            delete(opt.dir);                           // test data is not needed after the report
        }
        String report = report();
        io(() -> {
            Files.createDirectories(opt.out.toAbsolutePath().getParent());
            Files.writeString(opt.out, report);
        });
        return report;
    }

    // ------------------------------------------------------------------ phases

    private void load() {
        Config async = Config.defaults().withDurability(Durability.ASYNC);
        Random r = new Random(42);
        long t = System.nanoTime();
        try (ObjectDB db = ObjectDB.open(opt.dir, async)) {
            WriteBatch b = db.batch();
            for (long i = 0; i < opt.n; i++) {
                b.insert(Member.random(r, i));
                if (b.size() == 100) {
                    b.commit();
                    b = db.batch();
                }
            }
            if (b.size() > 0) {
                b.commit();
            }
            double secs = (System.nanoTime() - t) / 1e9;
            double rate = opt.n / secs;
            record("Write", "bulk insert, batches of 100, ASYNC (1 thread)", rate(rate) + " (" + String.format(Locale.ROOT, "%.1f s", secs) + ")",
                    "≥ 300 k/s", rate >= 300_000);
            long f = System.nanoTime();
            db.flush();
            record("Write", "flush() remaining MemSegments", Stats.format(System.nanoTime() - f), "", null);
            expected = opt.n;
        }
    }

    private ObjectDB timedOpen(String label) {
        long t = System.nanoTime();
        ObjectDB db = ObjectDB.open(opt.dir, Config.defaults().withTransactions(true));
        record("Recovery", label, Stats.format(System.nanoTime() - t), "", null);
        return db;
    }

    /** Latency of one 4 KiB write + force(): the floor of a SYNC commit on this disk. */
    private void fsync() {
        Path f = opt.dir.resolve("fsync.probe");
        Stats s = new Stats();
        io(() -> {
            try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.allocate(4096);
                for (int i = 0; i < 200; i++) {
                    b.clear();
                    long t = System.nanoTime();
                    ch.write(b, 0);
                    ch.force(false);
                    s.add(System.nanoTime() - t);
                }
            }
            Files.delete(f);
        });
        double perSecond = 1e9 / Math.max(1, s.percentile(50));
        record("Write", "disk fsync (4 KiB write + force) p50 / p99", s.summary(), "", null);
        record("Write", "SYNC ceiling ≈ concurrent writers × " + rate(perSecond) + " (1 fsync per group)", "", "", null);
    }

    private void durableWrites(ObjectDB db) {
        for (int threads : new int[]{opt.threads, opt.threads * 8}) {
            AtomicLong done = new AtomicLong();
            Stats lat = runThreads(threads, opt.seconds, rnd -> {
                Member m = Member.random(rnd, -1);
                db.insert(m);
                done.incrementAndGet();
            });
            double rate = done.get() / (double) opt.seconds;
            expected += done.get();
            record("Write", "single insert, SYNC, " + threads + " threads (group commit)", rate(rate), "", null);
            record("Write", "single insert latency p50 / p99, " + threads + " threads", lat.summary(), "", null);
        }
    }

    private void pointReads(ObjectDB db, String where) {
        List<Long> oids = sampleOids(db, 10_000, where.startsWith("MemSegment"));
        Stats s = new Stats();
        for (int round = 0; round < 3; round++) {
            for (long oid : oids) {
                long t = System.nanoTime();
                Member m = db.get(Member.class, oid);
                long e = System.nanoTime() - t;
                if (round == 2) {
                    s.add(e);
                }
                if (m == null) {
                    problem("get(" + oid + ") returned null (" + where + ")");
                }
            }
        }
        boolean mem = where.startsWith("MemSegment");
        long p50 = s.percentile(50);
        record("Read", "get(oid) from " + where + " p50 / p99", s.summary(), mem ? "≤ 2 µs" : "≤ 10 µs",
                p50 <= (mem ? 2_000 : 10_000));
    }

    private List<Long> sampleOids(ObjectDB db, int count, boolean newest) {
        List<Long> all = new ArrayList<>(count);
        QueryStream<Member> q = db.from(Member.class);
        if (newest) {
            q = q.filter(where(Member::getName).eq("member--1"));
        } else {
            q = q.filter(where(Member::getCity).eq(Member.CITIES[7]));
        }
        q.limit(count).forEach(m -> all.add(m.oid));
        return all;
    }

    private void queries(ObjectDB db, String label) {
        String city = Member.CITIES[3];
        long total = db.from(Member.class).count();
        bench("Query", "count() all [" + label + "]", null, 0, () -> db.from(Member.class).count());
        bench("Query", "count(city = c) bitmap [" + label + "]", "≤ 5 ms", 5,
                () -> db.from(Member.class).filter(where(Member::getCity).eq(city)).count());
        bench("Query", "city = c AND age BETWEEN 30 AND 40, limit 100 [" + label + "]", "≤ 20 ms", 20,
                () -> db.from(Member.class).filter(where(Member::getCity).eq(city))
                        .filter(where(Member::getAge).between(30, 40)).limit(100).toList().size());
        long matches = db.from(Member.class).filter(where(Member::getTags).contains("vip")).count();
        bench("Query", "top-10 by age DESC over " + (matches * 100 / Math.max(1, total)) + "% matches (tags ∋ 'vip') [" + label + "]",
                "≤ 50 ms / 1 M", 50.0 * Math.max(1, matches) / 1_000_000,
                () -> db.from(Member.class).filter(where(Member::getTags).contains("vip"))
                        .sorted(where(Member::getAge).desc()).limit(10).toList().size());
        bench("Query", "GROUP BY city COUNT(*) [" + label + "]", "≤ 100 ms / 10 M", 100.0 * total / 10_000_000,
                () -> db.from(Member.class).groupBy(where(Member::getCity)).agg(Q.count().as("n")).toList().size());
        bench("Query", "tags CONTAINS 'vip' AND active, count [" + label + "]", null, 0,
                () -> db.from(Member.class).filter(where(Member::getTags).contains("vip"))
                        .filter(where(Member::isActive).isTrue()).count());
        bench("Query", "unindexed score > x, count (row scan) [" + label + "]", null, 0,
                () -> db.from(Member.class).filter(where(Member::getScore).gt(900_000)).count());
        bench("Query", "OQL GROUP BY city, AVG(age) WHERE age > 40 [" + label + "]", null, 0,
                () -> db.oql("SELECT m.city, COUNT(*), AVG(m.age) FROM Member m WHERE m.age > 40 "
                        + "GROUP BY m.city ORDER BY 2 DESC LIMIT 5").list().size());
        bench("Query", "OQL SELECT m WHERE name = '…' [" + label + "]", null, 0,
                () -> db.oql("SELECT m FROM Member m WHERE m.name = 'member-12345'").list().size());
    }

    /** Transactions (docs/transaction-design.md §18): transfers, a hot counter, queries with own writes. */
    private void transactions(ObjectDB db) {
        // engine cost of transactions without fsync: an in-memory database with 1 000 accounts
        try (ObjectDB mem = ObjectDB.inMemory(Config.defaults().withTransactions(true))) {
            int accounts = 1_000;
            long[] oids = new long[accounts];
            for (int i = 0; i < accounts; i++) {
                Member m = Member.random(new Random(i), i);
                m.setScore(1_000);
                oids[i] = mem.insert(m);
            }
            AtomicLong commits = new AtomicLong();
            AtomicLong runs = new AtomicLong();
            Stats lat = runThreads(opt.threads, opt.seconds, rnd -> {
                long from = oids[rnd.nextInt(accounts)];
                long to = oids[rnd.nextInt(accounts)];
                mem.inTransaction(tx -> {
                    runs.incrementAndGet();
                    Member a = tx.get(Member.class, from);
                    Member b = tx.get(Member.class, to);
                    if (from != to && a.getScore() > 0) {
                        a.setScore(a.getScore() - 1);
                        b.setScore(b.getScore() + 1);
                        tx.update(a);
                        tx.update(b);
                    }
                }, 1_000);
                commits.incrementAndGet();
            });
            long total = mem.from(Member.class).sum(Q.where(Member::getScore));
            if (total != 1_000L * accounts) {
                problem("transfers changed the total: " + total);
            }
            double rate = commits.get() / (double) opt.seconds;
            record("Transactions", "transfer (read 2, update 2), " + opt.threads + " threads, in memory", rate(rate),
                    "≥ 50 k/s", rate >= 50_000);
            record("Transactions", "transfer latency p50 / p99 (incl. retries)", lat.summary(), "", null);
            record("Transactions", "conflict retries per commit", String.format(Locale.ROOT, "%.3f",
                    (runs.get() - commits.get()) / (double) Math.max(1, commits.get())), "", null);

            long counter = oids[0];
            int before = mem.get(Member.class, counter).getAge();
            AtomicLong incs = new AtomicLong();
            runThreads(opt.threads, Math.max(1, opt.seconds / 2), rnd -> {
                mem.increment(Member.class, counter, "age", 1);
                incs.incrementAndGet();
            });
            int after = mem.get(Member.class, counter).getAge();
            if (after - before != incs.get()) {
                problem("lost increments: " + incs.get() + " applied, counter moved " + (after - before));
            }
            record("Transactions", "hot counter increment, " + opt.threads + " threads (never conflicts)",
                    rate(incs.get() / (double) Math.max(1, opt.seconds / 2)), "no lost updates", after - before == incs.get());
        }

        // a query inside a transaction that changed 100 objects, on the full data set
        String city = Member.CITIES[5];
        bench("Transactions", "count(city = c) outside a transaction", null, 0,
                () -> db.query(Member.class).where(Member::getCity).eq(city).count());
        try (Transaction tx = db.begin()) {
            for (Member m : db.query(Member.class).where(Member::getCity).eq(city).limit(100).list()) {
                m.setScore(m.getScore() + 1);
                tx.update(m);
            }
            bench("Transactions", "count(city = c) in a transaction with 100 own updates", null, 0,
                    () -> tx.query(Member.class).where(Member::getCity).eq(city).count());
            tx.rollback();
        }
    }

    private void mixed(ObjectDB db) {
        AtomicLong reads = new AtomicLong();
        AtomicLong writes = new AtomicLong();
        AtomicLong conflicts = new AtomicLong();
        AtomicLong inserted = new AtomicLong();
        List<Long> oids = sampleOids(db, 50_000, false);
        int writers = Math.max(1, opt.threads / 4);
        int readers = Math.max(1, opt.threads - writers);
        Stats readLat = new Stats();
        Stats writeLat = new Stats();
        Thread w = new Thread(() -> writeLat.addAll(runThreads(writers, opt.seconds, rnd -> {
            if (rnd.nextInt(10) == 0) {
                db.insert(Member.random(rnd, -2));
                inserted.incrementAndGet();
            } else {
                Member m = db.get(Member.class, oids.get(rnd.nextInt(oids.size())));
                m.setScore(m.getScore() + 1);
                try {
                    db.update(m);
                } catch (OptimisticLockException e) {
                    conflicts.incrementAndGet();
                }
            }
            writes.incrementAndGet();
        })));
        w.start();
        readLat.addAll(runThreads(readers, opt.seconds, rnd -> {
            String city = Member.CITIES[rnd.nextInt(Member.CITIES.length)];
            switch (rnd.nextInt(4)) {
                case 0 -> sink += db.get(Member.class, oids.get(rnd.nextInt(oids.size()))).age;
                case 1 -> sink += db.from(Member.class).filter(where(Member::getCity).eq(city)).count();
                case 2 -> sink += db.from(Member.class).filter(where(Member::getCity).eq(city))
                        .filter(where(Member::getAge).between(30, 35)).limit(20).toList().size();
                default -> sink += db.from(Member.class).filter(where(Member::getCity).eq(city))
                        .sorted(where(Member::getScore).desc()).limit(10).toList().size();
            }
            reads.incrementAndGet();
        }));
        quietly(w::join);
        expected += inserted.get();
        String mix = readers + " readers + " + writers + " writers, " + opt.seconds + " s";
        record("Mixed", "reads/s (" + mix + ")", rate(reads.get() / (double) opt.seconds), "", null);
        record("Mixed", "read latency p50 / p99", readLat.summary(), "", null);
        record("Mixed", "writes/s (90 % update, 10 % insert, SYNC)", rate(writes.get() / (double) opt.seconds), "", null);
        record("Mixed", "write latency p50 / p99", writeLat.summary(), "", null);
        record("Mixed", "optimistic-lock conflicts", Long.toString(conflicts.get()), "", null);
    }

    private void storage(ObjectDB db, String when) {
        long[] sizes = new long[3];
        io(() -> {
            try (Stream<Path> files = Files.walk(opt.dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    long size = p.toFile().length();      // 0 if removed concurrently (WAL rotation, compaction)
                    sizes[0] += size;
                    if (name.endsWith(".odbf")) {
                        sizes[1]++;
                    } else if (name.endsWith(".log")) {
                        sizes[2] += size;
                    }
                });
            }
        });
        long count = db.from(Member.class).count();
        record("Storage", "disk size " + when, mib(sizes[0]) + (count == 0 ? " (no objects)" : " (" + Math.round(sizes[0] / (double) count) + " B/object)"), "", null);
        if (count == 0 && sizes[1] > 0) {
            problem("storage " + when + ": " + sizes[1] + " segment file(s) left without any object (space not reclaimed)");
        }
        record("Storage", "segments " + when, Long.toString(sizes[1]), "", null);
        record("Storage", "WAL size " + when, mib(sizes[2]), "", null);
    }

    /** Cleans up through the API (the delete path under load): OQL DELETE per city, then compaction. */
    private void deleteEverything(ObjectDB db) {
        long before = db.query(Member.class).count();
        long t = System.nanoTime();
        long deleted = 0;
        for (String city : Member.CITIES) {
            deleted += db.oql("DELETE FROM Member m WHERE m.city = :c").param("c", city).execute();
        }
        double secs = (System.nanoTime() - t) / 1e9;
        long left = db.query(Member.class).count();
        if (deleted != before || left != 0) {
            problem("delete: " + before + " objects, " + deleted + " deleted, " + left + " left");
        }
        record("Delete", "OQL DELETE of all " + String.format(Locale.ROOT, "%,d", before) + " objects, 50 statements (SYNC)",
                rate(deleted / Math.max(secs, 1e-9)) + " (" + String.format(Locale.ROOT, "%.1f s", secs) + ")", "", null);
        long c = System.nanoTime();
        db.compact();
        record("Delete", "compact() after deleting everything", Stats.format(System.nanoTime() - c), "", null);
        storage(db, "after deleting everything");
        expected = 0;
        check(db, "after deleting everything");
    }

    private void heap(ObjectDB db) {
        db.from(Member.class).count();
        System.gc();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        record("Memory", "heap used after reopen + GC", mib(used), "", null);
        record("Memory", "max heap", mib(rt.maxMemory()), "", null);
        record("Memory", "live threads", Integer.toString(ManagementFactory.getThreadMXBean().getThreadCount()), "", null);
    }

    private void check(ObjectDB db, String when) {
        long count = db.from(Member.class).count();
        long scanned = db.from(Member.class).filter(m -> true).count();
        long viaOql = ((Number) db.oql("SELECT COUNT(*) FROM Member m").list().get(0)).longValue();
        if (count != expected || scanned != expected || viaOql != expected) {
            problem("count mismatch " + when + ": expected " + expected + ", count=" + count + ", scan=" + scanned + ", oql=" + viaOql);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A worker body; gets the thread's random source. */
    interface Op {
        void run(ThreadLocalRandom rnd) throws Exception;
    }

    Stats runThreads(int threads, int seconds, Op op) {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        List<Stats> stats = new ArrayList<>();
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Stats s = new Stats();
            stats.add(s);
            Thread t = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                quietly(start::await);
                while (!stop.get()) {
                    long b = System.nanoTime();
                    try {
                        op.run(rnd);
                    } catch (Exception | AssertionError e) {
                        problem("worker error: " + e);
                        return;
                    }
                    s.add(System.nanoTime() - b);
                }
            }, "bench-" + i);
            pool.add(t);
            t.start();
        }
        start.countDown();
        quietly(() -> Thread.sleep(seconds * 1000L));
        stop.set(true);
        pool.forEach(t -> quietly(t::join));
        Stats all = new Stats();
        stats.forEach(all::addAll);
        return all;
    }

    private void bench(String section, String metric, String target, double targetMs, Supplier<Object> op) {
        int warmup = Math.max(3, opt.iterations / 3);
        Stats s = new Stats();
        long budget = System.nanoTime() + MAX_NANOS_PER_QUERY;
        for (int i = 0; i < warmup + opt.iterations; i++) {
            if (i >= warmup + 3 && System.nanoTime() > budget) {
                break;                                 // slow query: enough samples within the time budget
            }
            long t = System.nanoTime();
            Object v = op.get();
            long e = System.nanoTime() - t;
            sink += v.hashCode();
            if (i >= warmup) {
                s.add(e);
            }
        }
        Boolean met = target == null ? null : s.percentile(50) <= targetMs * 1e6;
        record(section, metric + " p50 / p99", s.summary(), target == null ? "" : target, met);
    }

    private synchronized void record(String section, String metric, String value, String target, Boolean met) {
        results.add(new Result(section, metric, value, target, met));
    }

    synchronized void problem(String text) {
        if (problems.size() < 50) {
            problems.add(text);
        }
    }

    private String report() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        sb.append("# Kookaburra benchmark\n\n");
        sb.append("| Setting | Value |\n|---|---|\n");
        sb.append("| objects | ").append(String.format(Locale.ROOT, "%,d", opt.n)).append(" |\n");
        sb.append("| threads / phase seconds | ").append(opt.threads).append(" / ").append(opt.seconds).append(" |\n");
        sb.append("| JVM | ").append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version")).append(" |\n");
        sb.append("| OS / CPUs / max heap | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(" / ").append(rt.availableProcessors()).append(" / ").append(mib(rt.maxMemory())).append(" |\n\n");
        sb.append("| Section | Metric | Result | Target | Met |\n|---|---|---|---|---|\n");
        results.stream().sorted(Comparator.comparingInt(r -> order(r.section))).forEach(r -> sb.append(r.row()).append('\n'));
        sb.append("\n**Problems**: ").append(problems.isEmpty() ? "none" : "").append('\n');
        problems.forEach(p -> sb.append("- ").append(p).append('\n'));
        return sb.toString();
    }

    private static int order(String section) {
        return List.of("Write", "Read", "Query", "Mixed", "Transactions", "Maintenance", "Recovery", "Storage", "Memory", "Delete").indexOf(section);
    }

    private static String rate(double perSecond) {
        return perSecond >= 1_000_000 ? String.format(Locale.ROOT, "%.2f M/s", perSecond / 1e6)
                : String.format(Locale.ROOT, "%.1f k/s", perSecond / 1e3);
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    static void delete(Path dir) {
        if (Files.exists(dir)) {
            io(() -> {
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            });
        }
    }

    /** An I/O step; failures become {@link UncheckedIOException}. */
    interface Io {
        void run() throws IOException;
    }

    static void io(Io step) {
        try {
            step.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A blocking step; an interrupt ends it and keeps the thread's interrupt flag. */
    interface Blocking {
        void run() throws InterruptedException;
    }

    static void quietly(Blocking step) {
        try {
            step.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
