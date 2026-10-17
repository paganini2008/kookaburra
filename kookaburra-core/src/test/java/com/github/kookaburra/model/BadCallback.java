package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.PostLoad;

/**
 * Invalid entity: a lifecycle method with a parameter.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class BadCallback {
    @Id long oid;

    @PostLoad
    void loaded(String why) {
    }
}
