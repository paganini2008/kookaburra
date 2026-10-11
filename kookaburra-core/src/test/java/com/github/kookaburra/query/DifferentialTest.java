package com.github.kookaburra.query;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.engine.Database;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.field.ComparableField;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.segment.Segment;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.kookaburra.query.Q.and;
import static com.github.kookaburra.query.Q.or;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random data + random queries: the database must agree with {@code List.stream()} (design §11).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DifferentialTest {

    private static final String[] CITIES = {"Sydney", "Perth", "Darwin", "Hobart", null, "Ärhus", "😀Emoji"};
    private static final String[] NAMES = {"Ann", "Bob", "Cid", "Dee", "Eve", "Fay", "Gus", "Hal", null, "Annie"};
    private static final String[] TAGS = {"vip", "new", "old", "x"};
    private static final double[] WEIGHTS = {-0.0, 0.0, Double.NaN, 1.5, -2.0, 70.25, Double.POSITIVE_INFINITY};

    private ObjectDB db = ObjectDB.inMemory();
    private final Map<Long, Person> model = new LinkedHashMap<>();

    @AfterEach
    void close() {
        db.close();
    }

    /** {@code memBytes} 4 KB forces a flush every few dozen rows: queries span MemSegments and segments. */
    @ParameterizedTest(name = "memSegmentBytes={0}")
    @ValueSource(longs = {64L << 20, 4096})
    void randomQueriesAgreeWithJavaStreams(long memBytes) {
        db.close();
        Config cfg = Config.defaults().withMemSegmentBytes(memBytes);
        db = ObjectDB.inMemory(memBytes < 65536 ? cfg.withCompactionFanIn(2).withMaxSegments(16) : cfg);
        for (long seed = 1; seed <= 4; seed++) {
            Random r = new Random(seed);
            for (int i = 0; i < 300; i++) {
                Person p = randomPerson(r);
                db.insert(p);
                model.put(p.getOid(), p);
            }
            check(r, 150);
            mutate(r);
            check(r, 150);
            db.flush();
            List<SegmentView> views = ((Database) db).views(Person.class.getName());
            assertThat(views).allMatch(v -> v instanceof Segment);
            if (memBytes == 4096) {
                assertThat(views.size()).as("tiered compaction keeps the segment count low").isBetween(2, 30);
            }
            check(r, 100);
            if (seed % 2 == 0) {
                db.compact();
                assertThat(((Database) db).views(Person.class.getName())).hasSize(1);
                check(r, 50);
            }
            mutate(r);
            check(r, 100);
        }
    }

    private void mutate(Random r) {
        List<Long> oids = new ArrayList<>(model.keySet());
        for (int i = 0; i < 60; i++) {
            long oid = oids.get(r.nextInt(oids.size()));
            Person p = model.get(oid);
            if (p == null) {
                continue;
            }
            if (r.nextInt(4) == 0) {
                db.delete(p);
                model.remove(oid);
            } else {
                Person fresh = randomPerson(r);
                copyInto(fresh, p);
                db.update(p);
            }
        }
        for (int i = 0; i < 40; i++) {
            Person p = randomPerson(r);
            db.insert(p);
            model.put(p.getOid(), p);
        }
    }

    private static void copyInto(Person from, Person to) {
        for (Field f : Person.class.getDeclaredFields()) {
            if (f.getName().equals("oid") || f.getName().equals("version") || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(to, f.get(from));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void check(Random r, int queries) {
        assertThat(db.from(Person.class).count()).isEqualTo(model.size());
        int nonEmpty = 0;
        int partial = 0;
        for (int q = 0; q < queries; q++) {
            Pred<Person> pred = randomPred(r, 3);
            List<Person> expectedAll = model.values().stream().filter(pred).toList();
            nonEmpty += expectedAll.isEmpty() ? 0 : 1;
            partial += !expectedAll.isEmpty() && expectedAll.size() < model.size() ? 1 : 0;
            String ctx = "query #" + q + ": " + pred;

            QueryStream<Person> stream = db.from(Person.class).filter(pred);
            assertThat(oids(stream.toList())).as(ctx).isEqualTo(oids(expectedAll));
            assertThat(stream.count()).as(ctx).isEqualTo(expectedAll.size());
            assertThat(stream.exists()).as(ctx).isEqualTo(!expectedAll.isEmpty());

            Order<Person> order = randomOrder(r);
            long skip = r.nextInt(3) == 0 ? r.nextInt(20) : 0;
            long limit = r.nextBoolean() ? r.nextInt(30) : -1;
            QueryStream<Person> sorted = stream.sorted(order);
            if (skip > 0) {
                sorted = sorted.skip(skip);
            }
            if (limit >= 0) {
                sorted = sorted.limit(limit);
            }
            List<Person> got = sorted.toList();
            Stream<Person> es = expectedAll.stream().sorted(order).skip(skip);
            List<Person> expected = (limit >= 0 ? es.limit(limit) : es).toList();
            String ctx2 = ctx + " ORDER BY " + order + " skip " + skip + " limit " + limit;
            assertThat(keys(got, order)).as(ctx2).isEqualTo(keys(expected, order));
            assertThat(got).as(ctx2).allMatch(pred);
            assertThat(sorted.count()).as(ctx2).isEqualTo(expected.size());

            // projections read canonical columns / records
            assertThat(stream.map(where(Person::getCity)).toList()).as(ctx)
                    .containsExactlyInAnyOrderElementsOf(expectedAll.stream().map(Person::getCity).toList());
            assertThat(stream.map(where(Person::getLevel)).distinct().toList()).as(ctx)
                    .containsExactlyInAnyOrderElementsOf(expectedAll.stream().map(Person::getLevel).distinct().toList());
        }
        // guard against a vacuous test: most queries must select a proper, non-empty subset
        assertThat(nonEmpty).isGreaterThan(queries / 2);
        assertThat(partial).isGreaterThan(queries / 3);
        checkGroups(r);
    }

    private void checkGroups(Random r) {
        Pred<Person> pred = randomPred(r, 2);
        List<Person> all = model.values().stream().filter(pred).toList();
        List<Row> rows = db.from(Person.class).filter(pred)
                .groupBy(where(Person::getCity))
                .agg(Q.count(), Q.avg(where(Person::getAge)), Q.min(where(Person::getAge)), Q.max(where(Person::getName)),
                        Q.sum(where(Person::getScore)), Q.countDistinct(where(Person::getLevel)), Q.count(where(Person::getScore)))
                .toList();
        Map<String, List<Person>> groups = all.stream().collect(Collectors.groupingBy(
                p -> String.valueOf(p.getCity()), LinkedHashMap::new, Collectors.toList()));
        assertThat(rows).hasSize(groups.size());
        for (Row row : rows) {
            List<Person> g = groups.get(String.valueOf(row.get("city")));
            assertThat(g).as("group %s", row).isNotNull();
            assertThat(row.getLong("count(*)")).isEqualTo(g.size());
            assertThat(row.getDouble("avg(age)")).isCloseTo(g.stream().mapToInt(Person::getAge).average().orElseThrow(),
                    Offset.offset(1e-9));
            assertThat(row.get("min(age)")).isEqualTo(g.stream().mapToInt(Person::getAge).min().orElseThrow());
            assertThat(row.get("max(name)")).isEqualTo(g.stream().map(Person::getName).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null));
            List<Integer> scores = g.stream().map(Person::getScore).filter(Objects::nonNull).toList();
            assertThat(row.get("sum(score)")).isEqualTo(scores.isEmpty() ? null : scores.stream().mapToLong(Integer::longValue).sum());
            assertThat(row.getLong("count(distinct level)")).isEqualTo(g.stream().map(Person::getLevel)
                    .filter(Objects::nonNull).distinct().count());
            assertThat(row.getLong("count(score)")).isEqualTo(g.stream().filter(p -> p.getScore() != null).count());
        }
        // COUNT(*) only: answered from postings per segment (no document visited), nulls as their own group
        for (ComparableField<Person, ?> key : List.<ComparableField<Person, ?>>of(where(Person::getCity), where(Person::getLevel), where(Person::getAge))) {
            Map<Object, Long> expected = all.stream().collect(Collectors.groupingBy(
                    p -> Optional.ofNullable(key.get(p)), Collectors.counting())).entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().map(Object::toString).orElse("<null>"), Map.Entry::getValue));
            Map<Object, Long> actual = new HashMap<>();
            for (Row row : db.from(Person.class).filter(pred).groupBy(key).agg(Q.count().as("n")).toList()) {
                Object k = row.get(key.path());
                actual.put(k == null ? "<null>" : k.toString(), row.getLong("n"));
            }
            assertThat(actual).as("count by " + key.path()).isEqualTo(expected);
        }
    }

    // ---------------------------------------------------------------- generators

    public static Person randomPerson(Random r) {
        Person p = new Person(pick(r, NAMES), pick(r, CITIES), r.nextInt(80));
        p.score(r.nextInt(4) == 0 ? null : r.nextInt(100));
        p.weight(WEIGHTS[r.nextInt(WEIGHTS.length)]);
        p.shortValue((short) (r.nextInt(2000) - 1000));
        p.active(r.nextBoolean());
        p.level(r.nextInt(5) == 0 ? null : Level.values()[r.nextInt(3)]);
        p.birthday(r.nextInt(5) == 0 ? null : LocalDate.of(1950, 1, 1).plusDays(r.nextInt(25000)));
        p.home(r.nextInt(4) == 0 ? null : new Address(pick(r, CITIES), String.valueOf(r.nextInt(9999))));
        if (r.nextInt(5) != 0) {
            List<String> tags = new ArrayList<>();
            for (String t : TAGS) {
                if (r.nextInt(3) == 0) {
                    tags.add(t);
                }
            }
            p.tags(tags);
        }
        return p;
    }

    private static <T> T pick(Random r, T[] values) {
        return values[r.nextInt(values.length)];
    }

    public static Pred<Person> randomPred(Random r, int depth) {
        if (depth > 0 && r.nextInt(3) == 0) {
            Pred<Person> a = randomPred(r, depth - 1);
            Pred<Person> b = randomPred(r, depth - 1);
            return switch (r.nextInt(3)) {
                case 0 -> and(a, b);
                case 1 -> or(a, b);
                default -> a.negate();
            };
        }
        return switch (r.nextInt(24)) {
            case 0 -> where(Person::getCity).eq(cityLiteral(r));
            case 1 -> where(Person::getCity).in(cityLiteral(r), cityLiteral(r));
            case 2 -> where(Person::getCity).isNull();
            case 3 -> where(Person::getCity).startsWith(r.nextBoolean() ? "S" : "H");
            case 4 -> where(Person::getName).between("Ann", r.nextBoolean() ? "Dee" : "Annie");
            case 5 -> where(Person::getAge).gt(r.nextInt(80));
            case 6 -> where(Person::getAge).le(r.nextInt(80));
            case 7 -> where(Person::getAge).between(r.nextInt(40), false, 40 + r.nextInt(40), r.nextBoolean());
            case 8 -> where(Person::getAge).in(r.nextInt(80), r.nextInt(80), r.nextInt(80));
            case 9 -> where(Person::getScore).ge(r.nextInt(100));
            case 10 -> where(Person::getScore).isNull();
            case 11 -> where(Person::getWeight).eq(WEIGHTS[r.nextInt(WEIGHTS.length)]);
            case 12 -> where(Person::getWeight).lt(r.nextBoolean() ? 0.0 : -0.0);
            case 13 -> where(Person::isActive).isTrue();
            case 14 -> where(Person::getLevel).eq(Level.values()[r.nextInt(3)]);
            case 15 -> where(Person::getLevel).gt(Level.LOW);
            case 16 -> where(Person::getBirthday).lt(LocalDate.of(1950, 1, 1).plusDays(r.nextInt(25000)));
            case 17 -> Fields.stringField(Person.class, "home.city").eq(cityLiteral(r));
            case 18 -> Fields.stringField(Person.class, "home.city").isNull();
            case 19 -> where(Person::getTags).contains(pick(r, TAGS));
            case 20 -> where(Person::getTags).containsAll(pick(r, TAGS), pick(r, TAGS));
            case 21 -> where(Person::getTags).isNull();
            case 22 -> where(Person::getShortValue).gt(r.nextInt(2000) - 1000);
            default -> Pred.wrap((Predicate<Person>) p -> p.getAge() % 3 == 0);
        };
    }

    private static String cityLiteral(Random r) {
        String c = pick(r, CITIES);
        return c == null ? "Nowhere" : c;
    }

    static Order<Person> randomOrder(Random r) {
        List<Function<Boolean, Order<Person>>> keys = List.of(
                d -> d ? where(Person::getCity).desc() : where(Person::getCity).asc(),
                d -> d ? where(Person::getAge).desc() : where(Person::getAge).asc(),
                d -> d ? where(Person::getScore).desc() : where(Person::getScore).asc(),
                d -> d ? where(Person::getWeight).desc() : where(Person::getWeight).asc(),
                d -> d ? where(Person::getName).desc() : where(Person::getName).asc(),
                d -> d ? where(Person::getLevel).desc() : where(Person::getLevel).asc(),
                d -> d ? where(Person::getBirthday).desc() : where(Person::getBirthday).asc(),
                d -> d ? Fields.stringField(Person.class, "home.city").desc() : Fields.stringField(Person.class, "home.city").asc(),
                d -> d ? where(Person::isActive).desc() : where(Person::isActive).asc(),
                d -> d ? where(Person::getShortValue).desc() : where(Person::getShortValue).asc());
        Order<Person> o = keys.get(r.nextInt(keys.size())).apply(r.nextBoolean());
        for (int i = r.nextInt(3); i > 0; i--) {
            Order<Person> next = keys.get(r.nextInt(keys.size())).apply(r.nextBoolean());
            o = o.thenComparing(r.nextBoolean() ? next.nullsFirst() : next);
        }
        return o;
    }

    private static Set<Long> oids(List<Person> ps) {
        Set<Long> s = new HashSet<>();
        for (Person p : ps) {
            s.add(p.getOid());
        }
        assertThat(s).hasSize(ps.size());
        return s;
    }

    private static List<List<Object>> keys(List<Person> ps, Order<Person> order) {
        List<List<Object>> out = new ArrayList<>();
        for (Person p : ps) {
            List<Object> k = new ArrayList<>();
            for (Order.Key<Person> key : order.keys()) {
                k.add(key.field().get(p));
            }
            out.add(k);
        }
        return out;
    }

    @Test
    void groupHavingSortAndSlice() {
        Random r = new Random(42);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            Person p = randomPerson(r);
            db.insert(p);
            counts.merge(String.valueOf(p.getCity()), 1, Integer::sum);
        }
        List<Row> rows = db.from(Person.class).groupBy(where(Person::getCity))
                .agg(Q.count().as("n"))
                .having(Q.count().as("n").gt(60))
                .sortedBy(Q.count().as("n").desc(), Q.key(where(Person::getCity)).asc())
                .toList();
        List<Map.Entry<String, Integer>> expected = counts.entrySet().stream().filter(e -> e.getValue() > 60)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        assertThat(rows).extracting(x -> x.getLong("n")).containsExactlyElementsOf(
                expected.stream().map(e -> (long) e.getValue()).toList());
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).agg(Q.count().as("n"))
                .sortedBy(Q.count().as("n").desc()).skip(1).limit(2).toList()).hasSize(2);
        assertThat(db.from(Person.class).groupBy(where(Person::getCity)).count()).isEqualTo(counts.size());
    }
}
