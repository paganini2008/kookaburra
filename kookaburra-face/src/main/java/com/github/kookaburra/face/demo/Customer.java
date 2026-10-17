package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Version;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    @Unique String email;
    @Index Tier tier;
    @Index(IndexKind.ORDINAL) int age;
    LocalDate since;
    Address address;
    @Index List<String> tags;
    Double credit;
    /** Further addresses by label ("work", "billing", …): a map of embedded objects. */
    Map<String, Address> contacts;
    /** Free text, sometimes long and multi-line. */
    String notes;
}
