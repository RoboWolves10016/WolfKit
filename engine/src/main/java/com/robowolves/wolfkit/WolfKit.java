/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.util.Arrays;

/**
 * The entry point for logging values and reading tunables from robot code.
 *
 * <p>Every value logged is streamed to connected dashboards and recorded in a WPILOG file for the
 * current OpMode run.
 *
 * <pre>{@code
 * WolfKit.log("Shooter/VelocityRPM", rpm);
 * WolfKit.logPedroPose("Drive/Robot", pose.x(), pose.y(), pose.heading());
 * double kP = WolfKit.tunable("Shooter/kP", 0.1);
 * }</pre>
 *
 * <p><b>Keys.</b> A key names a value. Slashes group keys, so {@code Shooter/VelocityRPM} appears
 * under {@code Shooter}. A key keeps the type it was first logged with.
 *
 * <p><b>Frames.</b> Values are grouped into frames that share a timestamp, one per iteration of the
 * robot loop. No update call is needed: logging a key that the current frame already holds starts a
 * new frame.
 *
 * <p><b>Units.</b> Poses are in inches and radians in the FTC field coordinate system, whose origin
 * is the centre of the field.
 *
 * <p><b>Allocation.</b> Logging a number, boolean or pose does not allocate once its key has been
 * logged before.
 *
 * <p>All methods are thread-safe.
 *
 * @see Logged
 * @see WolfKitConfig
 */
public final class WolfKit {
    private static final long STALE_FRAME_MICROS = 100_000;
    private static final long BUTTON_EXPIRY_MICROS = 500_000;
    private static final double METERS_PER_INCH = 0.0254;
    private static final long START_NANOS = System.nanoTime();

    private static final Object lock = new Object();
    private static Frame frame = new Frame();

    /** For each key slot, the number of the frame in which the key was last logged. */
    private static int[] seenInFrame = new int[256];

    private static int frameNumber = 1;

    static volatile Recorder recorder;

    /** Whether robot code has called {@link #logAll()} during the current OpMode run. */
    static volatile boolean explicitLogAll;

    private WolfKit() {}

    /**
     * Logs a number.
     *
     * @param key the key to log under
     * @param value the value
     */
    public static void log(String key, double value) {
        log(Keys.slot(key), value);
    }

    /**
     * Logs a boolean.
     *
     * @param key the key to log under
     * @param value the value
     */
    public static void log(String key, boolean value) {
        log(Keys.slot(key), value);
    }

    /**
     * Logs a value as text, using {@link String#valueOf(Object)}. Suitable for strings and enums.
     *
     * @param key the key to log under
     * @param value the value; {@code null} is logged as {@code "null"}
     */
    public static void log(String key, Object value) {
        log(Keys.slot(key), String.valueOf(value));
    }

    /**
     * Logs an array of numbers. The array is copied.
     *
     * @param key the key to log under
     * @param values the values
     */
    public static void log(String key, double[] values) {
        log(Keys.slot(key), values);
    }

    /**
     * Logs a pose on the field floor, such as a robot position, a target or a vision estimate.
     *
     * @param key the key to log under
     * @param xInches the x coordinate, in inches from the field centre
     * @param yInches the y coordinate, in inches from the field centre
     * @param headingRadians the heading, in radians anticlockwise from the x axis
     */
    public static void logPose2d(
            String key, double xInches, double yInches, double headingRadians) {
        logPose2d(Keys.slot(key), xInches, yInches, headingRadians);
    }

    /**
     * Logs a pose given in Pedro Pathing coordinates, whose origin is a corner of the field. The
     * pose is converted to field-centre coordinates.
     *
     * @param key the key to log under
     * @param x the Pedro x coordinate, in inches
     * @param y the Pedro y coordinate, in inches
     * @param headingRadians the Pedro heading, in radians
     */
    public static void logPedroPose(String key, double x, double y, double headingRadians) {
        logPose2d(Keys.slot(key), y - 72, 72 - x, headingRadians - Math.PI / 2);
    }

