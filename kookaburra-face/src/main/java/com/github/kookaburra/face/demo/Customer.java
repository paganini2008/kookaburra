package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;

import java.time.LocalDate;
import java.util.List;

/**
 * Customer of the demo data set.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Customer {
    @Id long oid;
    @Version long version;
    @Index String name;
    @Index Tier tier;
    @Index(IndexKind.ORDINAL) int age;
    LocalDate since;
    Address address;
    @Index List<String> tags;
    Double credit;
}
