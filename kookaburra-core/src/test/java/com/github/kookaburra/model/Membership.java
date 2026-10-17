package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.OnDelete;

/**
 * Link entity: deleted with its member (CASCADE); its team cannot be deleted while it exists (RESTRICT).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Membership {
    @Id long oid;
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Member> member;
    @ForeignKey Ref<Team> team;
    String role;

    public Membership() {
    }

    public Membership(long member, long team, String role) {
        this.member = Ref.of(member);
        this.team = Ref.of(team);
        this.role = role;
    }

    public long getOid() { return oid; }
    public Ref<Member> getMember() { return member; }
    public Ref<Team> getTeam() { return team; }
    public String getRole() { return role; }
}
