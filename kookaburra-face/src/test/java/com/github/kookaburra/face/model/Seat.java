package com.github.kookaburra.face.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Unique;

/**
 * Test entity with a composite unique constraint (one seat per hall, row and number) and a single-field one.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
@Unique(name = "seat", fields = {"hall", "row", "number"})
public class Seat {
    @Id long oid;
    String hall;
    String row;
    int number;
    @Unique String ticket;

    public Seat() {
    }

    public Seat(String hall, String row, int number, String ticket) {
        this.hall = hall;
        this.row = row;
        this.number = number;
        this.ticket = ticket;
    }
}
