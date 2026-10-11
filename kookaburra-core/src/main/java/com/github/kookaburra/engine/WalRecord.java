package com.github.kookaburra.engine;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;

import java.util.ArrayList;
import java.util.List;

/**
 * A decoded WAL record: one batch with its sequence number and the already-validated outcomes, so
 * replay re-applies it without validation (design §15).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
record WalRecord(long seq, List<Op> ops) {

    static final int INSERT = 1;
    static final int UPDATE = 2;
    static final int DELETE = 3;

    /**
     * @param kind    INSERT / UPDATE / DELETE
     * @param oid     object id
     * @param version new version (insert: 1; update: incremented; delete: unused)
     * @param row     encoded row (insert / update), else null
     * @param applied delete: whether an object was actually deleted
     */
    record Op(int kind, long oid, long version, byte[] row, boolean applied) {
    }

    static void encode(ByteSink out, List<WriteOp> ops, long[] results) {
        out.writeVarInt(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            if (op instanceof WriteOp.Insert ins) {
                out.writeByte(INSERT).writeVarLong(results[i]).writeBlob(ins.row());
            } else if (op instanceof WriteOp.Update u) {
                out.writeByte(UPDATE).writeVarLong(u.oid()).writeVarLong(results[i]).writeBlob(u.row());
            } else {
                out.writeByte(DELETE).writeVarLong(((WriteOp.Delete) op).oid()).writeByte((int) results[i]);
            }
        }
    }

    static WalRecord decode(long seq, ByteSource in) {
        int n = in.readVarInt();
        List<Op> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int kind = in.readByte();
            switch (kind) {
                case INSERT -> ops.add(new Op(INSERT, in.readVarLong(), 1, in.readBlob(), true));
                case UPDATE -> ops.add(new Op(UPDATE, in.readVarLong(), in.readVarLong(), in.readBlob(), true));
                case DELETE -> ops.add(new Op(DELETE, in.readVarLong(), 0, null, in.readByte() != 0));
                default -> throw new IllegalStateException("corrupt WAL op " + kind);
            }
        }
        return new WalRecord(seq, ops);
    }
}
