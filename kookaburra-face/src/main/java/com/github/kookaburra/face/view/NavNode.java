package com.github.kookaburra.face.view;

import com.github.kookaburra.face.model.ClassInfo;
import com.github.kookaburra.face.model.Session;

/**
 * One node of the navigator: an open database ({@code cls == null}) or a stored class of it.
 *
 * @param session  the database the node belongs to
 * @param label    display name of the database (unique among the open ones)
 * @param cls      the class, or null for the database node
 * @param txLabel  status of the database's open transaction, or null
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public record NavNode(Session session, String label, ClassInfo cls, String txLabel) {

    public boolean isDatabase() {
        return cls == null;
    }

    /** Text shown in the tree. */
    public String text() {
        return isDatabase() ? label + (txLabel == null ? "" : "  · TX") : cls.simpleName() + "  " + cls.count();
    }
}
