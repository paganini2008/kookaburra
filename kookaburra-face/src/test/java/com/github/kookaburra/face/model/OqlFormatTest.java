package com.github.kookaburra.face.model;

import com.github.kookaburra.oql.grammar.OqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OQL formatting: clause lines, AND / OR indentation, BETWEEN, long select lists, sub-queries, joins, statements,
 * comments and strings kept, idempotence, and the token stream unchanged (only spacing and keyword case).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class OqlFormatTest {

    /** Tokens without whitespace, keywords lower-cased: formatting must not change them. */
    private static List<String> tokens(String text) {
        OqlLexer lexer = new OqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        List<String> out = new ArrayList<>();
        for (Token t : lexer.getAllTokens()) {
            if (t.getType() != OqlLexer.WS) {
                out.add(t.getType() == OqlLexer.STRING || t.getType() == OqlLexer.QUOTED_IDENT
                        || t.getType() == OqlLexer.LINE_COMMENT || t.getType() == OqlLexer.BLOCK_COMMENT
                        ? t.getText() : t.getText().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static String check(String in) {
        String out = OqlFormat.format(in);
        assertThat(tokens(out)).as(in).isEqualTo(tokens(in));
        assertThat(OqlFormat.format(out)).as("idempotent").isEqualTo(out);
        return out;
    }

    @Test
    void clausesConditionsAndCase() {
        assertThat(check("select c from Customer c where c.tier = 'gold' and c.age between 30 and 40 or c.name like 'A%' order by c.age desc limit 10 offset 5"))
                .isEqualTo("""
                        SELECT c
                        FROM Customer c
                        WHERE c.tier = 'gold'
                            AND c.age BETWEEN 30 AND 40
                            OR c.name LIKE 'A%'
                        ORDER BY c.age DESC
                        LIMIT 10
                        OFFSET 5""");
        assertThat(check("select p.city,count(*),avg(p.age) from Person p group by p.city having count(*)>2"))
                .isEqualTo("""
                        SELECT p.city, COUNT(*), AVG(p.age)
                        FROM Person p
                        GROUP BY p.city
                        HAVING COUNT(*) > 2""");
        assertThat(check("update Product p set p.price=p.price*0.9,p.title=upper(p.title) where p.price>-1"))
                .isEqualTo("""
                        UPDATE Product p
                        SET p.price = p.price * 0.9, p.title = upper(p.title)
                        WHERE p.price > -1""");
        assertThat(check("delete from Purchase x where x.oid in (1, 2, 3)")).isEqualTo("""
                DELETE
                FROM Purchase x
                WHERE x.oid IN (1, 2, 3)""");
    }

    @Test
    void listsJoinsSubqueriesAndStatements() {
        assertThat(check("SELECT c.name, c.email, c.address.city, c.address.geo.lat, c.address.geo.lon, c.tier FROM Customer c"))
                .isEqualTo("""
                        SELECT
                            c.name,
                            c.email,
                            c.address.city,
                            c.address.geo.lat,
                            c.address.geo.lon,
                            c.tier
                        FROM Customer c""");
        assertThat(check("select p from Purchase p join p.customer c where c.tier = 'GOLD'")).isEqualTo("""
                SELECT p
                FROM Purchase p
                JOIN p.customer c
                WHERE c.tier = 'GOLD'""");
        assertThat(check("select p from Purchase p left join p.customer c")).contains("\nLEFT JOIN p.customer c");
        assertThat(check("select p from Product p where p.oid in (select l.product from Line l where l.qty > 2) and p.price > 1"))
                .isEqualTo("""
                        SELECT p
                        FROM Product p
                        WHERE p.oid IN (
                            SELECT l.product
                            FROM Line l
                            WHERE l.qty > 2
                        )
                            AND p.price > 1""");
        assertThat(check("begin; update Customer c set c.age = 1 where c.name = :n; commit")).isEqualTo("""
                BEGIN;

                UPDATE Customer c
                SET c.age = 1
                WHERE c.name = :n;

                COMMIT""");
        assertThat(check("explain analyze select p from Product p where p.oid = ?1")).isEqualTo("""
                EXPLAIN ANALYZE SELECT p
                FROM Product p
                WHERE p.oid = ?1""");
    }

    @Test
    void stringsCommentsAndFunctions() {
        String out = check("-- top products\nselect p.title, cosine_distance(p.embedding, '[1,  0, 0, 0]') as d /* keep */ from Product p order by d");
        assertThat(out).startsWith("-- top products\nSELECT").contains("'[1,  0, 0, 0]'", "/* keep */", "cosine_distance(p.embedding")
                .contains("\nORDER BY d");
        assertThat(check("select p from Product p where match(p.description, 'select from where') and p.title = 'it''s'"))
                .contains("match(p.description, 'select from where')", "'it''s'");
        assertThat(check("SELECT `order`.x FROM `Order` `order`")).contains("`Order` `order`");
        assertThat(OqlFormat.format("select # from")).isEqualTo("select # from");          // unknown character: unchanged
        assertThat(OqlFormat.format("")).isEmpty();
        assertThat(check("select case when p.price > 10 then 'big' else 'small' end from Product p"))
                .startsWith("SELECT CASE WHEN p.price > 10 THEN 'big' ELSE 'small' END");
    }
}
