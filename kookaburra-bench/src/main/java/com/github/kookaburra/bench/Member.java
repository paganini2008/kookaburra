package com.github.kookaburra.bench;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;

import java.util.List;
import java.util.Random;

/**
 * Benchmark entity: a mix of indexed (bitmap / ordinal) and plain columns.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Member {

    static final String[] CITIES = new String[50];
    static final String[] TAGS = {"vip", "new", "churn-risk", "beta", "staff", "partner", "trial", "mobile"};

    static {
        for (int i = 0; i < CITIES.length; i++) {
            CITIES[i] = "City-" + i;
        }
    }

    @Id long oid;
    @Version long version;
    @Index String name;
    @Index String city;
    @Index(IndexKind.ORDINAL) int age;
    int score;
    long balance;
    boolean active;
    @Index List<String> tags;
    String note;

    public Member() {
    }

    static Member random(Random r, long n) {
        Member m = new Member();
        m.name = "member-" + n;
        m.city = CITIES[r.nextInt(CITIES.length)];
        m.age = 18 + r.nextInt(63);
        m.score = r.nextInt(1_000_000);
        m.balance = r.nextLong() % 10_000_000L;
        m.active = r.nextInt(4) != 0;
        m.tags = List.of(TAGS[r.nextInt(TAGS.length)], TAGS[r.nextInt(TAGS.length)]);
        m.note = "note " + Long.toHexString(r.nextLong());
        return m;
    }

    public long getOid() { return oid; }
    public long getVersion() { return version; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public int getAge() { return age; }
    public int getScore() { return score; }
    public long getBalance() { return balance; }
    public boolean isActive() { return active; }
    public List<String> getTags() { return tags; }
    public String getNote() { return note; }

    public void setScore(int score) { this.score = score; }
}
