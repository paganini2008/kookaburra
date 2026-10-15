package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;

/**
 * Transaction test entity: versioned, an indexed owner, ordinal-indexed priority, numeric and embedded fields.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Entity
public class Account {
    @Id long oid;
    @Version long version;
    @Index String owner;
    @Index(IndexKind.ORDINAL) int priority;
    long balance;
    int visits;
    double rating;
    String note;
    Stats stats;

    public Account() {
    }

    public Account(String owner, long balance) {
        this.owner = owner;
        this.balance = balance;
    }

    public long getOid() { return oid; }
    public long getVersion() { return version; }
    public String getOwner() { return owner; }
    public int getPriority() { return priority; }
    public long getBalance() { return balance; }
    public int getVisits() { return visits; }
    public double getRating() { return rating; }
    public String getNote() { return note; }
    public Stats getStats() { return stats; }

    public Account owner(String v) { owner = v; return this; }
    public Account priority(int v) { priority = v; return this; }
    public Account balance(long v) { balance = v; return this; }
    public Account visits(int v) { visits = v; return this; }
    public Account rating(double v) { rating = v; return this; }
    public Account note(String v) { note = v; return this; }
    public Account stats(Stats v) { stats = v; return this; }

    @Override
    public String toString() {
        return owner + ":" + balance + "@v" + version;
    }
}
