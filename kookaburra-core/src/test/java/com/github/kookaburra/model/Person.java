package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Transient;
import com.github.kookaburra.annotation.Version;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Person {
    @Id long oid;
    @Version long version;
    @Index String name;
    @Index String city;
    @Index(IndexKind.ORDINAL) int age;
    Integer score;
    long balance;
    double weight;
    short shortValue;
    boolean active;
    Level level;
    LocalDate birthday;
    Address home;
    Ref<Company> employer;
    @Index List<String> tags;
    Map<String, String> attributes;
    String bio;
    @Transient String cache;
    transient String scratch;

    public Person() {
    }

    public Person(String name, String city, int age) {
        this.name = name;
        this.city = city;
        this.age = age;
    }

    public Person score(Integer v) { score = v; return this; }
    public Person weight(double v) { weight = v; return this; }
    public Person shortValue(short v) { shortValue = v; return this; }
    public Person active(boolean v) { active = v; return this; }
    public Person level(Level v) { level = v; return this; }
    public Person home(Address v) { home = v; return this; }
    public Person tags(List<String> v) { tags = v; return this; }
    public Person employer(Ref<Company> v) { employer = v; return this; }
    public Person birthday(LocalDate v) { birthday = v; return this; }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public int getAge() { return age; }
    public Integer getScore() { return score; }
    public long getBalance() { return balance; }
    public double getWeight() { return weight; }
    public short getShortValue() { return shortValue; }
    public boolean isActive() { return active; }
    public Level getLevel() { return level; }
    public LocalDate getBirthday() { return birthday; }
    public Address getHome() { return home; }
    public Ref<Company> getEmployer() { return employer; }
    public List<String> getTags() { return tags; }
    public Map<String, String> getAttributes() { return attributes; }
    public String getBio() { return bio; }
    public String getCache() { return cache; }
    public int computeSomething() { return age * 2; }

    @Override
    public String toString() {
        return name + "(" + city + ", " + age + ")";
    }
}
