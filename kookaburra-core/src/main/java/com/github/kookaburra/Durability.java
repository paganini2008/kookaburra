package com.github.kookaburra;

/**
 * When a write call returns.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public enum Durability {
    /** After the WAL record is fsync'ed. */
    SYNC,
    /** After the WAL record is written to the OS (fsync happens in the background). */
    ASYNC
}
