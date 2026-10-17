package com.github.kookaburra.readme;

import com.github.kookaburra.BackupInfo;
import com.github.kookaburra.Config;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.model.Account;
import com.github.kookaburra.model.Geo;
import com.github.kookaburra.model.Item;
import com.github.kookaburra.model.Money;
import com.github.kookaburra.model.Place;
import com.github.kookaburra.model.Route;
import com.github.kookaburra.model.Shipment;
import com.github.kookaburra.readme.Book.Genre;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the code shown in README.md and the blog posts, so the documented outputs stay true.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class ReadmeExamplesTest {

    @Test
    void quickStart() {
        try (ObjectDB db = TestDbs.create()) {
            db.batch()
              .insert(new Book("Dune", "Frank Herbert", Genre.SCIFI, 1965, 9.99, "classic", "desert"))
              .insert(new Book("Neuromancer", "William Gibson", Genre.SCIFI, 1984, 12.50, "cyberpunk"))
              .insert(new Book("Hyperion", "Dan Simmons", Genre.SCIFI, 1989, 14.20))
              .commit();

            List<String> titles = db.query(Book.class)
                    .where(Book::getGenre).eq(Genre.SCIFI)
                    .where(Book::getYear).ge(1960)
                    .orderBy(Book::getYear).desc()
                    .limit(2)
                    .list().stream().map(Book::getTitle).toList();
            assertThat(titles).containsExactly("Hyperion", "Neuromancer");

            Book dune = db.query(Book.class).where(Book::getTitle).eq("Dune").first().orElseThrow();
            dune.setPrice(11.99);
            db.update(dune);
            assertThat(db.query(Book.class).where(Book::getTags).contains("classic").list().get(0).getPrice()).isEqualTo(11.99);
            assertThat(db.dynamic("Book").from().where("author").startsWith("Dan").list().get(0).get("title")).isEqualTo("Hyperion");
        }
    }

    @Test
    void transactions() {
        try (ObjectDB db = TestDbs.create(Config.defaults().withTransactions(true))) {
            long from = db.insert(new Account("ann", 100));
            long to = db.insert(new Account("bob", 0));

            try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
                Account a = tx.get(Account.class, from);
                tx.update(a.balance(a.getBalance() - 30));
                tx.increment(Account.class, to, "balance", 30);
                assertThat(tx.query(Account.class).where(Account::getOwner).eq("ann").list().get(0).getBalance()).isEqualTo(70);
                tx.commit();
            }
            db.inTransaction(tx -> tx.increment(Account.class, to, "balance", 5));
            assertThat(db.get(Account.class, from).getBalance()).isEqualTo(70);
            assertThat(db.get(Account.class, to).getBalance()).isEqualTo(35);

            Transaction t1 = db.begin();
            Transaction t2 = db.begin();
            t1.update(t1.get(Account.class, from).balance(1));
            t2.update(t2.get(Account.class, from).balance(2));
            t1.commit();
            assertThatThrownBy(t2::commit).isInstanceOf(ConflictException.class);
        }
        try (ObjectDB db = TestDbs.create()) {
            assertThatThrownBy(db::begin).hasMessageContaining("kookaburra.transactions=true");
        }
    }

    @Test
    void nestedObjects() {
        try (ObjectDB db = TestDbs.create()) {
            Place sydney = new Place("Sydney", "George St", new Geo(-33.87, 151.21));
            Place perth = new Place("Perth", "Hay St", new Geo(-31.95, 115.86));
            db.insert(new Shipment("S-1", new Route(sydney, perth, 2))
                    .items(List.of(new Item("apple", 3, new Money(250, "AUD")), new Item("pear", 1, new Money(400, "AUD")))));
            db.insert(new Shipment("S-2", new Route(perth, sydney, 1)).items(List.of(new Item("kiwi", 6, new Money(90, "AUD")))));

            assertThat(db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").rows())
                    .extracting(r -> r.get(0)).containsExactly("S-1");
            assertThat(db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").list())
                    .extracting(Shipment::getCode).containsExactly("S-1");
            assertThat(db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").rows())
                    .extracting(r -> r.get(0)).containsExactly("S-2");
        }
    }
    @Test
    void constraintsDecimalsAndCallbacks() {
        try (ObjectDB db = TestDbs.create()) {
            long ann = db.insert(new Customer("Ann", "ann@example.com"));
            db.insert(new Order("A-1", ann, "19.90"));
            db.insert(new Order("A-2", ann, "10.15"));

            assertThatThrownBy(() -> db.insert(new Order("A-1", ann, "5")))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageStartingWith("duplicate value 'A-1' for unique Order.number");
            assertThatThrownBy(() -> db.insert(new Customer("Ann 2", "ann@example.com")))
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(db.query(Order.class).sum(where(Order::getTotal))).isEqualTo(new BigDecimal("30.05"));
            assertThat(db.query(Order.class).where(Order::getTotal).eq(new BigDecimal("19.9")).count()).isEqualTo(1);
            assertThat(db.query(Order.class).list()).allMatch(o -> o.getCreated() != null);   // @PrePersist
            assertThat(db.referrers(ann)).hasSize(2);
            assertThat(db.referrers(Order.class, ann)).extracting(Order::getNumber).containsExactlyInAnyOrder("A-1", "A-2");

            db.delete(Customer.class, ann);                                   // ON DELETE CASCADE
            assertThat(db.query(Order.class).count()).isZero();
        }
    }

    @Test
    void serializablePreventsWriteSkew() {
        try (ObjectDB db = TestDbs.create(Config.defaults().withTransactions(true))) {
            long alice = db.insert(new Doctor("Alice"));
            long bob = db.insert(new Doctor("Bob"));
            TxOptions serializable = TxOptions.defaults().isolation(Isolation.SERIALIZABLE);
            try (Transaction t1 = db.begin(serializable); Transaction t2 = db.begin(serializable)) {
                for (Transaction tx : List.of(t1, t2)) {
                    assertThat(tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count()).isEqualTo(2);
                }
                t1.update(t1.get(Doctor.class, alice).offCall());
                t2.update(t2.get(Doctor.class, bob).offCall());
                t1.commit();
                assertThatThrownBy(t2::commit).isInstanceOf(ConflictException.class).hasMessageContaining("serialization failure");
            }
            db.inTransaction(serializable, tx -> {
                long onCall = tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count();
                if (onCall > 1) {
                    tx.update(tx.get(Doctor.class, bob).offCall());
                }
            });
            assertThat(db.query(Doctor.class).where(Doctor::isOnCall).eq(true).count()).isEqualTo(1);
        }
    }

    @Test
    void onlineBackup(@TempDir Path tmp) {
        try (ObjectDB db = TestDbs.create()) {
            db.batch().insert(new Book("Dune", "Frank Herbert", Genre.SCIFI, 1965, 9.99))
                    .insert(new Book("Hyperion", "Dan Simmons", Genre.SCIFI, 1989, 14.20)).commit();
            BackupInfo info = db.backup(tmp.resolve("backups").resolve("2026-10-17"));
            assertThat(info.objects()).isEqualTo(2);
            assertThat(info.seq()).isEqualTo(1);                     // as shown in the README
            assertThat(info.segments()).isEqualTo(1);
            try (ObjectDB copy = ObjectDB.open(info.path())) {
                assertThat(copy.query(Book.class).count()).isEqualTo(2);
            }
        }
    }
}
