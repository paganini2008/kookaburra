package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;
import com.github.kookaburra.model.Address;
import com.github.kookaburra.model.Level;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.UUID;

/**
 * Entity using every supported value type.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Kitchen {
    @Id public long id;
    @Version public long version;
    @Index public boolean b; public Boolean bb;
    public byte by; public Byte bby; public short sh; public Short ssh; public char ch; public Character cch; @Index public int i; @Index(IndexKind.ORDINAL) public Integer ii;
    @Index(IndexKind.BITMAP) public long l; public Long ll; public float f; public Float ff; @Index public double d; public Double dd;
    @Index public String s; @Index public Level level;
    @Index public LocalDate date; @Index public LocalDateTime dateTime; @Index public Instant instant;
    @Index public Ref<Kitchen> ref;
    @Index public Set<Level> levels; public SortedSet<Integer> sorted; public ArrayList<String> arrayList;
    public Address address; public Nested nested; public List<Address> addresses; public Set<Address> addressSet;
    public Map<String, Integer> map; public SortedMap<String, Double> sortedMap; public HashMap<Long, String> hashMap;
    public byte[] bytes; public UUID uuid; public Map<String, Object> anything; public List<Object> objects;

    @Embeddable
    public static class Nested {
        @Index String code;
        Address inner;
        List<Integer> numbers;
    }
}
