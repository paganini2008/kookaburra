package com.github.kookaburra.oql;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Cat;
import com.github.kookaburra.model.Company;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Level;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OQL: SQL-92 core semantics on a small fixed data set.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class OqlTest {

    private ObjectDB db;
    private long ai;

    @BeforeEach
    void setUp() {
        db = ObjectDB.inMemory();
        Company c1 = new Company();
        c1.setIndustry("AI");
        ai = db.insert(c1);
        Company c2 = new Company();
        c2.setIndustry("Mining");
        long mining = db.insert(c2);
        db.insert(new Person("Ann", "Sydney", 30).score(90).level(Level.HIGH).tags(List.of("vip", "new"))
                .home(new Address("Perth", "6000")).employer(Ref.of(ai)).birthday(LocalDate.of(1990, 5, 1)).weight(60.5));
        db.insert(new Person("Bob", "Perth", 45).score(70).level(Level.LOW).employer(Ref.of(mining)).tags(List.of()));
        db.insert(new Person("Cid", "Sydney", 20).level(Level.MEDIUM).tags(List.of("vip")));
        db.insert(new Person("Dee", null, 61).score(10).weight(80.0));
        db.insert(new Person("O'Neil", "Darwin", 33).score(50).employer(Ref.of(ai)));
        db.insert(new Dog("Rex", 3, "Lab"));
        db.insert(new Cat("Tom", 2, true));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private List<Object> col(String q) {
        return db.oql(q).list();
    }

    @Test
    void selectShapes() {
        assertThat(db.oql("SELECT p FROM Person p WHERE p.age > 40", Person.class).list())
                .extracting(Person::getName).containsExactlyInAnyOrder("Bob", "Dee");
        assertThat(db.oql("select * from Person where age > 40").list()).hasSize(2).allMatch(o -> o instanceof Person);
        assertThat(db.oql("SELECT p FROM Person p WHERE p.age > 40").dynamic().list()).allMatch(o -> o.get("name") != null);
        assertThat(col("SELECT p.name FROM Person p WHERE p.city = 'Sydney' ORDER BY p.name")).containsExactly("Ann", "Cid");
        assertThat(db.oql("SELECT p.age FROM Person p WHERE p.name = 'Ann'", Long.class).list()).containsExactly(30L);
        assertThat(db.oql("SELECT p.age FROM Person p WHERE p.name = 'Ann'", Integer.class).list()).containsExactly(30);
        List<Row> rows = db.oql("SELECT p.name AS n, p.age * 2 doubled FROM Person p WHERE p.name = 'Bob'").rows();
        assertThat(rows.get(0).get("n")).isEqualTo("Bob");
        assertThat(rows.get(0).get("doubled")).isEqualTo(90L);
        assertThat(db.oql("SELECT p.name, p.age FROM Person p WHERE p.name = 'Bob'", Row.class).list().get(0).get(1)).isEqualTo(45);
        assertThat(db.oql("SELECT p.name, p.age FROM Person p WHERE p.name = 'Bob'").list().get(0)).isInstanceOf(Row.class);
        assertThat(db.oql("SELECT p FROM Person p WHERE p.name = 'Bob'").rows().get(0).get("p")).isInstanceOf(DynamicObject.class);
        assertThat(db.oql("SELECT NEW com.github.kookaburra.oql.OqlTest$NameAge(p.name, p.age) FROM Person p WHERE p.age < 25",
                NameAge.class).list()).containsExactly(new NameAge("Cid", 20));
        assertThat(db.oql("SELECT p FROM Person p WHERE p.name = 'Zed'", Person.class).first()).isEmpty();
        assertThat(db.oql("SELECT p FROM Person p ORDER BY p.age", Person.class).first()).map(Person::getName).contains("Cid");
        assertThat(db.oql("SELECT a FROM Animal a ORDER BY a.name").list()).hasExactlyElementsOfTypes(Dog.class, Cat.class);
    }

    public record NameAge(String name, int age) {
    }

    @Test
    void expressionsAndFunctions() {
        assertThat(col("SELECT p.age + 1, p.age - 1, p.age * 2, p.age / 7, p.age % 7, -p.age FROM Person p WHERE p.name = 'Ann'").get(0).toString())
                .isEqualTo("{(p.age + 1)=31, (p.age - 1)=29, p.age * 2=60, p.age / 7=4, p.age % 7=2, -p.age=-30}".replace("(p.age + 1)", "p.age + 1").replace("(p.age - 1)", "p.age - 1"));
        assertThat(col("SELECT p.weight / 2, p.weight + 1 FROM Person p WHERE p.name = 'Ann'").get(0).toString()).contains("30.25", "61.5");
        assertThat(col("SELECT p.name || '@' || p.city FROM Person p WHERE p.name = 'Ann'")).containsExactly("Ann@Sydney");
        assertThat(col("SELECT p.name || p.city FROM Person p WHERE p.name = 'Dee'")).containsExactly((Object) null);
        assertThat(col("SELECT UPPER(p.name), LOWER(p.name), LENGTH(p.name), SUBSTRING(p.name, 2), SUBSTRING(p.name, 2, 1), "
                + "TRIM('  x '), ABS(-3), ABS(-1.5), MOD(7, 3), COALESCE(p.city, 'none'), NULLIF(p.age, 30), SIZE(p.tags), "
                + "CONCAT(p.name, '!'), SUBSTRING(p.name, 99) FROM Person p WHERE p.name = 'Ann'").get(0).toString())
                .contains("=ANN", "=ann", "=3", "=nn", "=n,", "=x", "=3,", "=1.5", "=1,", "=Sydney", "=null", "=2", "=Ann!");
        assertThat(col("SELECT CASE WHEN p.age < 30 THEN 'young' WHEN p.age < 50 THEN 'mid' ELSE 'old' END FROM Person p ORDER BY p.age"))
                .containsExactly("young", "mid", "mid", "mid", "old");
        assertThat(col("SELECT CASE WHEN p.age > 100 THEN 1 END FROM Person p WHERE p.name = 'Ann'")).containsExactly((Object) null);
        assertThat(col("SELECT COALESCE(p.city, p.name) FROM Person p WHERE p.name = 'Dee'")).containsExactly("Dee");
        assertThat(col("SELECT SIZE(p.tags) FROM Person p WHERE p.name = 'Dee'")).containsExactly(0L);
        assertThat(col("SELECT p.employer FROM Person p WHERE p.name = 'Ann'")).containsExactly(Ref.of(ai));
        assertThat(col("SELECT p.employer + 0 FROM Person p WHERE p.name = 'Ann'")).containsExactly(ai);
        assertThat(col("SELECT p.home.city, p.home FROM Person p WHERE p.name = 'Ann'").get(0).toString()).contains("Perth", "zip=6000");
    }

    @Test
    void wherePredicates() {
        assertThat(col("SELECT p.name FROM Person p WHERE p.age BETWEEN 30 AND 45 AND p.score > 60 ORDER BY 1")).containsExactly("Ann", "Bob");
        assertThat(col("SELECT p.name FROM Person p WHERE p.age NOT BETWEEN 30 AND 45 ORDER BY 1")).containsExactly("Cid", "Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.city IN ('Perth', 'Darwin') ORDER BY 1")).containsExactly("Bob", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.city NOT IN ('Perth', 'Darwin') ORDER BY 1")).containsExactly("Ann", "Cid", "Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.name LIKE '_e%' ORDER BY 1")).containsExactly("Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.name LIKE '%n' ORDER BY 1")).containsExactly("Ann");
        assertThat(col("SELECT p.name FROM Person p WHERE p.name NOT LIKE 'A%' AND p.name LIKE 'O''%'")).containsExactly("O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE 'a_b' LIKE 'a!_b' ESCAPE '!' AND p.name = 'Ann'")).containsExactly("Ann");
        assertThat(col("SELECT p.name FROM Person p WHERE p.city IS NULL")).containsExactly("Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.home IS NOT NULL")).containsExactly("Ann");
        assertThat(col("SELECT p.name FROM Person p WHERE p.tags IS EMPTY ORDER BY 1")).containsExactly("Bob", "Dee", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.tags IS NOT EMPTY ORDER BY 1")).containsExactly("Ann", "Cid");
        assertThat(col("SELECT p.name FROM Person p WHERE 'vip' MEMBER OF p.tags ORDER BY 1")).containsExactly("Ann", "Cid");
        assertThat(col("SELECT p.name FROM Person p WHERE 'new' NOT MEMBER p.tags AND p.tags IS NOT NULL ORDER BY 1")).containsExactly("Bob", "Cid");
        assertThat(col("SELECT p.name FROM Person p WHERE p.level > 'LOW' ORDER BY p.level DESC")).containsExactly("Ann", "Cid");
        assertThat(col("SELECT p.name FROM Person p WHERE p.birthday < DATE '2000-01-01'")).containsExactly("Ann");
        assertThat(col("SELECT p.name FROM Person p WHERE p.age > 30.5 ORDER BY 1")).containsExactly("Bob", "Dee", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.age * 2 > 100")).containsExactly("Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.score <> 90 ORDER BY 1")).containsExactly("Bob", "Dee", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE NOT (p.score = 90) ORDER BY 1")).containsExactly("Bob", "Cid", "Dee", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.score = NULL")).isEmpty();
        assertThat(col("SELECT p.name FROM Person p WHERE 30 < p.age AND 60 >= p.age ORDER BY 1")).containsExactly("Bob", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.age IN (20, 61) OR p.name = 'Ann' ORDER BY 1")).containsExactly("Ann", "Cid", "Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE p.oid = " + db.oql("SELECT p.oid FROM Person p WHERE p.name = 'Bob'").list().get(0)))
                .containsExactly("Bob");
        assertThat(col("SELECT p.name FROM Person p WHERE p.version = 1")).hasSize(5);
        assertThatThrownBy(() -> col("select p.NAME from Person p where p.name = 'Ann'")).hasMessageContaining("unknown field 'NAME'");
    }

    @Test
    void referencesJoinsAndTypes() {
        assertThat(col("SELECT p.name FROM Person p WHERE p.employer.industry = 'AI' ORDER BY 1")).containsExactly("Ann", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p JOIN p.employer e WHERE e.industry = 'Mining'")).containsExactly("Bob");
        assertThat(col("SELECT p.name FROM Person p INNER JOIN p.employer e ORDER BY 1")).containsExactly("Ann", "Bob", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p LEFT OUTER JOIN p.employer e")).hasSize(5);
        assertThat(col("SELECT p.name, p.employer.industry FROM Person p WHERE p.name = 'Ann'").get(0).toString()).contains("AI");
        assertThat(col("SELECT e.industry FROM Person p JOIN p.employer e WHERE p.name = 'Bob'")).containsExactly("Mining");
        assertThat(col("SELECT p.name FROM Person p WHERE NOT (p.employer.industry = 'AI') ORDER BY 1")).containsExactly("Bob", "Cid", "Dee");
        assertThat(col("SELECT p.name FROM Person p WHERE UPPER(p.employer.industry) = 'AI' ORDER BY 1")).containsExactly("Ann", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p WHERE p.employer.industry IS NULL ORDER BY 1")).containsExactly("Cid", "Dee");
        assertThat(col("SELECT a.name FROM Animal a WHERE TYPE(a) = Dog")).containsExactly("Rex");
        assertThat(col("SELECT a.name FROM Animal a WHERE TYPE(a) <> Dog")).containsExactly("Tom");
        assertThat(col("SELECT a.name FROM Animal a WHERE TYPE(a) IN (Cat, Dog) ORDER BY 1")).containsExactly("Rex", "Tom");
        assertThat(col("SELECT a.name FROM Animal a WHERE TYPE(a) NOT IN (Cat)")).containsExactly("Rex");
        assertThat(col("SELECT d.breed FROM Dog d")).containsExactly("Lab");
        assertThat(col("SELECT a.breed FROM Animal a WHERE a.breed IS NOT NULL")).containsExactly("Lab");
    }

    @Test
    void groupingAndAggregates() {
        List<Row> rows = db.oql("SELECT p.city, COUNT(*) AS n, SUM(p.score) total, AVG(p.age), MIN(p.age), MAX(p.name), "
                + "COUNT(DISTINCT p.level) FROM Person p GROUP BY p.city HAVING COUNT(*) >= 1 ORDER BY n DESC, 1").rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("p.city")).isEqualTo("Sydney");
        assertThat(rows.get(0).get("n")).isEqualTo(2L);
        assertThat(rows.get(0).get("total")).isEqualTo(90L);
        assertThat(rows.get(0).get(3)).isEqualTo(25.0);
        assertThat(rows.get(0).get(4)).isEqualTo(20);
        assertThat(rows.get(0).get(5)).isEqualTo("Cid");
        assertThat(rows.get(0).get(6)).isEqualTo(2L);
        assertThat(rows.get(3).get(0)).isNull();       // NULL group sorts last ascending
        assertThat(db.oql("SELECT p.city, COUNT(*) FROM Person p GROUP BY p.city HAVING COUNT(*) > 1").rows()).hasSize(1);
        assertThat(col("SELECT COUNT(*) FROM Person p WHERE p.age > 100")).containsExactly(0L);
        assertThat(col("SELECT SUM(p.score) FROM Person p WHERE p.age > 100")).containsExactly((Object) null);
        assertThat(col("SELECT MAX(p.age) - MIN(p.age) FROM Person p")).containsExactly(41L);
        assertThat(col("SELECT COUNT(p.score), COUNT(*) FROM Person p").get(0).toString()).contains("=4", "=5");
        assertThat(col("SELECT UPPER(p.city), COUNT(*) FROM Person p GROUP BY UPPER(p.city) ORDER BY 2 DESC, 1").get(0).toString())
                .contains("SYDNEY", "=2");
        assertThat(col("SELECT p.level, AVG(p.score + 0) FROM Person p WHERE p.level IS NOT NULL GROUP BY p.level ORDER BY p.level"))
                .extracting(r -> ((Row) r).get(0)).containsExactly("LOW", "MEDIUM", "HIGH");
        assertThat(col("SELECT SUM(p.weight), AVG(p.weight) FROM Person p WHERE p.weight > 0").get(0).toString()).contains("140.5", "70.25");
        assertThat(col("SELECT COUNT(DISTINCT p.city || '') FROM Person p")).containsExactly(3L);
        assertThat(col("SELECT DISTINCT p.city FROM Person p WHERE p.city IS NOT NULL ORDER BY p.city")).containsExactly("Darwin", "Perth", "Sydney");
        assertThat(col("SELECT DISTINCT COUNT(*) FROM Person p GROUP BY p.city")).containsExactlyInAnyOrder(2L, 1L);
        assertThat(col("SELECT p.city, COUNT(*) c FROM Person p GROUP BY p.city ORDER BY c DESC LIMIT 1").get(0).toString()).contains("Sydney");
        assertThat(col("SELECT COUNT(*) FROM Person p HAVING COUNT(*) > 100")).isEmpty();
    }

    @Test
    void orderingSlicingAndParameters() {
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.score DESC NULLS LAST, p.name")).containsExactly("Ann", "Bob", "O'Neil", "Dee", "Cid");
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.score ASC NULLS FIRST")).first().isEqualTo("Cid");
        assertThat(col("SELECT p.name, p.age FROM Person p ORDER BY 2 DESC LIMIT 2").toString()).contains("Dee", "Bob").doesNotContain("Ann");
        assertThat(col("SELECT p.name FROM Person p ORDER BY LENGTH(p.name) DESC, p.name LIMIT 2")).containsExactly("O'Neil", "Ann");
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.age LIMIT 2 OFFSET 1")).containsExactly("Ann", "O'Neil");
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.age OFFSET 4")).containsExactly("Dee");
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.age OFFSET 1 LIMIT 1")).containsExactly("Ann");
        assertThat(db.oql("SELECT p.name FROM Person p WHERE p.city = :c AND p.age > ?1 ORDER BY p.name")
                .param("c", "Sydney").param(1, 25).list()).containsExactly("Ann");
        assertThat(db.oql("SELECT p.name FROM Person p WHERE p.level = :l").param("l", Level.LOW).list()).containsExactly("Bob");
        assertThat(db.oql("SELECT p.name FROM Person p ORDER BY p.age LIMIT :n").param("n", 1).list()).containsExactly("Cid");
        assertThat(db.oql("SELECT p.name FROM Person p ORDER BY p.age").limit(2).offset(3).list()).containsExactly("Bob", "Dee");
        assertThat(db.oql("SELECT DISTINCT p.city FROM Person p ORDER BY 1 LIMIT 2").list()).containsExactly("Darwin", "Perth");
        assertThat(col("SELECT p.name FROM Person p ORDER BY p.employer.industry, p.name")).containsExactly("Ann", "O'Neil", "Bob", "Cid", "Dee");
        assertThat(col("SELECT p.name AS who FROM Person p ORDER BY who DESC LIMIT 1")).containsExactly("O'Neil");
        assertThat(col("-- comment\nSELECT /* inline */ p.name FROM Person p WHERE p.name = 'Ann'")).containsExactly("Ann");
        assertThatThrownBy(() -> col("SELECT p.name FROM Person p WHERE p.name = `Ann`")).hasMessageContaining("unknown field 'Ann'");
        assertThat(col("SELECT p.`name` FROM Person AS p WHERE p.name = 'Ann';")).containsExactly("Ann");
    }

    @Test
    void updateDeleteAndExplain() {
        assertThat(db.oql("UPDATE Person p SET p.score = COALESCE(p.score, 0) + 1, p.city = 'X' WHERE p.age > 40").execute()).isEqualTo(2);
        assertThat(col("SELECT p.score FROM Person p WHERE p.city = 'X' ORDER BY 1")).containsExactly(11, 71);
        assertThat(db.oql("UPDATE Person SET home.city = 'Hobart' WHERE name = 'Ann'").execute()).isEqualTo(1);
        assertThat(col("SELECT p.home.city FROM Person p WHERE p.name = 'Ann'")).containsExactly("Hobart");
        assertThat(db.oql("DELETE FROM Person p WHERE p.city = 'X'").execute()).isEqualTo(2);
        assertThat(col("SELECT COUNT(*) FROM Person p")).containsExactly(3L);
        assertThat(db.oql("DELETE FROM Animal").execute()).isEqualTo(2);
        assertThat(db.oql("UPDATE Person p SET p.age = p.age WHERE p.age > 1000").execute()).isZero();

        String plan = db.oql("SELECT p FROM Person p WHERE p.city = 'Sydney' AND p.age * 2 > 10 ORDER BY p.age").explain();
        assertThat(plan).contains("QUERY Person", "city = 'Sydney'", "RESIDUAL", "ORDER BY pushed down");
        assertThat(db.oql("SELECT p.name FROM Person p ORDER BY UPPER(p.name)").explain()).contains("ORDER BY in memory");
        assertThat(db.oql("EXPLAIN SELECT p FROM Person p").list()).first().asString().startsWith("QUERY");
        // EXPLAIN never changes data, even through execute()
        long before = db.from(Person.class).count();
        assertThatThrownBy(() -> db.oql("EXPLAIN DELETE FROM Person p").execute()).hasMessageContaining("nothing was changed");
        assertThatThrownBy(() -> db.oql("EXPLAIN UPDATE Person p SET p.age = 0").execute()).hasMessageContaining("nothing was changed");
        assertThat(db.from(Person.class).count()).isEqualTo(before);
        assertThat(db.oql("SELECT p FROM Person p LIMIT 0").list()).isEmpty();
        assertThat(db.oql("SELECT p.name FROM Person p ORDER BY p.name LIMIT 0").list()).isEmpty();
        assertThat(db.oql("DELETE FROM Person p WHERE p.age > 1").explain()).contains("DELETE in one batch");
        assertThat(db.oql("UPDATE Person p SET p.age = 1").explain()).contains("UPDATE in one batch");
        assertThat(db.oql("SELECT p FROM Person p").text()).isEqualTo("SELECT p FROM Person p");
        assertThat(db.oql("SELECT p FROM Person p").isSelect()).isTrue();
    }

    @Test
    void errors() {
        assertError("SELECT p FROM", "syntax error", 1);
        assertError("SELECT p FROM Person p WHERE p.name = 'abc", "unterminated string literal", 1);
        assertThatThrownBy(() -> db.oql("SELECT").list()).hasMessageContaining("(unexpected here)").hasMessageNotContaining("expecting {");
        assertThatThrownBy(() -> db.oql("SELECT p FROM Person p WHERE (p.age > 1").list()).hasMessageContaining("syntax error");
        assertError("SELECT p.name FROM Nowhere p", "no stored class", 1);
        assertError("SELECT p.nope FROM Person p", "unknown field 'nope'", 1);
        assertError("SELECT p.name FROM Person p WHERE p.age > :missing", "missing parameter :missing", 1);
        assertError("SELECT p.name, COUNT(*) FROM Person p GROUP BY p.city", "must appear in GROUP BY", 1);
        assertError("SELECT COUNT(MAX(p.age)) FROM Person p", "cannot be nested", -1);
        assertError("SELECT p.name FROM Person p HAVING p.age > 1", "HAVING needs GROUP BY", -1);
        assertError("SELECT p FROM Person p GROUP BY p.city", "whole objects", -1);
        assertError("SELECT p.age / 0 FROM Person p", "division by zero", -1);
        assertError("SELECT p.name FROM Person p LIMIT -1", "non-negative", -1);
        assertError("SELECT p.name FROM Person p ORDER BY 9", "out of range", -1);
        assertError("SELECT NOPE(p.name) FROM Person p", "unknown function NOPE", 1);
        assertError("SELECT UPPER(p.name, 1) FROM Person p", "takes 1 argument", 1);
        assertError("SELECT SUBSTRING(p.name) FROM Person p", "2 or 3 arguments", 1);
        assertError("SELECT p.name + 1 FROM Person p", "needs numbers", -1);
        assertError("SELECT p.name FROM Person p WHERE p.name > 1", "cannot compare", -1);
        assertError("SELECT p.name FROM Person p JOIN p.name n", "must end at a reference", 1);
        assertError("SELECT a.name FROM Animal a WHERE TYPE(x) = Dog", "needs the FROM alias", 1);
        assertError("SELECT a.name FROM Animal a WHERE TYPE(a) = Nope", "no stored class", 1);
        assertError("SELECT a.name FROM Animal a WHERE TYPE(a) > Dog", "only supports = and <>", 1);
        assertError("SELECT p.name FROM Person p WHERE p.birthday < DATE 'x'", "invalid DATE literal", 1);
        assertError("SELECT p.age FROM Person p WHERE p.age > 99999999999999999999", "out of range", 1);
        assertError("SELECT p.name FROM Person p WHERE 'a' LIKE 'a' ESCAPE 'xy'", "single character", -1);
        assertThatThrownBy(() -> db.oql("SELECT NEW java.lang.Nope(p.name) FROM Person p").list()).hasMessageContaining("not found");
        assertThatThrownBy(() -> db.oql("SELECT NEW com.github.kookaburra.oql.OqlTest$NameAge(p.name) FROM Person p").list())
                .hasMessageContaining("no constructor");
        assertThatThrownBy(() -> db.oql("UPDATE Person p SET p.oid = 1").execute()).hasMessageContaining("cannot SET");
        assertThatThrownBy(() -> db.oql("UPDATE Person p SET p.employer.industry = 'x'").execute()).hasMessageContaining("cannot SET");
        assertThatThrownBy(() -> db.oql("UPDATE Person p SET p.age = 'old'").execute()).hasMessageContaining("UPDATE failed");
        assertThatThrownBy(() -> db.oql("SELECT p FROM Person p").execute()).hasMessageContaining("execute() is for UPDATE");
        assertThatThrownBy(() -> db.oql("DELETE FROM Person").list()).hasMessageContaining("needs a SELECT");
        assertThatThrownBy(() -> db.oql("DELETE FROM Person").rows()).hasMessageContaining("needs a SELECT");
        assertThatThrownBy(() -> db.oql("SELECT p.name, p.age FROM Person p", String.class).list()).hasMessageContaining("2 columns");
        assertThatThrownBy(() -> db.oql("SELECT p.name FROM Person p", Integer.class).list()).hasMessageContaining("cannot be converted");
        assertThatThrownBy(() -> db.oql("SELECT a FROM Animal a", Dog.class).list()).hasMessageContaining("is not a");
        assertThatThrownBy(() -> db.oql("SELECT p FROM Person p").limit(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.oql("SELECT p FROM Person p").offset(-1)).isInstanceOf(IllegalArgumentException.class);
        OqlException e = new OqlException("x");
        assertThat(e.line()).isEqualTo(-1);
        assertThat(e.column()).isEqualTo(-1);
    }

    private void assertError(String q, String message, int line) {
        assertThatThrownBy(() -> db.oql(q).rows()).as(q).isInstanceOf(OqlException.class).hasMessageContaining(message)
                .satisfies(t -> {
                    if (line > 0) {
                        assertThat(((OqlException) t).line()).isEqualTo(line);
                    }
                });
    }

    @Test
    void conversions() {
        assertThat(OqlQuery.convert(5L, int.class)).isEqualTo(5);
        assertThat(OqlQuery.convert(5, long.class)).isEqualTo(5L);
        assertThat(OqlQuery.convert(5, double.class)).isEqualTo(5.0);
        assertThat(OqlQuery.convert(5, float.class)).isEqualTo(5f);
        assertThat(OqlQuery.convert(5, short.class)).isEqualTo((short) 5);
        assertThat(OqlQuery.convert(5, byte.class)).isEqualTo((byte) 5);
        assertThat(OqlQuery.convert(7L, Ref.class)).isEqualTo(Ref.of(7));
        assertThat(OqlQuery.convert("HIGH", Level.class)).isEqualTo(Level.HIGH);
        assertThat(OqlQuery.convert(1, String.class)).isEqualTo("1");
        assertThat(OqlQuery.convert(true, boolean.class)).isEqualTo(true);
        assertThat(OqlQuery.convert(null, String.class)).isNull();
        assertThatThrownBy(() -> OqlQuery.convert(null, int.class)).isInstanceOf(OqlException.class);
        assertThatThrownBy(() -> OqlQuery.convert('c', int.class)).isInstanceOf(OqlException.class);
        assertThat(Eval.compare(LocalDate.of(2000, 1, 1), "2000-01-01")).isZero();
        assertThat(Eval.compare(LocalDateTime.of(2000, 1, 1, 0, 0), "2000-01-01 00:00:00")).isZero();
        assertThat(Eval.compare("2000-01-01T00:00:00Z", Instant.EPOCH)).isPositive();
        assertThat(Eval.compare(List.of(1), List.of(1))).isZero();
        assertThat(Eval.compare(List.of(1), List.of(2))).isNotZero();
        assertThat(Eval.like("abc", "a%c", null)).isTrue();
        assertThat(Eval.prefixOf("abc")).isNull();
        assertThat(Eval.prefixOf("a_c%")).isNull();
    }
}
