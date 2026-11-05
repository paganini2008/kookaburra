package com.github.kookaburra.readme;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.SchemaExport;
import com.github.kookaburra.model.Article;
import com.github.kookaburra.model.Geo;
import com.github.kookaburra.model.Item;
import com.github.kookaburra.model.Money;
import com.github.kookaburra.model.Place;
import com.github.kookaburra.model.Route;
import com.github.kookaburra.model.Shipment;
import com.github.kookaburra.query.QueryPlan;
import com.github.kookaburra.readme.Book.Genre;
import com.github.kookaburra.text.Analyzers;
import com.github.kookaburra.text.TextQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The code of the developer guide (docs/developer-guide.md, Part A) that the README examples do not run: operators
 * and paging, element conditions, snapshots, batches with durability, transactions with options, full-text helpers
 * and a custom analyzer, OQL statements, plans, schema export and the dynamic API. Runs in memory and on disk (the
 * disk suite).
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
class DeveloperGuideExamplesTest {

    @TempDir
    Path dir;

    private static void books(ObjectDB db) {
        db.batch()
          .insert(new Book("Dune", "Frank Herbert", Genre.SCIFI, 1965, 9.99, "classic", "desert"))
          .insert(new Book("Neuromancer", "William Gibson", Genre.SCIFI, 1984, 12.50, "cyberpunk"))
          .insert(new Book("Hyperion", "Dan Simmons", Genre.SCIFI, 1989, 14.20))
          .insert(new Book("The Hobbit", "J.R.R. Tolkien", Genre.FANTASY, 1937, 10.00, "classic"))
          .commit();
    }

    @Test
    void queriesAndPaging() {
        try (ObjectDB db = TestDbs.create()) {
            books(db);
            List<Book> books = db.query(Book.class)
                    .where(Book::getGenre).eq(Genre.SCIFI)
                    .where(Book::getYear).between(1960, 1990)
                    .where(Book::getTitle).startsWith("H")
                    .orderBy(Book::getYear).desc()
                    .limit(10)
                    .list();
            assertThat(books).extracting(Book::getTitle).containsExactly("Hyperion");
            assertThat(db.query(Book.class).where(Book::getGenre).notEq(Genre.SCIFI).count()).isEqualTo(1);
            assertThat(db.query(Book.class).orderBy(Book::getYear).asc().skip(1).limit(2).list())
                    .extracting(Book::getTitle).containsExactly("Dune", "Neuromancer");
            assertThat(db.query(Book.class).where(Book::getTags).contains("classic").count()).isEqualTo(2);

            try (Snapshot s = db.snapshot()) {
                db.insert(new Book("Later", "X", Genre.SCIFI, 2001, 1));
                assertThat(s.from(Book.class).count()).isEqualTo(4);                 // one moment
                assertThat(s.from(Book.class).where(Book::getYear).ge(2000).list()).isEmpty();
            }
            assertThat(db.query(Book.class).count()).isEqualTo(5);

            WriteBatch bulk = db.batch().durability(Durability.ASYNC);
            IntStream.range(0, 50).forEach(i -> bulk.insert(new Book("B" + i, "Y", Genre.CRIME, 2000 + i, 5)));
            bulk.commit();
            assertThat(db.query(Book.class).where(Book::getGenre).eq(Genre.CRIME).count()).isEqualTo(50);

            assertThat(db.oql("SELECT b.title, CASE WHEN b.price > 10 THEN 'high' ELSE 'low' END AS band"
                    + " FROM Book b WHERE b.title = 'Dune'").rows()).extracting(r -> r.get(1)).containsExactly("low");
            assertThat(db.oql("SELECT b FROM Book b WHERE b.genre = 'FANTASY'", Book.class).list())
                    .extracting(Book::getTitle).containsExactly("The Hobbit");
            assertThat(db.oql("DELETE FROM Book b WHERE b.year < 1900").execute()).isZero();
            QueryPlan plan = db.oql("EXPLAIN SELECT b FROM Book b WHERE b.genre = 'SCIFI'").plan();
            assertThat(plan.render()).contains("genre");
        }
    }

    @Test
    void elementConditionsAndTransactions() {
        try (ObjectDB db = TestDbs.create(Config.defaults().withTransactions(true))) {
            Place p = new Place("Perth", "Hay St", new Geo(-31.95, 115.86));
            db.insert(new Shipment("S-1", new Route(p, p, 1))
                    .items(List.of(new Item("A", 3, new Money(1, "AUD")), new Item("B", 9, new Money(1, "AUD")))));
            db.insert(new Shipment("S-2", new Route(p, p, 1)).items(List.of(new Item("A", 7, new Money(1, "AUD")))));
            assertThat(db.query(Shipment.class)
                    .whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq("A").where(Item::getQty).gt(5))
                    .list()).extracting(Shipment::getCode).containsExactly("S-2");

            int n = db.inTransactionResult(TxOptions.defaults().isolation(Isolation.SERIALIZABLE)
                    .durability(Durability.SYNC), tx -> (int) tx.query(Shipment.class).count());
            assertThat(n).isEqualTo(2);
        }
    }

    @Test
    void fullTextHelpers() {
        try (ObjectDB db = TestDbs.create()) {
            db.insert(new Article("en", "Fresh bread every morning from the oven"));
            db.insert(new Article("en", "Chocolate cake recipes"));
            assertThat(db.query(Article.class).where(Article::getBody).matchesAny("bread cake").count()).isEqualTo(2);
            assertThat(db.query(Article.class).where(Article::getBody).matches("\"fresh bread\"").count()).isEqualTo(1);
            assertThat(db.query(Article.class).where(Article::getBody).matches("bre*").count()).isPositive();
            assertThat(TextQuery.parse("bread").highlight("Fresh bread every morning")).contains("<em>bread</em>");
            Analyzers.register("lower-guide", text -> List.of(text.toLowerCase(Locale.ROOT).split("\\W+")));
            assertThat(Analyzers.get("lower-guide").words("Hello, World")).containsExactly("hello", "world");
        }
    }

    @Test
    void schemaExportAndDynamic() throws Exception {
        try (ObjectDB db = TestDbs.create()) {
            books(db);
            SchemaExport java = db.exportSchema((String) null);
            assertThat(java.files()).extracting(SchemaExport.SourceFile::path).contains("com/github/kookaburra/readme/Book.java");
            List<Path> written = db.exportSchema(dir.resolve("src"), "com.acme.model");
            assertThat(written).isNotEmpty().allMatch(Files::exists);

            DynamicCollection books = db.dynamic("Book");
            DynamicObject b = books.from().where("author").startsWith("J").first().orElseThrow();
            books.update(b.set("price", 8.5));
            assertThat(db.query(Book.class).where(Book::getTitle).eq("The Hobbit").first().orElseThrow().getPrice()).isEqualTo(8.5);
        }
    }
}
