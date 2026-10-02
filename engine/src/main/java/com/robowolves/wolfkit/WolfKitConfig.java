/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

/**
 * Settings for the features WolfKit performs automatically.
 *
 * <p>Each field may be changed at any time. To apply settings from the first OpMode onward, set
 * them when the robot controller app starts:
 *
 * <pre>{@code
 * public class WolfKitSettings {
 *     @OnCreate
 *     public static void configure(Context context) {
 *         WolfKitConfig.logGamepads = false;
 *     }
 * }
 * }</pre>
 *
 * <p>These settings do not affect explicit calls such as {@link WolfKit#log(String, double)}, which
 * always work.
 */
public final class WolfKitConfig {
    private WolfKitConfig() {}

    /**
     * Whether to record a WPILOG file for each OpMode run, from init to stop. Files are written to
     * {@code WolfKit/logs} on the robot controller's storage.
     *
     * <p>When {@code false}, values are still streamed to connected dashboards but are not kept.
     */
    public static volatile boolean recordLogFiles = true;

    /**
     * The number of log files to keep. When a new run would exceed it, the oldest files are
     * deleted.
     */
    public static volatile int keepLogFiles = 50;

    /**
     * Whether to log both gamepads while an OpMode is active. Every stick, trigger and button is
     * logged under {@code Gamepad1/} and {@code Gamepad2/}.
     *
     * <p>The gamepads are sampled by a background thread every {@link #gamepadIntervalMs}
     * milliseconds. Samples are therefore not synchronised with the robot loop.
     */
    public static volatile boolean logGamepads = true;

    /**
     * The interval between gamepad samples, in milliseconds. A button press shorter than the
     * interval may not be logged.
     */
    public static volatile int gamepadIntervalMs = 20;

    /**
     * Whether to log the battery voltage under {@code WolfKit/BatteryVolts}.
     *
     * <p>Each reading is a command to the hub of 2 to 3 ms on the bus the robot loop uses, and may
     * delay one loop iteration by that long.
     */
    public static volatile boolean logBattery = true;

    /** The interval between battery voltage readings, in seconds. */
    public static volatile int batteryIntervalSeconds = 10;

    /**
     * Whether to publish the text an OpMode sends to the Driver Station through {@code telemetry}.
     * The text is published under {@code Telemetry} at most ten times a second.
     *
     * <p>WolfKit replaces the OpMode's {@code telemetry} with a pass-through before init. Every
     * call still reaches the Driver Station. When {@code false}, {@link
     * #logAnnotatedOnTelemetryUpdate} has no effect, since it depends on the pass-through.
     */
    public static volatile boolean captureTelemetry = true;

    /**
     * Whether to {@linkplain WolfKit#register(Object) register} each OpMode before its init, so
     * that {@link Logged} members of the OpMode, and of objects reachable through them, are found
     * without an explicit call.
     */
    public static volatile boolean registerOpModes = true;

    /**
     * Whether to log all {@link Logged} members each time an OpMode updates its telemetry.
     *
     * <p>The SDK updates telemetry after every {@code loop()} of an iterative OpMode, so such
     * OpModes need not call {@link WolfKit#logAll()}. A {@code LinearOpMode} is covered whenever it
     * calls {@code telemetry.update()}. An OpMode that calls {@link WolfKit#logAll()} itself is
     * exempt for the rest of its run, so that members are not logged twice.
     */
    public static volatile boolean logAnnotatedOnTelemetryUpdate = true;

    /**
     * Whether to log the period of the robot loop under {@code WolfKit/LoopMs}, in milliseconds.
     * The period is the time between the starts of consecutive frames.
     */
    public static volatile boolean logLoopTime = true;
}
