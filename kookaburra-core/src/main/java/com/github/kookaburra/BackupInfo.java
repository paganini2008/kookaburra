package com.github.kookaburra;

import java.nio.file.Path;

/**
 * Result of {@link ObjectDB#backup}: a consistent copy of the database as of one snapshot.
 *
 * @param path     the backup directory (open it with {@link ObjectDB#open(Path)} to restore)
 * @param seq      snapshot sequence number the backup reflects (every commit up to it, none after)
 * @param objects  objects copied
 * @param segments segment files written
 * @param bytes    bytes written
 * @param millis   duration
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
public record BackupInfo(Path path, long seq, long objects, int segments, long bytes, long millis) {
}
