package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Version;

import java.math.BigDecimal;

/**
 * Constraint test entity: unique e-mail (string), unique badge number (no index declared: {@code @Unique} adds
 * one), unique decimal code and a unique phone inside an embedded contact.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Member {
    @Id long oid;
    @Version long version;
    String name;
    @Unique String email;
    @Unique Integer badge;
    @Unique BigDecimal code;
    Contact contact;

    public Member() {
    }

    public Member(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Integer getBadge() { return badge; }
    public BigDecimal getCode() { return code; }
    public Contact getContact() { return contact; }

    public Member email(String v) { email = v; return this; }
    public Member badge(Integer v) { badge = v; return this; }
    public Member code(String v) { code = new BigDecimal(v); return this; }
    public Member contact(Contact v) { contact = v; return this; }

    @Override
    public String toString() {
        return name + "<" + email + ">";
    }
}
