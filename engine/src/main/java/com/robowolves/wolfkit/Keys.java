/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns each logged key a slot: a small, stable integer used as an array index.
 *
 * <p>A slot is assigned the first time a key is seen. Looking up an existing key does not allocate.
 * This class is thread-safe.
 */
final class Keys {
    private static final ConcurrentHashMap<String, Integer> slots = new ConcurrentHashMap<>();
    private static final List<String> names = new ArrayList<>();

    private Keys() {}

    /** Returns the slot of a key, assigning one if the key is new. */
    static int slot(String key) {
        Integer slot = slots.get(key);
        return slot != null ? slot : create(key);
    }

    private static synchronized int create(String key) {
        Integer existing = slots.get(key);
        if (existing != null) return existing;
        names.add(key);
        slots.put(key, names.size() - 1);
        return names.size() - 1;
    }

    /** Returns the key a slot was assigned to. */
    static synchronized String name(int slot) {
        return names.get(slot);
    }
}