    /**
     * Logs a sequence of poses on the field floor, such as a path. The three arrays must have the
     * same length.
     *
     * @param key the key to log under
     * @param xInches the x coordinates, in inches from the field centre
     * @param yInches the y coordinates, in inches from the field centre
     * @param headingRadians the headings, in radians
     */
    public static void logPoses2d(
            String key, double[] xInches, double[] yInches, double[] headingRadians) {
        int slot = Keys.slot(key);
        synchronized (lock) {
            begin(slot);
            int at = frame.reserve(slot, Frame.POSE2D_ARRAY, xInches.length * 3);
            double[] out = frame.extra;
            for (int i = 0; i < xInches.length; i++) {
                out[at++] = xInches[i] * METERS_PER_INCH;
                out[at++] = yInches[i] * METERS_PER_INCH;
                out[at++] = headingRadians[i];
            }
        }
    }

    /**
     * Logs a pose in three dimensions, such as a mechanism, camera or game piece.
     *
     * @param key the key to log under
     * @param xInches the x coordinate, in inches from the field centre
     * @param yInches the y coordinate, in inches from the field centre
     * @param zInches the height above the floor, in inches
     * @param rollRadians the rotation about the field x axis, in radians
     * @param pitchRadians the rotation about the field y axis, in radians
     * @param yawRadians the rotation about the field z axis, in radians
     */
    public static void logPose3d(
            String key,
            double xInches,
            double yInches,
            double zInches,
            double rollRadians,
            double pitchRadians,
            double yawRadians) {
        double cr = Math.cos(rollRadians / 2), sr = Math.sin(rollRadians / 2);
        double cp = Math.cos(pitchRadians / 2), sp = Math.sin(pitchRadians / 2);
        double cy = Math.cos(yawRadians / 2), sy = Math.sin(yawRadians / 2);
        int slot = Keys.slot(key);
        synchronized (lock) {
            begin(slot);
            int at = frame.reserve(slot, Frame.POSE3D, 7);
            double[] out = frame.extra;
            out[at] = xInches * METERS_PER_INCH;
            out[at + 1] = yInches * METERS_PER_INCH;
            out[at + 2] = zInches * METERS_PER_INCH;
            out[at + 3] = cr * cp * cy + sr * sp * sy; // quaternion w
            out[at + 4] = sr * cp * cy - cr * sp * sy; // x
            out[at + 5] = cr * sp * cy + sr * cp * sy; // y
            out[at + 6] = cr * cp * sy - sr * sp * cy; // z
        }
    }

    /**
     * Returns the value of a number that can be changed from a dashboard while the robot runs. Call
     * this on every loop iteration and use the result.
     *
     * @param key the key, shown on the dashboard under {@code Tuning/}
     * @param defaultValue the value returned until a dashboard changes it
     * @return the current value
     */
    public static double tunable(String key, double defaultValue) {
        Tunables.Entry entry = Tunables.entry(Tunables.TUNING, key, defaultValue);
        Object value = entry.value;
        double current = value instanceof Double ? (Double) value : defaultValue;
        log(entry.slot, current);
        return current;
    }

    /**
     * Returns the value of a boolean that can be changed from a dashboard.
     *
     * @param key the key, shown on the dashboard under {@code Tuning/}
     * @param defaultValue the value returned until a dashboard changes it
     * @return the current value
     * @see #tunable(String, double)
     */
    public static boolean tunable(String key, boolean defaultValue) {
        Tunables.Entry entry = Tunables.entry(Tunables.TUNING, key, defaultValue);
        Object value = entry.value;
        boolean current = value instanceof Boolean ? (Boolean) value : defaultValue;
        log(entry.slot, current);
        return current;
    }

    /**
     * Returns the value of a string that can be changed from a dashboard.
     *
     * @param key the key, shown on the dashboard under {@code Tuning/}
     * @param defaultValue the value returned until a dashboard changes it
     * @return the current value
     * @see #tunable(String, double)
     */
    public static String tunable(String key, String defaultValue) {
        Tunables.Entry entry = Tunables.entry(Tunables.TUNING, key, defaultValue);
        Object value = entry.value;
        String current = value instanceof String ? (String) value : defaultValue;
        log(entry.slot, current);
        return current;
    }

    /**
     * Declares a button on the dashboard and reports whether it has been clicked. Call this on
     * every loop iteration.
     *
     * <p>A click is reported once. A click that is not read within half a second is discarded, so
     * that a click made while no OpMode is running cannot take effect later.
     *
     * @param label the text on the button
     * @return {@code true} if the button was clicked since the previous call
     */
    public static boolean button(String label) {
        Tunables.Entry entry = Tunables.entry(Tunables.BUTTONS, label, Boolean.FALSE);
        boolean pressed =
                entry.value == Boolean.TRUE
                        && micros() - entry.changedMicros < BUTTON_EXPIRY_MICROS;
        entry.value = Boolean.FALSE;
        log(entry.slot, pressed);
        return pressed;
    }

