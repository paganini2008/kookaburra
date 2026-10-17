package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Composite unique constraints: a code per tenant, one listing per seller and day (named), a street per city
 * through an embedded path, and a decimal in a combination.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
@Unique(fields = {"tenant", "code"})
@Unique(name = "one_per_day", fields = {"seller", "day"})
@Unique(fields = {"place.city", "place.street"})
@Unique(fields = {"tenant", "price"})
public class Listing {
    @Id long oid;
    @Version long version;
    String tenant;
    String code;
    Ref<Member> seller;
    LocalDate day;
    Place place;
    BigDecimal price;

    public Listing() {
    }

    public Listing(String tenant, String code) {
        this.tenant = tenant;
        this.code = code;
    }

    public long getOid() { return oid; }
    public String getTenant() { return tenant; }
    public String getCode() { return code; }

    public Listing code(String v) { code = v; return this; }
    public Listing tenant(String v) { tenant = v; return this; }
    public Listing seller(long v, String isoDay) { seller = Ref.of(v); day = LocalDate.parse(isoDay); return this; }
    public Listing place(String city, String street) { place = new Place(city, street, null); return this; }
    public Listing price(String v) { price = new BigDecimal(v); return this; }
}
