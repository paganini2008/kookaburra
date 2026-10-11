package com.github.kookaburra.annotation;

/**
 * Column index kinds, see design §5.3.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public enum IndexKind {
    /** Dictionary + ordinals; postings when the segment builder finds the column low-cardinality. */
    AUTO,
    /** Dictionary + ordinals + postings: equality, IN and group-count. */
    BITMAP,
    /** Dictionary + ordinals only: range and sort on high-cardinality columns. */
    ORDINAL,
    /** Bit-sliced index. Reserved, not implemented yet. */
    BSI
}
