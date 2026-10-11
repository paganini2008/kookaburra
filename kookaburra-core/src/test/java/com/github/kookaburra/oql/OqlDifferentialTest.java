package com.github.kookaburra.oql;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.query.Order;
import com.github.kookaburra.query.Pred;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static com.github.kookaburra.query.DifferentialTest.randomPerson;
import static com.github.kookaburra.query.DifferentialTest.randomPred;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random typed queries rendered as OQL must return the same objects as the typed API.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class OqlDifferentialTest {

    private final ObjectDB db = ObjectDB.inMemory(Config.defaults().withMemSegmentBytes(16384));

    @AfterEach
    void close() {
        db.close();
    }

    /** Renders a typed predicate as OQL over alias {@code p}; null if not expressible (opaque code, NaN). */
    static String render(Pred<?> p, Map<String, Object> params, Random r) {
        if (p instanceof Pred.And<?> a) {
            return join(a.preds(), " AND ", params, r);
        }
        if (p instanceof Pred.Or<?> o) {
            return join(o.preds(), " OR ", params, r);
        }
        if (p instanceof Pred.Not<?> n) {
            String inner = render(n.pred(), params, r);
            return inner == null ? null : "NOT (" + inner + ")";
        }
        if (p instanceof Pred.Eq<?, ?> e) {
            String v = lit(e.value(), params, r);
            return v == null ? null : "p." + e.field().path() + " = " + v;
        }
        if (p instanceof Pred.In<?, ?> in) {
            List<String> items = new ArrayList<>();
            for (Object v : in.values()) {
                String s = lit(v, params, r);
                if (s == null) {
                    return null;
                }
                items.add(s);
            }
            return "p." + in.field().path() + " IN (" + String.join(", ", items) + ")";
        }
        if (p instanceof Pred.Range<?, ?> rg) {
            String path = "p." + rg.field().path();
            String lo = rg.lo() == null ? null : lit(rg.lo(), params, r);
            String hi = rg.hi() == null ? null : lit(rg.hi(), params, r);
            if ((rg.lo() != null && lo == null) || (rg.hi() != null && hi == null)) {
                return null;
            }
            if (lo != null && hi != null && rg.loInclusive() && rg.hiInclusive() && r.nextBoolean()) {
                return path + " BETWEEN " + lo + " AND " + hi;
            }
            List<String> parts = new ArrayList<>();
            if (lo != null) {
                parts.add(r.nextBoolean() ? path + (rg.loInclusive() ? " >= " : " > ") + lo
                        : lo + (rg.loInclusive() ? " <= " : " < ") + path);
            }
            if (hi != null) {
                parts.add(path + (rg.hiInclusive() ? " <= " : " < ") + hi);
            }
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (p instanceof Pred.IsNull<?> n) {
            return "p." + n.field().path() + " IS NULL";
        }
        if (p instanceof Pred.StartsWith<?> s) {
            return "p." + s.field().path() + " LIKE '" + s.prefix() + "%'";
        }
        if (p instanceof Pred.Contains<?, ?> c) {
            List<String> parts = new ArrayList<>();
            for (Object v : c.values()) {
                parts.add(lit(v, params, r) + " MEMBER OF p." + c.field().path());
            }
            return "(" + String.join(c.all() ? " AND " : " OR ", parts) + ")";
        }
        return null;
    }

    private static String join(List<? extends Pred<?>> ps, String op, Map<String, Object> params, Random r) {
        List<String> parts = new ArrayList<>();
        for (Pred<?> x : ps) {
            String s = render(x, params, r);
            if (s == null) {
                return null;
            }
            parts.add(s);
        }
        return "(" + String.join(op, parts) + ")";
    }

    private static String lit(Object v, Map<String, Object> params, Random r) {
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return null;
        }
        if (r.nextInt(4) == 0) {
            String name = "p" + params.size();
            params.put(name, v);
            return ":" + name;
        }
        if (v instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (v instanceof Enum<?> e) {
            return "'" + e.name() + "'";
        }
        if (v instanceof LocalDate d) {
            return "DATE '" + d + "'";
        }
        if (v instanceof Double d) {
            return Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-0.0) ? "-0.0" : String.valueOf(d);
        }
        if (v instanceof Ref<?> ref) {
            return String.valueOf(ref.oid());
        }
        return String.valueOf(v);
    }

    @Test
    void oqlAgreesWithTypedQueries() {
        Random r = new Random(77);
        for (int i = 0; i < 500; i++) {
            db.insert(randomPerson(r));
        }
        db.flush();
        for (int i = 0; i < 150; i++) {
            db.insert(randomPerson(r));
        }
        int compared = 0;
        for (int q = 0; q < 400; q++) {
            Pred<Person> pred = randomPred(r, 3);
            Map<String, Object> params = new TreeMap<>();
            String where = render(pred, params, r);
            if (where == null) {
                continue;
            }
            compared++;
            String text = "SELECT p FROM Person p WHERE " + where;
            OqlQuery<Person> query = db.oql(text, Person.class);
            params.forEach(query::param);
            Set<Long> got = new HashSet<>();
            query.list().forEach(p -> got.add(p.getOid()));
            Set<Long> expected = new HashSet<>();
            db.from(Person.class).filter(pred).toList().forEach(p -> expected.add(p.getOid()));
            assertThat(got).as(text + " " + params).isEqualTo(expected);

            OqlQuery<Object> count = db.oql("SELECT COUNT(*) FROM Person p WHERE " + where);
            params.forEach(count::param);
            assertThat(count.list()).as(text).containsExactly((long) expected.size());
        }
        assertThat(compared).isGreaterThan(250);
    }

    @Test
    void orderAndSliceAgree() {
        Random r = new Random(5);
        for (int i = 0; i < 300; i++) {
            db.insert(randomPerson(r));
        }
        String[][] keys = {{"age", "DESC"}, {"name", "ASC"}, {"city", "ASC NULLS FIRST"}, {"score", "DESC NULLS LAST"}};
        for (int q = 0; q < 40; q++) {
            String[] k1 = keys[r.nextInt(keys.length)];
            String[] k2 = keys[r.nextInt(keys.length)];
            int limit = r.nextInt(20);
            int offset = r.nextInt(10);
            List<Object> oql = new ArrayList<>();
            db.oql("SELECT p FROM Person p ORDER BY p." + k1[0] + " " + k1[1] + ", p." + k2[0] + " " + k2[1]
                    + " LIMIT " + limit + " OFFSET " + offset, Person.class).list().forEach(p -> oql.add(key(p, k1[0], k2[0])));
            Order<Person> o1 = order(k1);
            Order<Person> o2 = order(k2);
            List<Object> typed = new ArrayList<>();
            db.from(Person.class).sorted(o1.thenComparing(o2)).skip(offset).limit(limit).toList()
                    .forEach(p -> typed.add(key(p, k1[0], k2[0])));
            assertThat(oql).as(k1[0] + "," + k2[0]).isEqualTo(typed);
        }
    }

    private static Order<Person> order(String[] k) {
        Order<Person> o = switch (k[0]) {
            case "age" -> k[1].startsWith("DESC") ? where(Person::getAge).desc() : where(Person::getAge).asc();
            case "name" -> where(Person::getName).asc();
            case "city" -> where(Person::getCity).asc();
            default -> where(Person::getScore).desc();
        };
        if (k[1].endsWith("NULLS FIRST")) {
            o = o.nullsFirst();
        } else if (k[1].endsWith("NULLS LAST")) {
            o = o.nullsLast();
        }
        return o;
    }

    private static List<Object> key(Person p, String a, String b) {
        List<Object> k = new ArrayList<>();
        for (String f : List.of(a, b)) {
            k.add(switch (f) {
                case "age" -> p.getAge();
                case "name" -> p.getName();
                case "city" -> p.getCity();
                default -> p.getScore();
            });
        }
        return k;
    }

    @Test
    void fastAndEvaluatedGroupingAgree() {
        Random r = new Random(9);
        for (int i = 0; i < 400; i++) {
            db.insert(randomPerson(r));
        }
        List<Row> fast = db.oql("SELECT p.city, COUNT(*), COUNT(p.score), COUNT(DISTINCT p.level), SUM(p.score), AVG(p.age), "
                + "MIN(p.name), MAX(p.level) FROM Person p GROUP BY p.city ORDER BY 1").rows();
        List<Row> evaluated = db.oql("SELECT p.city, COUNT(*), COUNT(p.score + 0), COUNT(DISTINCT CONCAT(p.level)), "
                + "SUM(p.score + 0), AVG(p.age + 0), MIN(CONCAT(p.name)), MAX(p.level || '') FROM Person p GROUP BY p.city ORDER BY 1").rows();
        assertThat(fast).hasSameSizeAs(evaluated);
        for (int i = 0; i < fast.size(); i++) {
            for (int c = 0; c < 7; c++) {
                Object a = fast.get(i).get(c);
                Object b = evaluated.get(i).get(c);
                if (c == 7) {
                    continue;
                }
                assertThat(a instanceof Double x ? (Object) Math.round(x * 1e9) : a)
                        .as("row %d col %d", i, c).isEqualTo(b instanceof Double y ? (Object) Math.round(y * 1e9) : b);
            }
        }
        assertThat(db.oql("EXPLAIN SELECT p.city, COUNT(*) FROM Person p GROUP BY p.city").rows().toString()).contains("canonical fast path");
        assertThat(db.oql("SELECT p.city, COUNT(*) FROM Person p GROUP BY UPPER(p.city)").explain()).contains("evaluated per object");
    }
}
