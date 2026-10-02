/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.util.Arrays;

/**
 * The values logged during one iteration of the robot loop.
 *
 * <p>Values are held in parallel arrays indexed by entry. Frames are pooled and passed between the
 * logging thread and the {@link Recorder}, so no frame is allocated in steady state.
 */
final class Frame {
    /** The kind of value an entry holds. */
    static final byte NUMBER = 0,
            BOOLEAN = 1,
            TEXT = 2,
            DOUBLES = 3,
            POSE2D = 4,
            POSE2D_ARRAY = 5,
            POSE3D = 6,
            SCHEMA = 7;

    private static final String[] TYPES = {
        "double",
        "boolean",
        "string",
        "double[]",
        "struct:Pose2d",
        "struct:Pose2d[]",
        "struct:Pose3d",
        "structschema"
    };

    /** The action the recorder takes for a frame. */
    static final byte VALUES = 0, OPEN_FILE = 1, CLOSE_FILE = 2;

    byte action;
    long micros;

    /** Whether the frame was closed by the start of the next loop iteration. */
    boolean loop;

    String opModeName;

    int count;
    int[] slots = new int[64];
    byte[] kinds = new byte[64];

    /**
     * For numbers and booleans, the value. For arrays and poses, the offset into {@link #extra}.
     */
    double[] numbers = new double[64];

    int[] lengths = new int[64];
    String[] texts = new String[64];

    /** Storage for the elements of arrays and poses, stored contiguously. */
    double[] extra = new double[64];

    int extraSize;

    /** Returns the NetworkTables type name of a kind. */
    static String typeOf(byte kind) {
        return TYPES[kind];
    }

    /** Empties the frame for reuse, keeping its capacity. */
    void reset() {
        Arrays.fill(texts, 0, count, null);
        count = 0;
        extraSize = 0;
        action = VALUES;
        loop = false;
        opModeName = null;
    }

    private int add(int slot, byte kind) {
        if (count == slots.length) {
            int size = count * 2;
            slots = Arrays.copyOf(slots, size);
            kinds = Arrays.copyOf(kinds, size);
            numbers = Arrays.copyOf(numbers, size);
            lengths = Arrays.copyOf(lengths, size);
            texts = Arrays.copyOf(texts, size);
        }
        slots[count] = slot;
        kinds[count] = kind;
        return count++;
    }

    void number(int slot, double value) {
        numbers[add(slot, NUMBER)] = value;
    }

    void bool(int slot, boolean value) {
        numbers[add(slot, BOOLEAN)] = value ? 1 : 0;
    }

    void text(int slot, String value) {
        texts[add(slot, TEXT)] = value;
    }

    /**
     * Adds an array or pose entry and reserves space for its elements.
     *
     * @param doubles the number of elements
     * @return the offset in {@link #extra} at which to write them
     */
    int reserve(int slot, byte kind, int doubles) {
        if (extraSize + doubles > extra.length)
            extra = Arrays.copyOf(extra, Math.max(extra.length * 2, extraSize + doubles));
        int index = add(slot, kind);
        numbers[index] = extraSize;
        lengths[index] = doubles;
        extraSize += doubles;
        return extraSize - doubles;
    }
}
