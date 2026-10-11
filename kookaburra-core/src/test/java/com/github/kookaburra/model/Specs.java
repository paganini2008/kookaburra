package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Specs {
    int count;
    long total;
    double ratio;
    boolean enabled;
    String label;
    LocalDate since;
    List<String> parts;
    Ref<Company> maker;
    Address origin;
    Map<String, String> extra;

    public int getCount() { return count; }
    public long getTotal() { return total; }
    public double getRatio() { return ratio; }
    public boolean isEnabled() { return enabled; }
    public String getLabel() { return label; }
    public LocalDate getSince() { return since; }
    public List<String> getParts() { return parts; }
    public Ref<Company> getMaker() { return maker; }
    public Address getOrigin() { return origin; }
    public Map<String, String> getExtra() { return extra; }
}
