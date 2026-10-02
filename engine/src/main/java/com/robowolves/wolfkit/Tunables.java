/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The values a client may change: tunables and buttons.
 *
 * <p>A write from a client is accepted only for a topic registered here. This class is thread-safe.
 */
final class Tunables {
    static final String TUNING = "/Tuning/";
    static final String BUTTONS = "/Buttons/";

    private static final ConcurrentHashMap<String, Entry> byTopic = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Entry> tuning = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Entry> buttons = new ConcurrentHashMap<>();

    private Tunables() {}

    /**
     * Returns the entry for a key, creating it with a default value on first use.
     *
     * @param prefix {@link #TUNING} or {@link #BUTTONS}
     */
    static Entry entry(String prefix, String key, Object defaultValue) {
        ConcurrentHashMap<String, Entry> byKey = prefix.equals(TUNING) ? tuning : buttons;
        Entry entry = byKey.get(key);
        if (entry == null) {
            entry = new Entry(prefix + key, defaultValue);
            byKey.put(key, entry);
            byTopic.put(entry.topic, entry);
        }
        return entry;
    }

    /**
     * Applies a value written by a client.
     *
     * @return the entry updated, or {@code null} if the topic is not registered or the value's type
     *     differs from the entry's
     */
    static Entry accept(String topic, Object value) {
        Entry entry = byTopic.get(topic);
        if (entry == null || value == null || value.getClass() != entry.type) return null;
        entry.changedMicros = WolfKit.micros();
        entry.value = value;
        return entry;
    }

    /** A tunable or button and its current value. */
    static final class Entry {
        final String topic;
        final int slot;
        final Class<?> type;
        volatile Object value;
        volatile long changedMicros;

        Entry(String topic, Object defaultValue) {
            this.topic = topic;
            this.slot = Keys.slot(topic);
            this.type = defaultValue.getClass();
            this.value = defaultValue;
        }
    }
}
