/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.util.Arrays;

/**
 * A logged key as published to clients and written to log files.
 *
 * <p>The {@link #id} identifies the topic both in the live stream and in a log file.
 */
final class Topic {
    static final String SCHEMA_PREFIX = "/.schema/";

    final int id;
    final String name;
    final byte kind;
    final String type;

    /** The last value published, in WPILOG encoding. Valid for {@link #lastSize} bytes. */
    byte[] last = new byte[8];

    /** The length of {@link #last}, or -1 if nothing has been published. */
    int lastSize = -1;

    /** For text topics, the last string published, compared before encoding. */
    String lastText;

    /** The epoch of the log file this topic was last written to. */
    int fileEpoch;

    Topic(int id, String name, byte kind) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.type = Frame.typeOf(kind);
    }

    /**
     * Returns the entry name used in log files. Names carry the {@code NT:} prefix that WPILib
     * gives logged NetworkTables entries, so a layout applies to live and recorded data alike.
     */
    String logName() {
        return name.startsWith(SCHEMA_PREFIX) ? name : "NT:" + name;
    }

    /** Returns whether a payload equals the last value published. */
    boolean sameAsLast(Buf payload) {
        if (payload.size() != lastSize) return false;
        byte[] data = payload.data();
        for (int i = 0; i < lastSize; i++) if (data[i] != last[i]) return false;
        return true;
    }

    /** Records a payload as the last value published. */
    void remember(Buf payload) {
        if (payload.size() > last.length) last = Arrays.copyOf(payload.data(), payload.size());
        else System.arraycopy(payload.data(), 0, last, 0, payload.size());
        lastSize = payload.size();
    }
}
