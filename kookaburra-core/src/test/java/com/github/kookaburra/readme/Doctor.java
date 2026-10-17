package com.github.kookaburra.readme;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Version;

/**
 * README example: the on-call rota of the write-skew scenario.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
public class Doctor {
    @Id long oid;
    @Version long version;
    String name;
    @Index boolean onCall;

    public Doctor() {
    }

    public Doctor(String name) {
        this.name = name;
        this.onCall = true;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public boolean isOnCall() { return onCall; }
    public Doctor offCall() { onCall = false; return this; }
}
