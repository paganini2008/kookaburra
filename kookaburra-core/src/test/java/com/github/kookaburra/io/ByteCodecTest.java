package com.github.kookaburra.io;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class ByteCodecTest {

    @Test
    void roundTrip() {
        ByteSink out = new ByteSink(1);
        long[] longs = {0, 1, -1, 127, 128, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 35};
        for (long v : longs) {
            out.writeZigZag(v).writeVarLong(v).writeLong(v);
        }
        out.writeVarInt(-1).writeInt(-5).writeDouble(-0.0).writeByte(0xFF).writeString("äö€😀").writeBlob(new byte[]{9, 8});
        assertThat(out.size()).isEqualTo(out.toByteArray().length);
        assertThat(out.array().length).isGreaterThanOrEqualTo(out.size());

        ByteSource in = new ByteSource(out.toByteArray());
        for (long v : longs) {
            assertThat(in.readZigZag()).isEqualTo(v);
            assertThat(in.readVarLong()).isEqualTo(v);
            assertThat(in.readLong()).isEqualTo(v);
        }
        assertThat(in.readVarInt()).isEqualTo(-1);
        assertThat(in.readInt()).isEqualTo(-5);
        assertThat(Double.doubleToRawLongBits(in.readDouble())).isEqualTo(Double.doubleToRawLongBits(-0.0));
        assertThat(in.readByte()).isEqualTo(-1);
        assertThat(in.readString()).isEqualTo("äö€😀");
        assertThat(in.readBlob()).containsExactly(9, 8);
        assertThat(in.remaining()).isZero();
        assertThatThrownBy(in::readByte).hasMessageContaining("read past end");

        out.reset();
        assertThat(out.size()).isZero();
    }

    @Test
    void regionsAndCorruption() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{5, 7, 1, 0});
        ByteSource region = new ByteSource(buf, 1, 2);
        assertThat(region.position()).isEqualTo(1);
        assertThat(region.readByte()).isEqualTo(7);
        assertThat(region.readVarInt()).isEqualTo(1);

        byte[] tooLong = new byte[11];
        Arrays.fill(tooLong, (byte) 0x80);
        assertThatThrownBy(() -> new ByteSource(tooLong).readVarLong()).hasMessageContaining("too long");
        byte[] big = new ByteSink().writeVarLong(1L << 40).toByteArray();
        assertThatThrownBy(() -> new ByteSource(big).readVarInt()).hasMessageContaining("overflow");
    }
}
