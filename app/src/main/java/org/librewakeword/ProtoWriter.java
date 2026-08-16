package org.librewakeword;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class ProtoWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    ProtoWriter int32(int field, int value) {
        tag(field, 0);
        varint(value);
        return this;
    }

    ProtoWriter sint32(int field, int value) {
        tag(field, 0);
        varint((value << 1) ^ (value >> 31));
        return this;
    }

    ProtoWriter bool(int field, boolean value) {
        tag(field, 0);
        varint(value ? 1 : 0);
        return this;
    }

    ProtoWriter fixed32(int field, int value) {
        tag(field, 5);
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
        return this;
    }

    ProtoWriter float32(int field, float value) {
        return fixed32(field, Float.floatToIntBits(value));
    }

    ProtoWriter string(int field, String value) {
        if (value == null) {
            return this;
        }
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    ProtoWriter bytes(int field, byte[] value) {
        if (value == null) {
            return this;
        }
        tag(field, 2);
        varint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    ProtoWriter message(int field, byte[] value) {
        return bytes(field, value);
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    static byte[] empty() {
        return new byte[0];
    }

    private void tag(int field, int wireType) {
        varint((field << 3) | wireType);
    }

    private void varint(long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
