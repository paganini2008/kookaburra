package com.github.kookaburra.oql;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.exec.QueryHost;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.mapping.ClassDef;

/**
 * What OQL needs from the database. Internal API.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface OqlHost extends QueryHost {

    /** Stored class by fully qualified or unique simple name. */
    ClassDef storedClass(String name);

    /** Schema-driven object by OID as of a view, or null. */
    DynamicObject getDynamic(ReadView view, long oid);

    /** Java object by OID as of a view, or null (requires the entity class). */
    Object getTyped(ReadView view, long oid);
}
