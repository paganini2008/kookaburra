package com.github.kookaburra.text;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.FullText;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.model.Article;
import com.github.kookaburra.oql.OqlException;
import com.github.kookaburra.query.Neighbor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Full-text search: analyzers (multilingual words, English rules, custom ones), {@code matches} with and without an
 * index (same results), BM25 relevance against the formula, hybrid fusion with vectors, OQL {@code MATCH} /
 * {@code SCORE}, explain ({@link FullTextDiskTest} runs the same on disk).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class FullTextTest {

    protected ObjectDB db;

    protected Config config() {
        return Config.defaults().withTransactions(true).withEmbedder(t -> t.contains("bread") ? new float[]{1, 0} : new float[]{0, 1});
    }

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    /** Disk variant: flush, close and reopen. */
    protected void checkpoint() {
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static List<String> bodies(List<Article> l) {
        return l.stream().map(Article::getBody).toList();
    }

    // ---------------------------------------------------------------- analyzers

    @Test
    void analyzers() {
        Analyzer english = Analyzers.get(null);
        assertThat(english.words("The Quick brown foxes jumped over John's stories, and the dogs' bus"))
                .containsExactly("quick", "brown", "fox", "jump", "over", "john", "stori", "dog", "bu");   // Porter, as Lucene
        assertThat(english.words("CAFÉ naïve Москва Ελλάδα مرحبا 全文索引 iPhone15手机 한국어"))
                .containsExactly("café", "naïve", "москва", "ελλάδα", "مرحبا", "全文", "文索", "索引", "iphone15", "手机", "한국", "국어");
        assertThat(english.words("猫")).containsExactly("猫");
        assertThat(english.words(null)).isEmpty();
        assertThat(english.words("the of and")).isEmpty();
        Analyzer standard = Analyzers.get(Analyzers.STANDARD);
        assertThat(standard.words("The stories")).containsExactly("the", "stories");
        assertThat(standard.words("x".repeat(100)).get(0)).hasSize(StandardAnalyzer.MAX_WORD);
        assertThat(standard.frequencies("a b a")).containsEntry("a", 2).containsEntry("b", 1);
        // the examples of Porter's paper and Lucene's PorterStemmer
        String[][] pairs = {{"caresses", "caress"}, {"ponies", "poni"}, {"ties", "ti"}, {"cats", "cat"}, {"feed", "feed"},
                {"agreed", "agre"}, {"plastered", "plaster"}, {"motoring", "motor"}, {"sing", "sing"}, {"conflated", "conflat"},
                {"troubled", "troubl"}, {"sized", "size"}, {"hopping", "hop"}, {"falling", "fall"}, {"hissing", "hiss"},
                {"failing", "fail"}, {"filing", "file"}, {"happy", "happi"}, {"sky", "sky"}, {"relational", "relat"},
                {"conditional", "condit"}, {"valenci", "valenc"}, {"digitizer", "digit"}, {"conformabli", "conform"},
                {"radicalli", "radic"}, {"differentli", "differ"}, {"vileli", "vile"}, {"analogousli", "analog"},
                {"vietnamization", "vietnam"}, {"predication", "predic"}, {"operator", "oper"}, {"feudalism", "feudal"},
                {"decisiveness", "decis"}, {"hopefulness", "hope"}, {"callousness", "callous"}, {"formaliti", "formal"},
                {"sensitiviti", "sensit"}, {"sensibiliti", "sensibl"}, {"triplicate", "triplic"}, {"formative", "form"},
                {"formalize", "formal"}, {"electriciti", "electr"}, {"electrical", "electr"}, {"hopeful", "hope"},
                {"goodness", "good"}, {"revival", "reviv"}, {"allowance", "allow"}, {"inference", "infer"},
                {"airliner", "airlin"}, {"gyroscopic", "gyroscop"}, {"adjustable", "adjust"}, {"defensible", "defens"},
                {"irritant", "irrit"}, {"replacement", "replac"}, {"adjustment", "adjust"}, {"dependent", "depend"},
                {"adoption", "adopt"}, {"homologou", "homolog"}, {"communism", "commun"}, {"activate", "activ"},
                {"angulariti", "angular"}, {"homologous", "homolog"}, {"effective", "effect"}, {"bowdlerize", "bowdler"},
                {"probate", "probat"}, {"rate", "rate"}, {"cease", "ceas"}, {"controll", "control"}, {"roll", "roll"},
                {"connected", "connect"}, {"connecting", "connect"}, {"connection", "connect"}, {"movies", "movi"},
                {"movie", "movi"}, {"generalizations", "gener"}, {"oscillators", "oscil"}, {"is", "is"}, {"cafés", "cafés"}};
        for (String[] p : pairs) {
            assertThat(PorterStemmer.stem(p[0])).as(p[0]).isEqualTo(p[1]);
        }

        assertThatThrownBy(() -> Analyzers.get("klingon")).hasMessageContaining("'klingon' is not registered");
        assertThatThrownBy(() -> Analyzers.register("english", standard)).hasMessageContaining("cannot be replaced");
        assertThatThrownBy(() -> Analyzers.register(" ", standard)).hasMessageContaining("needs a name");
        assertThatThrownBy(() -> Analyzers.register("x", null)).hasMessageContaining("needs a name");
    }

    @Test
    void queries() {
        TextQuery q = TextQuery.parse("Fresh breads bake* foo-ba* *");
        assertThat(q.words()).containsExactly("fresh", "bread", "foo");
        assertThat(q.prefixes()).containsExactly("bake", "ba");
        assertThat(q.toString()).isEqualTo("fresh bread foo bake* ba*");
        assertThat(q.matches("fresh bread, baked foo bar", true)).isTrue();
        assertThat(q.matches("fresh bread", true)).isFalse();
        assertThat(q.matches("bar", false)).isTrue();
        assertThat(q.matches((String) null, false)).isFalse();
        assertThat(TextQuery.parse("the stories", Analyzers.get("standard")).words()).containsExactly("the", "stories");
        assertThatThrownBy(() -> TextQuery.parse("the of !")).hasMessageContaining("has no words");
        assertThatThrownBy(() -> TextQuery.parse(null)).hasMessageContaining("null");
    }

    // ---------------------------------------------------------------- matches

    private void articles() {
        db.insert(new Article("en", "Fresh bread from the oven every morning", 1, 0));
        db.insert(new Article("en", "Bread, bread and more bread: a baker's diary", 0.9f, 0.1f));
        db.insert(new Article("en", "Oven cleaning tips", 0, 1));
        db.insert(new Article("de", "Frisches Brot und Brötchen", 0.8f, 0.2f));
        db.insert(new Article("zh", "全文索引让面包店的菜单更好找", 0.5f, 0.5f));
        db.insert(new Article("en", null));
        checkpoint();
    }

    @Test
    void matchesWithAndWithoutIndex() {
        articles();
        assertThat(bodies(db.from(Article.class).where(Article::getBody).matches("bread oven").list()))
                .containsExactly("Fresh bread from the oven every morning");
        assertThat(db.from(Article.class).where(Article::getBody).matchesAny("bread oven").count()).isEqualTo(3);
        assertThat(db.from(Article.class).where(Article::getBody).matches("bak*").count()).isEqualTo(1);          // baker
        assertThat(db.from(Article.class).where(Article::getBody).matches("Breads").count()).isEqualTo(2);        // stemmed
        assertThat(db.from(Article.class).where(Article::getBody).matches("bakers cleaned").count()).isZero();
        assertThat(db.from(Article.class).where(Article::getBody).matches("cleaning").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matches("面包").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matches("brot").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matches("bread").where(Article::getLang).eq("de").count()).isZero();
        assertThat(db.from(Article.class).where(Article::getRaw).matches("the").count()).isEqualTo(1);           // standard: no stop words
        // the same text without an index: rows are split on the fly, same answers
        for (String q : List.of("bread oven", "bak*", "Breads", "面包", "brot", "o*")) {
            assertThat(db.from(Article.class).where(Article::getPlain).matches(q).count()).as(q)
                    .isEqualTo(db.from(Article.class).where(Article::getBody).matches(q).count());
            assertThat(db.from(Article.class).where(Article::getPlain).matchesAny(q).count()).as(q)
                    .isEqualTo(db.from(Article.class).where(Article::getBody).matchesAny(q).count());
        }
        assertThat(db.from(Article.class).where(Article::getBody).matches("bread").explain()).contains("full-text index");     // in memory or in a segment
        assertThat(db.from(Article.class).where(Article::getPlain).matches("bread").explain()).contains("scan (words of each row)");
        assertThatThrownBy(() -> db.from(Article.class).where(Article::getLang).matches("the")).hasMessageContaining("no words");
        assertThatThrownBy(() -> db.from(Article.class).where(Article::getOid).matches("x")).hasMessageContaining("needs a string field");

        // updates and deletes: postings follow
        Article oven = db.from(Article.class).where(Article::getBody).matches("cleaning").first().orElseThrow();
        db.update(oven.body("Pizza stone care"));
        db.delete(db.from(Article.class).where(Article::getBody).matches("diary").first().orElseThrow());
        checkpoint();
        assertThat(db.from(Article.class).where(Article::getBody).matches("cleaning").count()).isZero();
        assertThat(db.from(Article.class).where(Article::getBody).matches("pizza").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matchesAny("bread oven").count()).isEqualTo(1);

        // dynamic access
        assertThat(db.dynamic(Article.class.getName()).from().where("body").matches("bread").count()).isEqualTo(1);
        assertThat(db.dynamic(Article.class.getName()).from().where("body").matchesAny("bread pizza").count()).isEqualTo(2);
    }

    @Test
    void differential() {
        Random rnd = new Random(5);
        String[] vocab = {"bread", "oven", "baker", "flour", "salt", "water", "yeast", "crust", "rye", "sourdough", "the", "and"};
        WriteBatch b = db.batch();
        for (int i = 0; i < 400; i++) {
            StringBuilder sb = new StringBuilder();
            for (int w = 0; w < 1 + rnd.nextInt(8); w++) {
                sb.append(vocab[rnd.nextInt(vocab.length)]).append(rnd.nextBoolean() ? "s " : " ");
            }
            b.insert(new Article(i % 2 == 0 ? "en" : "de", sb.toString()));
            if (i == 200) {
                b.commit();
                db.flush();
                b = db.batch();
            }
        }
        b.commit();
        checkpoint();
        for (String q : List.of("bread", "bread oven", "salt water yeast", "cr*", "rye sourdough", "baker*")) {
            Set<Long> indexed = new TreeSet<>();
            Set<Long> plain = new TreeSet<>();
            db.from(Article.class).where(Article::getBody).matches(q).list().forEach(n -> indexed.add(n.getOid()));
            db.from(Article.class).where(Article::getPlain).matches(q).list().forEach(n -> plain.add(n.getOid()));
            assertThat(indexed).as(q).isEqualTo(plain);
            List<Article> relevantIndexed = db.from(Article.class).search(Article::getBody, q, 15).list();
            List<Article> relevantPlain = db.from(Article.class).search(Article::getPlain, q, 15).list();
            assertThat(relevantIndexed).extracting(Article::getOid).as(q).isEqualTo(relevantPlain.stream().map(Article::getOid).toList());
        }
    }

    // ---------------------------------------------------------------- relevance

    @Test
    void bm25() {
        db.insert(new Article("en", "bread bread oven"));
        db.insert(new Article("en", "bread"));
        db.insert(new Article("en", "fresh salad with oil"));
        checkpoint();
        List<Neighbor<Article>> r = db.from(Article.class).search(Article::getBody, "bread").listWithDistance();
        assertThat(r).extracting(n -> n.value().getBody()).containsExactly("bread", "bread bread oven");
        // N = 3, df = 2, lengths 3 / 1 / 3 ("with" is a stop word), average 7 / 3
        double idf = Math.log(1 + (3 - 2 + 0.5) / (2 + 0.5));
        double avg = 7 / 3.0;
        double shortDoc = idf * 1 * 2.2 / (1 + 1.2 * (0.25 + 0.75 * 1 / avg));
        double longDoc = idf * 2 * 2.2 / (2 + 1.2 * (0.25 + 0.75 * 3 / avg));
        assertThat(r.get(0).score()).isCloseTo(shortDoc, within(1e-9));
        assertThat(r.get(1).score()).isCloseTo(longDoc, within(1e-9));
        assertThat(r.get(0).distance()).isNaN();
        assertThat(Bm25.term(1, 0, 3, 2)).isZero();
        assertThat(Bm25.term(1, 1, 3, 0)).isCloseTo(2.2 / 2.2, within(1e-9));

        // rarer words weigh more; search finds any word
        assertThat(bodies(db.from(Article.class).search(Article::getBody, "oven bread", 3).list())).startsWith("bread bread oven");
        assertThat(db.from(Article.class).search(Article::getBody, "salad", 5).count()).isEqualTo(1);
        assertThat(db.from(Article.class).search(Article::getBody, "nothing", 5).list()).isEmpty();
        assertThat(db.from(Article.class).search(Article::getBody, "bread", 5).skip(1).list()).hasSize(1);
        assertThat(db.from(Article.class).search(Article::getBody, "bread", 0).list()).isEmpty();
        assertThat(db.from(Article.class).where(Article::getLang).eq("de").search(Article::getBody, "bread", 5).list()).isEmpty();
        assertThat(db.from(Article.class).search("body", "bread").explain())
                .contains("Full-text search  body for 'bread', any word, BM25 relevance, full sort", "full-text index");
        assertThat(db.from(Article.class).search(Article::getPlain, "bread", 2).explain()).contains("[scan: words of each row]", "top-N heap");

        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "the")).hasMessageContaining("no words");
        assertThatThrownBy(() -> db.from(Article.class).search("lang", "x").limit(1).search("lang", "y")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.from(Article.class).search("embedding", "x").list()).hasMessageContaining("is not a string field");
        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "bread", 2).diverse()).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "bread", 2).exact()).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "bread", 2).numCandidates(5)).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "bread").rerank(Article::getBody).list())
                .hasMessageContaining("rerank() needs a limit");
    }

    @Test
    void hybrid() {
        articles();
        // vector order (bread ≈ [1, 0]): fresh bread, diary, Brot, 全文…, oven tips; text order: diary (3 × bread), fresh bread
        List<Neighbor<Article>> h = db.from(Article.class).nearest(Article::getEmbedding, "bread", 3).hybrid(Article::getBody).listWithDistance();
        assertThat(h).extracting(n -> n.value().getBody()).startsWith("Fresh bread from the oven every morning",
                "Bread, bread and more bread: a baker's diary");
        assertThat(h.get(0).score()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-12));
        assertThat(h.get(1).score()).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-12));
        // text only finds what vectors miss: a body with the word but a vector far away
        db.insert(new Article("en", "bread crumbs", 0, 1));
        checkpoint();
        List<Article> fused = db.from(Article.class).nearest(Article::getEmbedding, "bread", 6).hybrid("body", "crumbs", 6).list();
        assertThat(bodies(fused)).contains("bread crumbs");
        assertThat(db.from(Article.class).nearest(Article::getEmbedding, "bread", 3).hybrid(Article::getBody).explain())
                .contains("Hybrid  full-text search of body, reciprocal rank fusion k=60, max(20, 4 k) from each");
        assertThat(db.from(Article.class).nearest(Article::getEmbedding, new float[]{1, 0}, 3).hybrid("body", "bread", 0).list()).hasSize(3);

        assertThatThrownBy(() -> db.from(Article.class).nearest(Article::getEmbedding, new float[]{1, 0}, 3).hybrid(Article::getBody))
                .hasMessageContaining("needs the query text");
        assertThatThrownBy(() -> db.from(Article.class).search(Article::getBody, "bread", 2).hybrid(Article::getBody)).hasMessageContaining("needs nearest");
        assertThatThrownBy(() -> db.from(Article.class).nearest(Article::getEmbedding, "bread", 3).hybrid("body", null, -1))
                .hasMessageContaining("negative fetch");
        assertThatThrownBy(() -> db.from(Article.class).nearest(Article::getEmbedding, "bread", 3).hybrid("lang", "x", 0).hybrid("embedding", "x", 0).list())
                .hasMessageContaining("is not a string field");
    }

    // ---------------------------------------------------------------- OQL

    @Test
    void oql() {
        articles();
        assertThat(db.oql("SELECT n.body FROM Article n WHERE MATCH(n.body, 'bread oven')").rows()).hasSize(1);
        assertThat(db.oql("SELECT n.body FROM Article n WHERE MATCH(n.body, :q) AND n.lang = 'en'").param("q", "bread").rows()).hasSize(2);
        assertThat(db.oql("SELECT n.body FROM Article n WHERE NOT MATCH(n.body, 'bread')").rows()).hasSize(4);
        assertThat(db.oql("SELECT n.body FROM Article n WHERE MATCH(UPPER(n.body), 'OVEN')").rows()).hasSize(2);       // evaluated per row
        assertThat(db.oql("EXPLAIN SELECT n FROM Article n WHERE MATCH(n.body, 'bread')").explain()).contains("MATCHES 'bread'", "full-text index");

        var top = db.oql("SELECT n.body, SCORE(n.body, 'bread') AS s FROM Article n ORDER BY SCORE(n.body, 'bread') DESC LIMIT 2").rows();
        assertThat(top).hasSize(2);
        assertThat(top.get(0).get(0)).isEqualTo("Bread, bread and more bread: a baker's diary");
        assertThat((Double) top.get(0).get(1)).isGreaterThan((Double) top.get(1).get(1));
        assertThat(db.oql("SELECT n FROM Article n WHERE n.lang = 'en' ORDER BY SCORE(n.body, 'oven') DESC").list()).hasSize(2);
        assertThat(db.oql("SELECT n.body FROM Article n ORDER BY SCORE(n.body, 'bread') DESC LIMIT 1 OFFSET 1").rows().get(0).get(0))
                .isEqualTo("Fresh bread from the oven every morning");
        assertThat(db.oql("EXPLAIN SELECT n FROM Article n ORDER BY SCORE(n.body, 'bread') DESC LIMIT 3").explain())
                .contains("Full-text search  body for 'bread'", "[ORDER BY pushed down: BM25 relevance]");

        assertThatThrownBy(() -> db.oql("SELECT SCORE(n.body, 'bread') FROM Article n").rows())
                .isInstanceOf(OqlException.class).hasMessageContaining("SCORE() needs ORDER BY SCORE(n.body, 'bread') DESC");
        assertThatThrownBy(() -> db.oql("SELECT n FROM Article n WHERE MATCH(n.body, 'the')").list())
                .isInstanceOf(OqlException.class).hasMessageContaining("no words");
        assertThatThrownBy(() -> db.oql("SELECT n FROM Article n ORDER BY SCORE(n.body, 'of') DESC").list())
                .isInstanceOf(OqlException.class).hasMessageContaining("no words");
        assertThatThrownBy(() -> db.oql("SELECT n FROM Article n WHERE MATCH(n.body)").list())
                .isInstanceOf(OqlException.class).hasMessageContaining("MATCH takes 2 arguments");
        assertThatThrownBy(() -> db.oql("SELECT n FROM Article n WHERE MATCH(UPPER(n.body), 'the')").list())
                .isInstanceOf(OqlException.class).hasMessageContaining("MATCH: ");
        // ascending SCORE is not a relevance search: sorted in memory, needs the scores
        assertThatThrownBy(() -> db.oql("SELECT n FROM Article n ORDER BY SCORE(n.body, 'bread')").list()).isInstanceOf(OqlException.class);
    }

    // ---------------------------------------------------------------- schema and custom analyzers

    /** Comma-separated tags as words. */
    @Entity
    public static class Tagged {
        @Id long oid;
        @FullText(analyzer = "csv-tags") String tags;

        public Tagged() {
        }

        Tagged(String tags) {
            this.tags = tags;
        }

        public String getTags() {
            return tags;
        }
    }

    @Entity
    public static class BadNumber {
        @Id long oid;
        @FullText int n;
    }

    @Entity
    public static class BadMix {
        @Id long oid;
        @FullText @Index(IndexKind.BITMAP) String s;
    }

    @Entity
    public static class BadUnique {
        @Id long oid;
        @FullText @Unique String s;
    }

    @Test
    void customAnalyzerAndSchema() {
        Analyzers.register("csv-tags", text -> {
            List<String> out = new ArrayList<>();
            for (String t : text.split(",")) {
                if (!t.isBlank()) {
                    out.add(t.strip().toLowerCase());
                }
            }
            return out;
        });
        db.insert(new Tagged("New York, Los Angeles"));
        db.insert(new Tagged("York"));
        checkpoint();
        assertThat(db.from(Tagged.class).where(Tagged::getTags).matches("new york").count()).isEqualTo(1);
        assertThat(db.from(Tagged.class).where(Tagged::getTags).matches("york").count()).isEqualTo(1);
        assertThat(db.dynamic(Tagged.class.getName()).field("tags").typeDef().name()).isEqualTo("csv-tags");   // kept in the schema
        assertThatThrownBy(() -> db.insert(new BadNumber())).hasMessageContaining("is not supported (String fields)")
                .isInstanceOf(SchemaException.class);
        assertThatThrownBy(() -> db.insert(new BadMix())).hasMessageContaining("cannot be combined with @Index(BITMAP)");
        assertThatThrownBy(() -> db.insert(new BadUnique())).hasMessageContaining("@Unique on");
    }

    @Test
    void transactions() {
        articles();
        db.inTransaction(tx -> {
            tx.insert(new Article("en", "bread pudding recipe"));
            assertThat(tx.from(Article.class).where(Article::getBody).matches("pudding").count()).isEqualTo(1);   // read your writes
            assertThat(tx.from(Article.class).search(Article::getBody, "pudding", 3).list()).hasSize(1);
        });
        assertThat(db.from(Article.class).where(Article::getBody).matches("pudding").count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- phrases and highlighting

    @Test
    void tokensWithPositions() {
        List<Token> t = Analyzers.get(null).tokens("The dog's bone, in the yard");
        assertThat(t).extracting(Token::word).containsExactly("dog", "bone", "yard");
        assertThat(t).extracting(Token::position).containsExactly(1, 2, 5);           // stop words keep their place
        assertThat(t.get(0).start()).isEqualTo(4);
        assertThat(t.get(0).end()).isEqualTo(7);
        List<Token> cjk = Analyzers.get("standard").tokens("全文索引 ok");
        assertThat(cjk).extracting(Token::word).containsExactly("全文", "文索", "索引", "ok");
        assertThat(cjk.get(2).start()).isEqualTo(2);
        assertThat(cjk.get(2).end()).isEqualTo(4);
        Analyzer custom = text -> List.of(text.split(" "));
        assertThat(custom.tokens("a b")).extracting(Token::start).containsExactly(-1, -1);
    }

    @Test
    void phrases() {
        articles();
        assertThat(bodies(db.from(Article.class).where(Article::getBody).matches("\"fresh bread\"").list()))
                .containsExactly("Fresh bread from the oven every morning");
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"bread fresh\"").count()).isZero();   // order counts
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"bread from the oven\"").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"bread from oven\"").count()).isZero();  // the gap counts
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"面包店\"").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"oven cleaning\" tips").count()).isEqualTo(1);
        assertThat(db.from(Article.class).where(Article::getBody).matchesAny("\"fresh bread\" brot").count()).isEqualTo(2);
        assertThat(db.from(Article.class).where(Article::getBody).matches("\"bread\"").count()).isEqualTo(2);         // one word
        assertThat(db.from(Article.class).where(Article::getBody).matches("bread \"unclosed").count()).isZero();
        for (String q : List.of("\"fresh bread\"", "\"bread from the oven\"", "\"bread from oven\"", "\"面包店\"")) {
            assertThat(db.from(Article.class).where(Article::getPlain).matches(q).count()).as(q)
                    .isEqualTo(db.from(Article.class).where(Article::getBody).matches(q).count());
        }
        assertThat(bodies(db.from(Article.class).search(Article::getBody, "\"more bread\"", 5).list()))
                .containsExactly("Bread, bread and more bread: a baker's diary");
        assertThat(db.from(Article.class).search(Article::getPlain, "\"more bread\"", 5).list()).hasSize(1);
        assertThat(db.oql("SELECT n.body FROM Article n WHERE MATCH(n.body, '\"fresh bread\"')").rows()).hasSize(1);
        TextQuery q = TextQuery.parse("\"fresh bread\" oven bak*");
        assertThat(q.toString()).isEqualTo("oven \"fresh bread\" bak*");
        assertThatThrownBy(() -> TextQuery.parse("\"the of\"")).hasMessageContaining("no words");
    }

    @Test
    void highlight() {
        TextQuery q = TextQuery.parse("bread oven");
        assertThat(q.highlight("Fresh bread from the oven every morning"))
                .isEqualTo("Fresh <em>bread</em> from the <em>oven</em> every morning");
        assertThat(TextQuery.parse("\"fresh bread\"").highlight("Fresh bread from the oven"))
                .isEqualTo("<em>Fresh bread</em> from the oven");
        assertThat(TextQuery.parse("bak*").highlight("The baker baked")).isEqualTo("The <em>baker</em> <em>baked</em>");
        assertThat(TextQuery.parse("面包").highlight("全文索引让面包店的菜单")).isEqualTo("全文索引让<em>面包</em>店的菜单");
        assertThat(q.highlight(null)).isNull();
        String filler = "lorem ipsum dolor sit amet ".repeat(12);
        String longText = filler + "fresh bread here " + filler + "and an oven there " + filler;
        String h = q.highlight(longText);
        assertThat(h).contains("<em>bread</em>", "<em>oven</em>", " … ").startsWith("… ").endsWith(" …");
        assertThat(h.length()).isLessThan(longText.length() / 2);
        assertThat(q.highlight(longText, 60, 1)).contains("<em>bread</em>").doesNotContain("<em>oven</em>");
        assertThat(q.highlight(filler + filler)).endsWith(" …").doesNotContain("<em>");        // nothing matched: the start
        Analyzer custom = text -> List.of(text.toLowerCase().split(" "));
        assertThat(TextQuery.parse("bread", custom).highlight("Bread here")).isEqualTo("Bread here");   // no offsets: unmarked

        articles();
        var rows = db.oql("SELECT HIGHLIGHT(n.body, 'oven') FROM Article n WHERE MATCH(n.body, 'oven') ORDER BY n.body").rows();
        assertThat(rows).extracting(r -> r.get(0)).containsExactly("Fresh bread from the <em>oven</em> every morning",
                "<em>Oven</em> cleaning tips");
        assertThat(db.oql("SELECT HIGHLIGHT(n.raw, 'the') FROM Article n WHERE n.lang = 'en' AND MATCH(n.raw, 'the')").rows()
                .get(0).get(0)).asString().contains("<em>the</em>");                                   // the field's analyzer
        assertThatThrownBy(() -> db.oql("SELECT HIGHLIGHT(n.body, 'of') FROM Article n").rows()).hasMessageContaining("HIGHLIGHT: ");
    }
}