    /**
     * Registers an object whose {@link Logged} fields and methods are logged by {@link #logAll()}.
     * Call this once per object, for example in a constructor. Registrations are cleared when the
     * OpMode stops.
     *
     * @param target the object to register
     */
    public static void register(Object target) {
        AnnotationLogger.register(target);
    }

    /**
     * Logs the {@link Logged} members of every registered object. Call this once per loop
     * iteration.
     *
     * <p>On a robot this is called automatically whenever the OpMode updates its telemetry, unless
     * disabled by {@link WolfKitConfig#logAnnotatedOnTelemetryUpdate}. Calling it explicitly turns
     * the automatic call off for the rest of the OpMode run.
     */
    public static void logAll() {
        explicitLogAll = true;
        AnnotationLogger.logAll();
    }

    /**
     * Registers an adapter that logs values of a type WolfKit does not handle natively, for use by
     * {@link Logged}.
     *
     * @param type the type to handle, including its subtypes
     * @param adapter logs a value of that type under a key
     * @param <T> the type to handle
     */
    public static <T> void adapter(Class<T> type, LogAdapter<? super T> adapter) {
        AnnotationLogger.adapter(type, adapter);
    }

    // Slot-based variants for callers that have already resolved the key.

    static void log(int slot, double value) {
        synchronized (lock) {
            begin(slot);
            frame.number(slot, value);
        }
    }

    static void log(int slot, boolean value) {
        synchronized (lock) {
            begin(slot);
            frame.bool(slot, value);
        }
    }

    static void log(int slot, String value) {
        synchronized (lock) {
            begin(slot);
            frame.text(slot, value);
        }
    }

    static void log(int slot, double[] values) {
        synchronized (lock) {
            begin(slot);
            System.arraycopy(
                    values,
                    0,
                    frame.extra,
                    frame.reserve(slot, Frame.DOUBLES, values.length),
                    values.length);
        }
    }

    static void logPose2d(int slot, double xInches, double yInches, double headingRadians) {
        synchronized (lock) {
            begin(slot);
            int at = frame.reserve(slot, Frame.POSE2D, 3);
            frame.extra[at] = xInches * METERS_PER_INCH;
            frame.extra[at + 1] = yInches * METERS_PER_INCH;
            frame.extra[at + 2] = headingRadians;
        }
    }

    /**
     * Prepares the current frame to receive a value for a slot. If the frame already holds that
     * slot, a new loop iteration has begun and the frame is closed first. Must be called with
     * {@link #lock} held.
     */
    private static void begin(int slot) {
        if (slot >= seenInFrame.length)
            seenInFrame = Arrays.copyOf(seenInFrame, Math.max(slot + 1, seenInFrame.length * 2));
        if (seenInFrame[slot] == frameNumber) closeFrame(true);
        if (frame.count == 0) frame.micros = micros();
        seenInFrame[slot] = frameNumber;
    }

    /**
     * Passes the current frame to the recorder and begins a new one. The frame is discarded when
     * nothing is recording or connected. Must be called with {@link #lock} held.
     *
     * @param loop whether the frame is being closed by the start of the next loop iteration
     */
    private static void closeFrame(boolean loop) {
        if (frame.count == 0) return;
        frameNumber++;
        Recorder r = recorder;
        if (r == null || !r.wantsFrames()) {
            frame.reset();
            return;
        }
        frame.loop = loop;
        frame = r.submit(frame);
    }

    /**
     * Closes the current frame if it has been open longer than {@link #STALE_FRAME_MICROS}. This
     * publishes values that no later frame would close, such as the last iteration of an OpMode.
     */
    static void closeStaleFrame() {
        synchronized (lock) {
            if (micros() - frame.micros > STALE_FRAME_MICROS) closeFrame(false);
        }
    }

    /** Closes the current frame immediately. */
    static void closeFrameNow() {
        synchronized (lock) {
            closeFrame(false);
        }
    }

    /**
     * Returns the time in microseconds since this class was loaded. This is the clock used for
     * timestamps in the live stream and in log files.
     */
    static long micros() {
        return (System.nanoTime() - START_NANOS) / 1000;
    }
}
