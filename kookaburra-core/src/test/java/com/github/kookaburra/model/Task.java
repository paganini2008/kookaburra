package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.Version;

import java.util.List;

/**
 * SET_NULL foreign keys (a ref, a list of refs, a ref inside an embedded object) and a plain unchecked, unindexed
 * reference (found by {@code referrers} with a scan).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Task {
    @Id long oid;
    @Version long version;
    String title;
    @ForeignKey(onDelete = OnDelete.SET_NULL) Ref<Member> assignee;
    @ForeignKey(onDelete = OnDelete.SET_NULL) List<Ref<Member>> watchers;
    Desk desk;
    Ref<Member> author;

    public Task() {
    }

    public Task(String title) {
        this.title = title;
    }

    public long getOid() { return oid; }
    public long getVersion() { return version; }
    public String getTitle() { return title; }
    public Ref<Member> getAssignee() { return assignee; }
    public List<Ref<Member>> getWatchers() { return watchers; }
    public Desk getDesk() { return desk; }
    public Ref<Member> getAuthor() { return author; }

    public Task assignee(long v) { assignee = Ref.of(v); return this; }
    public Task watchers(List<Ref<Member>> v) { watchers = v; return this; }
    public Task desk(Desk v) { desk = v; return this; }
    public Task author(long v) { author = Ref.of(v); return this; }
}
