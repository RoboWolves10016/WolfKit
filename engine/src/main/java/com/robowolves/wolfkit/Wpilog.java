/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.BitSet;

/**
 * Writes a log file in WPILib's WPILOG format, which AdvantageScope reads.
 *
 * <p>Not thread-safe. Used only by the {@link Recorder} thread.
 */
final class Wpilog {
    private final OutputStream out;
    private final BitSet started = new BitSet();
    private final Buf header = new Buf();

    Wpilog(File file) throws IOException {
        out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
        byte[] extraHeader = Buf.utf8("WolfKit");
        out.write(
                new Buf()
                        .bytes(Buf.utf8("WPILOG"))
                        .le(0x0100, 2)
                        .le(extraHeader.length, 4)
                        .bytes(extraHeader)
                        .toBytes());
    }

    /**
     * Appends a value, first declaring the topic if this file has not seen it.
     *
     * @param micros the timestamp in microseconds
     * @param payload the value in WPILOG encoding
     * @param length the number of bytes of {@code payload} to write
     */
    void append(Topic topic, long micros, byte[] payload, int length) throws IOException {
        if (!started.get(topic.id)) {
            started.set(topic.id);
            byte[] name = Buf.utf8(topic.logName());
            byte[] type = Buf.utf8(topic.type);
            Buf start = new Buf().u8(0).le(topic.id, 4);
            start.le(name.length, 4).bytes(name).le(type.length, 4).bytes(type).le(0, 4);
            record(0, micros, start.data(), start.size());
        }
        record(topic.id, micros, payload, length);
    }

    /** Writes a record. The header byte encodes the width of the entry id, size and timestamp. */
    private void record(int entry, long micros, byte[] payload, int length) throws IOException {
        int idBytes = bytesFor(entry), sizeBytes = bytesFor(length), timeBytes = bytesFor(micros);
        header.clear().u8((idBytes - 1) | (sizeBytes - 1) << 2 | (timeBytes - 1) << 4);
        header.le(entry, idBytes).le(length, sizeBytes).le(micros, timeBytes);
        out.write(header.data(), 0, header.size());
        out.write(payload, 0, length);
    }

    private static int bytesFor(long value) {
        int count = 1;
        while (count < 8 && (value >>> (8 * count)) != 0) count++;
        return count;
    }

    void flush() throws IOException {
        out.flush();
    }

    void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
