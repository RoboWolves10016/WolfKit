/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

/**
 * Logs values of a type that WolfKit does not handle natively, such as a pose class from another
 * library. Register one with {@link WolfKit#adapter(Class, LogAdapter)}.
 *
 * @param <T> the type of value this adapter logs
 */
public interface LogAdapter<T> {
    /**
     * Logs a value.
     *
     * @param key the key to log under
     * @param value the value, never {@code null}
     */
    void log(String key, T value);
}
