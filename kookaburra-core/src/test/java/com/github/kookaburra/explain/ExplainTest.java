package com.github.kookaburra.explain;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.oql.OqlException;
import com.github.kookaburra.query.QueryPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Query plans: the step tree of {@code plan()} / {@code explain()} and the measured one of {@code analyze()} /
 * {@code explainAnalyze()} / OQL {@code EXPLAIN ANALYZE}.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class ExplainTest {

    private ObjectDB db;

    @BeforeEach
    void open() {
        db = TestDbs.create();
        WriteBatch b = db.batch();
        String[] cities = {"Perth", "Sydney", "Hobart", "Darwin"};
        for (int i = 0; i < 1000; i++) {
            b.insert(new Person("p" + i, cities[i % 4], 20 + i % 50).score(i % 100));
        }
        b.commit();
        db.flush();
        db.insert(new Person("late", "Perth", 33));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void planTree() {
        QueryPlan plan = db.from(Person.class).where(Person::getCity).eq("Perth").where(Person::getAge).between(30, 40)
                .orderBy(Person::getAge).desc().limit(10).plan();
        assertThat(plan.analyzed()).isFalse();
        QueryPlan.Step root = plan.root();
        assertThat(root.operation()).isEqualTo("Query");
        assertThat(root.detail()).isEqualTo("Person · snapshot 2 · 2 segments");
        QueryPlan.Step limit = root.children().get(0);
        assertThat(limit.operation()).isEqualTo("Limit");
        QueryPlan.Step sort = limit.children().get(0);
        assertThat(sort.operation()).isEqualTo("Top-N sort");
        assertThat(sort.access()).isEqualTo(QueryPlan.Access.PROCESS);
        QueryPlan.Step filter = sort.children().get(0);
        assertThat(filter.children()).extracting(QueryPlan.Step::operation).containsExactly("mem#3", "seg#2");
        QueryPlan.Step and = filter.children().get(1).children().get(0);
        assertThat(and.operation()).isEqualTo("AND");
        assertThat(and.children()).extracting(QueryPlan.Step::access).containsExactly(QueryPlan.Access.INDEX, QueryPlan.Access.INDEX);
        assertThat(filter.children().get(0).children().get(0).children().get(1).access()).isEqualTo(QueryPlan.Access.SCAN);
        assertThat(plan.render()).contains("└─ Limit  10", "├─ mem#3  1 object", "[postings", "[scan: reads each row]")
                .doesNotContain(" ms");                                         // no times without analyze
        assertThat(plan.toString()).isEqualTo(plan.render());
    }

    @Test
    void analyzeCountsAndTimes() {
        QueryPlan plan = db.from(Person.class).where(Person::getCity).eq("Perth").where(Person::getScore).gt(90)
                .orderBy(Person::getAge).desc().limit(10).analyze();
        assertThat(plan.analyzed()).isTrue();
        QueryPlan.Step root = plan.root();
        assertThat(root.rows()).isEqualTo(10);
        assertThat(root.nanos()).isPositive();
        QueryPlan.Step fetch = root.children().get(0);
        assertThat(fetch.operation()).isEqualTo("Fetch objects");
        QueryPlan.Step sort = fetch.children().get(0).children().get(0);
        assertThat(sort.inputRows()).isEqualTo(20);                             // Perth (250 + 1) with score > 90
        assertThat(sort.rows()).isEqualTo(10);
        QueryPlan.Step filter = sort.children().get(0);
        assertThat(filter.inputRows()).isEqualTo(1001);
        assertThat(filter.rows()).isEqualTo(20);
        QueryPlan.Step seg = filter.children().get(1);
        assertThat(seg.operation()).isEqualTo("seg#2");
        QueryPlan.Step city = seg.children().get(0).children().get(0);
        assertThat(city.inputRows()).isEqualTo(1000);
        assertThat(city.rows()).isEqualTo(250);
        assertThat(seg.children().get(0).children().get(1).rows()).isEqualTo(20);
        String text = plan.render();
        assertThat(text).contains("1,000 → 250 rows", " ms").contains("10 rows");
        assertThat(db.from(Person.class).where(Person::getCity).eq("Perth").limit(1).explainAnalyze()).contains("1 row ");
        assertThat(QueryPlan.time(42_000)).isEqualTo("0.04 ms");
        assertThat(QueryPlan.time(42_000_000)).isEqualTo("42.0 ms");
        assertThat(QueryPlan.time(4_200_000_000L)).isEqualTo("4.20 s");
    }

    @Test
    void oql() {
        String text = db.oql("EXPLAIN ANALYZE SELECT p.name FROM Person p WHERE p.city = 'Perth' AND p.age > 60 ORDER BY p.age").explain();
        assertThat(text).contains("Sort  age ASC", "→ 40 rows", " ms", "OQL projection [p.name]");
        assertThat(db.oql("EXPLAIN ANALYZE SELECT p FROM Person p").isAnalyze()).isTrue();
        assertThat(db.oql("EXPLAIN SELECT p FROM Person p").isAnalyze()).isFalse();
        assertThat(db.oql("SELECT p FROM Person p").isAnalyze()).isFalse();

        QueryPlan plan = db.oql("EXPLAIN ANALYZE SELECT p.name FROM Person p WHERE p.city = 'Perth' ORDER BY p.age LIMIT 3").plan();
        assertThat(plan.analyzed()).isTrue();
        assertThat(plan.root().operation()).isEqualTo("OQL");
        assertThat(plan.root().detail()).contains("[ORDER BY pushed down]");
        assertThat(plan.root().rows()).isEqualTo(3);
        assertThat(plan.root().children().get(0).operation()).isEqualTo("Query");
        QueryPlan grouped = db.oql("SELECT p.city, COUNT(*) FROM Person p GROUP BY p.city").plan();
        assertThat(grouped.analyzed()).isFalse();
        assertThat(grouped.root().detail()).contains("GROUP BY");
        assertThat(db.oql("UPDATE Person p SET p.age = 1 WHERE p.name = 'x'").plan()).isNull();

        assertThatThrownBy(() -> db.oql("EXPLAIN ANALYZE UPDATE Person p SET p.age = 1"))
                .isInstanceOf(OqlException.class).hasMessageContaining("SELECT statements only");
        assertThat(db.oql("SELECT p FROM Person p WHERE p.name = 'analyze'").list()).isEmpty();           // still a name
    }

    @Test
    void rankingSteps() {
        assertThat(db.from(Person.class).where(Person::getCity).eq("Perth").plan().root().children().get(0).operation())
                .isEqualTo("Filter");
        assertThat(db.from(Person.class).plan().render()).contains("Filter  all objects");
    }
}
