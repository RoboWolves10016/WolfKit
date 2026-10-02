/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.io.File;
import java.io.IOException;

/**
 * Lifecycle and integration hooks for the code that hosts WolfKit.
 *
 * <p>Robot code does not call this class. It is used by the {@code core} module on a robot and by
 * the simulator on a desktop to start and stop WolfKit, report OpMode transitions and publish
 * values from outside the robot loop.
 *
 * <p>All methods are thread-safe.
 */
public final class WolfKitRuntime {
    private static final long TELEMETRY_INTERVAL_MICROS = 100_000;
    private static final Object lock = new Object();
    private static volatile long lastTelemetryMicros;
    private static Nt4Server server;
    private static Thread recorderThread;
    private static File dataDir;

    private WolfKitRuntime() {}

    /**
     * Starts the live stream and the recorder. Does nothing if WolfKit is already running.
     *
     * @param dataDirectory the directory for log files and saved dashboard layouts
     * @throws IOException if the live stream port is in use
     */
    public static void start(File dataDirectory) throws IOException {
        synchronized (lock) {
            if (server != null) return;
            Nt4Server s = new Nt4Server();
            s.open();
            server = s;
            dataDir = dataDirectory;
            WolfKit.recorder = new Recorder(logDir(), s);
            recorderThread = new Thread(WolfKit.recorder, "WolfKit recorder");
            recorderThread.setDaemon(true);
            recorderThread.start();
            loadOptionalModule("com.robowolves.wolfkit.pedro.WolfKitPedro");
        }
    }

    /**
     * Loads an optional module if it is on the classpath. A module registers its {@link
     * LogAdapter}s in a static initializer.
     */
    private static void loadOptionalModule(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException | LinkageError notInstalled) {
            // The module is not in use.
        }
    }

    /** Stops the live stream and the recorder, closing any open log file. */
    public static void stop() {
        synchronized (lock) {
            if (server == null) return;
            recorderThread.interrupt();
            WolfKit.recorder = null;
            server.close();
            server = null;
        }
    }

    /**
     * Returns whether WolfKit is running.
     *
     * @return {@code true} if WolfKit has been started and not stopped
     */
    public static boolean isRunning() {
        return WolfKit.recorder != null;
    }

    /**
     * Returns the port of the live stream.
     *
     * @return the TCP port
     */
    public static int livePort() {
        return Nt4Server.PORT;
    }

    static File logDir() {
        return new File(dataDir, "logs");
    }

    static File layoutsFile() {
        return new File(dataDir, "layouts.json");
    }

    /**
     * Reports that an OpMode has entered init. Starts a log file for the run.
     *
     * @param name the name of the OpMode
     */
    public static void opModeInit(String name) {
        Recorder recorder = WolfKit.recorder;
        if (recorder == null) return;
        // Registrations are kept: objects constructed with the OpMode have already registered.
        WolfKit.explicitLogAll = false;
        recorder.startFile(name);
        WolfKit.log("WolfKit/OpMode", name);
        WolfKit.log("WolfKit/OpModeStatus", "INIT");
    }

    /** Reports that the active OpMode has started. */
    public static void opModeStart() {
        if (isRunning()) WolfKit.log("WolfKit/OpModeStatus", "RUNNING");
    }

    /**
     * Reports that the active OpMode has stopped. Closes its log file and clears the objects
     * registered for {@link Logged}.
     */
    public static void opModeStop() {
        Recorder recorder = WolfKit.recorder;
        if (recorder == null) return;
        AnnotationLogger.clear();
        WolfKit.log("WolfKit/OpModeStatus", "STOPPED");
        WolfKit.closeFrameNow();
        recorder.stopFile();
    }

    /**
     * Signals one iteration of the robot loop. Logs all {@link Logged} members unless the OpMode
     * calls {@link WolfKit#logAll()} itself or {@link WolfKitConfig#logAnnotatedOnTelemetryUpdate}
     * is {@code false}.
     */
    public static void loopTick() {
        if (WolfKitConfig.logAnnotatedOnTelemetryUpdate && !WolfKit.explicitLogAll)
            AnnotationLogger.logAll();
    }

    /**
     * Publishes a single value from a thread other than the robot loop.
     *
     * @param key the key to publish under
     * @param value the value
     */
    public static void publish(String key, double value) {
        Batch batch = new Batch();
        if (!batch.begin()) return;
        batch.put(key, value);
        batch.send();
    }

    /**
     * A group of values published together, with one timestamp, from a thread other than the robot
     * loop.
     *
     * <p>An instance may be reused for any number of batches:
     *
     * <pre>{@code
     * if (batch.begin()) {
     *     batch.put("Gamepad1/A", gamepad.a);
     *     batch.send();
     * }
     * }</pre>
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Batch {
        private Recorder recorder;
        private Frame frame;

        /** Creates a batch. Call {@link #begin()} before adding values. */
        public Batch() {}

        /**
         * Starts a batch.
         *
         * @return {@code false} if nothing is recording or connected, in which case the batch must
         *     not be filled or sent
         */
        public boolean begin() {
            recorder = WolfKit.recorder;
            if (recorder == null || !recorder.wantsFrames()) return false;
            frame = recorder.obtain();
            frame.micros = WolfKit.micros();
            return true;
        }

        /**
         * Adds a number.
         *
         * @param key the key to publish under
         * @param value the value
         */
        public void put(String key, double value) {
            frame.number(Keys.slot(key), value);
        }

        /**
         * Adds a boolean.
         *
         * @param key the key to publish under
         * @param value the value
         */
        public void put(String key, boolean value) {
            frame.bool(Keys.slot(key), value);
        }

        /**
         * Adds a string.
         *
         * @param key the key to publish under
         * @param value the value
         */
        public void put(String key, String value) {
            frame.text(Keys.slot(key), value);
        }

        /** Publishes the batch. */
        public void send() {
            recorder.submit(frame);
            frame = null;
        }
    }

    /**
     * Returns whether Driver Station telemetry is due to be published. A caller that receives
     * {@code true} is expected to call {@link #telemetry(String)}. Checking first avoids composing
     * text that would be discarded.
     *
     * @return {@code true} at most ten times a second
     */
    public static boolean telemetryDue() {
        long now = WolfKit.micros();
        if (now - lastTelemetryMicros < TELEMETRY_INTERVAL_MICROS) return false;
        lastTelemetryMicros = now;
        return true;
    }

    /**
     * Publishes the text an OpMode sent to the Driver Station.
     *
     * @param text the telemetry lines, separated by newlines
     */
    public static void telemetry(String text) {
        Batch batch = new Batch();
        if (!batch.begin()) return;
        batch.put("Telemetry", text);
        batch.send();
    }
}
