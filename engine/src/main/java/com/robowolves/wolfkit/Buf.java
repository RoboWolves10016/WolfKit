/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer with little-endian and big-endian writes.
 *
 * <p>Instances are intended to be retained and {@linkplain #clear() cleared} between uses, so that
 * writing does not allocate once the buffer has reached its working size.
 */
final class Buf {
    private byte[] data = new byte[256];
    private int size;

    /** Returns the number of bytes written. */
    int size() {
        return size;
    }

    /** Returns the backing array. Only the first {@link #size()} bytes are valid. */
    byte[] data() {
        return data;
    }

    /** Discards the contents, keeping the capacity. */
    Buf clear() {
        size = 0;
        return this;
    }

    /** Returns a copy of the bytes written. */
    byte[] toBytes() {
        return Arrays.copyOf(data, size);
    }

    Buf u8(int value) {
        if (size == data.length) data = Arrays.copyOf(data, size * 2);
        data[size++] = (byte) value;
        return this;
    }

    Buf bytes(byte[] bytes) {
        return bytes(bytes, bytes.length);
    }

    Buf bytes(byte[] bytes, int length) {
        if (size + length > data.length)
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + length));
        System.arraycopy(bytes, 0, data, size, length);
        size += length;
        return this;
    }

    /** Appends the lowest {@code count} bytes of {@code value}, least significant first. */
    Buf le(long value, int count) {
        for (int i = 0; i < count; i++) u8((int) (value >>> (8 * i)));
        return this;
    }

    /** Appends the lowest {@code count} bytes of {@code value}, most significant first. */
    Buf be(long value, int count) {
        for (int i = count - 1; i >= 0; i--) u8((int) (value >>> (8 * i)));
        return this;
    }

    /** Appends a double as eight little-endian bytes. */
    Buf leDouble(double value) {
        return le(Double.doubleToRawLongBits(value), 8);
    }

    /** Returns the UTF-8 encoding of a string. */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
