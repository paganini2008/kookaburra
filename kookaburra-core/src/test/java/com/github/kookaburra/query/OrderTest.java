package com.github.kookaburra.query;

import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class OrderTest {

    private static List<Person> people(long seed) {
        Random r = new Random(seed);
        String[] cities = {"Sydney", "Perth", null, "Darwin", "Ärhus", "😀", "￿"};
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Person p = new Person("p" + r.nextInt(50), cities[r.nextInt(cities.length)], r.nextInt(20));
            p.score(r.nextInt(4) == 0 ? null : r.nextInt(10));
            p.weight(new double[]{-0.0, 0.0, Double.NaN, 1.5, -3}[r.nextInt(5)]);
            list.add(p);
        }
        return list;
    }

    @Test
    void matchesEquivalentJdkComparators() {
        for (long seed = 0; seed < 20; seed++) {
            List<Person> data = people(seed);
            check(data, where(Person::getAge).asc(), Comparator.comparingInt(Person::getAge));
            check(data, where(Person::getCity).asc(),
                    Comparator.comparing(Person::getCity, Comparator.nullsLast(Comparator.naturalOrder())));
            check(data, where(Person::getCity).desc(),
                    Comparator.comparing(Person::getCity, Comparator.nullsFirst(Comparator.<String>reverseOrder())));
            check(data, where(Person::getScore).asc().nullsFirst(),
                    Comparator.comparing(Person::getScore, Comparator.nullsFirst(Comparator.naturalOrder())));
            check(data, where(Person::getWeight).desc(),
                    Comparator.comparingDouble(Person::getWeight).reversed());
            check(data, where(Person::getCity).asc().thenComparing(where(Person::getAge).desc()),
                    Comparator.comparing(Person::getCity, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                            .thenComparing(Comparator.comparingInt(Person::getAge).reversed()));
            Order<Person> o = where(Person::getCity).asc().thenComparing(where(Person::getScore).asc());
            check(data, o.reversed(), ((Comparator<Person>) o).reversed());
        }
    }

    private static void check(List<Person> data, Order<Person> order, Comparator<Person> expected) {
        for (Person a : data) {
            for (Person b : data) {
                assertThat(Integer.signum(order.compare(a, b)))
                        .as("%s vs %s by %s", a, b, order)
                        .isEqualTo(Integer.signum(expected.compare(a, b)));
            }
        }
    }

    @Test
    void reversedFlipsNullPlacement() {
        Order<Person> asc = where(Person::getCity).asc();
        assertThat(asc.reversed()).isEqualTo(where(Person::getCity).desc());
        assertThat(asc.toString()).isEqualTo("city ASC NULLS LAST");
    }
}
