package com.github.kookaburra.engine;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * One WAL file: records {@code len(4) · crc32c(4) · seq(8) · payload} (design §15). Appends are buffered
 * per write group and written with one {@code write}; {@link #sync()} forces them to disk.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class WalFile implements Wal {

    private static final Logger LOG = LoggerFactory.getLogger(WalFile.class);

    private static final int HEADER = 16;

    private final Path path;
    private final FileChannel ch;
    private final boolean sync;
    private final ByteSink pending = new ByteSink(1 << 16);
    private final ByteSink payload = new ByteSink(1 << 12);

    WalFile(Path path, boolean sync) {
        this.path = path;
        this.sync = sync;
        try {
            this.ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path path() {
        return path;
    }

    @Override
    public void append(long seq, List<WriteOp> ops, long[] results) {
        payload.reset();
        WalRecord.encode(payload, ops, results);
        CRC32C crc = new CRC32C();
        crc.update(payload.array(), 0, payload.size());
        pending.writeInt(payload.size()).writeInt((int) crc.getValue()).writeLong(seq);
        pending.writeBytes(payload.array(), 0, payload.size());
    }

    @Override
    public void sync() {
        try {
            if (pending.size() > 0) {
                ByteBuffer b = ByteBuffer.wrap(pending.array(), 0, pending.size());
                while (b.hasRemaining()) {
                    ch.write(b);
                }
                pending.reset();
            }
            if (sync) {
                ch.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("WAL write failed", e);
        }
    }

    long size() {
        try {
            return ch.size() + pending.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            sync();
            ch.force(false);
            ch.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads all valid records; truncates the file at the first torn or corrupt record. */
    static List<WalRecord> read(Path path) {
        List<WalRecord> out = new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(path);
            int pos = 0;
            while (pos + HEADER <= bytes.length) {
                ByteSource h = new ByteSource(ByteBuffer.wrap(bytes), pos, HEADER);
                int len = h.readInt();
                int crc = h.readInt();
                long seq = h.readLong();
                if (len < 0 || pos + HEADER + len > bytes.length) {
                    break;
                }
                CRC32C c = new CRC32C();
                c.update(bytes, pos + HEADER, len);
                if ((int) c.getValue() != crc) {
                    break;
                }
                out.add(WalRecord.decode(seq, new ByteSource(ByteBuffer.wrap(bytes), pos + HEADER, len)));
                pos += HEADER + len;
            }
            if (pos < bytes.length) {
                LOG.warn("WAL {}: torn or corrupt tail, truncated at byte {} of {} ({} records kept)", path, pos,
                        bytes.length, out.size());
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    ch.truncate(pos);
                    ch.force(true);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read WAL " + path, e);
        }
    }
}
