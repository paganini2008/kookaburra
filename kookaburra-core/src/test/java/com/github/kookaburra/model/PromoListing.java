package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;

/**
 * Subclass of {@link Listing}: shares its composite unique constraints.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
public class PromoListing extends Listing {
    int discount;

    public PromoListing() {
    }

    public PromoListing(String tenant, String code) {
        super(tenant, code);
    }
}
