package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.OnDelete;

/**
 * Embedded placement of a {@link Task}: a foreign key inside an embedded object ({@code desk.room}).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Desk {
    @ForeignKey(onDelete = OnDelete.SET_NULL) Ref<Team> room;
    int number;

    public Desk() {
    }

    public Desk(long room, int number) {
        this.room = Ref.of(room);
        this.number = number;
    }

    public Ref<Team> getRoom() { return room; }
    public int getNumber() { return number; }
}
