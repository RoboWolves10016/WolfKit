/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * WolfKit: logging, live telemetry and tuning for FIRST Tech Challenge robots.
 *
 * <p>Robot code uses {@link com.robowolves.wolfkit.WolfKit} to log values and read tunables, {@link
 * com.robowolves.wolfkit.Logged} to log fields by annotation, and {@link
 * com.robowolves.wolfkit.WolfKitConfig} to switch automatic features on or off.
 *
 * <p>Logged values are published over a NetworkTables 4 compatible WebSocket and written to WPILOG
 * files. {@link com.robowolves.wolfkit.WolfKitRuntime} and {@link com.robowolves.wolfkit.DashHttp}
 * are the integration points for the code that hosts WolfKit: the {@code core} module on a robot
 * and the simulator on a desktop.
 *
 * <p>This package has no Android dependencies.
 */
package com.robowolves.wolfkit;
