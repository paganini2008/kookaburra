package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.OnDelete;

/**
 * Second cascade level: deleted with its {@link Membership}.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Note {
    @Id long oid;
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Membership> on;
    String text;

    public Note() {
    }

    public Note(long on, String text) {
        this.on = Ref.of(on);
        this.text = text;
    }

    public long getOid() { return oid; }
    public String getText() { return text; }
}
