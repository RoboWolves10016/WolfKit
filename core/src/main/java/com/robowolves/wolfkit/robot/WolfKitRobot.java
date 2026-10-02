/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit.robot;

import android.content.Context;
import android.content.res.AssetManager;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import com.qualcomm.robotcore.util.RobotLog;
import com.qualcomm.robotcore.util.WebHandlerManager;
import com.robowolves.wolfkit.DashHttp;
import com.robowolves.wolfkit.WolfKit;
import com.robowolves.wolfkit.WolfKitConfig;
import com.robowolves.wolfkit.WolfKitRuntime;

import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.ftccommon.external.OnDestroy;
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta;
import org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Integrates WolfKit with the FTC SDK.
 *
 * <p>The SDK discovers the annotated methods of this class by scanning the app and calls them when
 * the robot controller starts. Robot code does not call this class.
 *
 * <p>This class starts WolfKit, serves the WolfDash page from the SDK's web server, follows OpMode
 * transitions, and logs the battery voltage and gamepads from a background thread.
 */
public final class WolfKitRobot implements OpModeManagerNotifier.Notifications, DashHttp.Robot {
    private static final String TAG = "WolfKit";
    private static final String ASSET_ROOT = "wolfdash";
    private static final WolfKitRobot instance = new WolfKitRobot();

    private OpModeManagerImpl opModeManager;
    private volatile boolean logging;
    private Thread monitorThread;

    private WolfKitRobot() {}

    @OnCreateEventLoop
    /**
     * Starts WolfKit. Called by the SDK when the event loop is created.
     *
     * @param context the application context
     * @param eventLoop the event loop, which provides the OpMode manager
     */
    public static void attachEventLoop(Context context, FtcEventLoop eventLoop) {
        try {
            WolfKitRuntime.start(new File(AppUtil.ROOT_FOLDER, "WolfKit"));
        } catch (IOException e) {
            RobotLog.ww(
                    TAG,
                    e,
                    "Port %d is taken. WolfKit logging is disabled.",
                    WolfKitRuntime.livePort());
            return;
        }
        WolfKit.adapter(
                Pose2D.class,
                (key, pose) ->
                        WolfKit.logPose2d(
                                key,
                                pose.getX(DistanceUnit.INCH),
                                pose.getY(DistanceUnit.INCH),
                                pose.getHeading(AngleUnit.RADIANS)));
        instance.watch(eventLoop.getOpModeManager());
        RobotLog.ii(TAG, "WolfDash is at http://192.168.43.1:8080%s/", DashHttp.ROOT);
    }

    /**
     * Registers the WolfDash page and its API with the SDK's web server. Called by the SDK when the
     * web server starts.
     *
     * @param context the application context, which provides the page's assets
     * @param manager the web server's handler registry
     */
    @WebHandlerRegistrar
    public static void attachWebServer(Context context, WebHandlerManager manager) {
        AssetManager assets = context.getAssets();
        DashHttp dash = new DashHttp(path -> assets.open(ASSET_ROOT + "/" + path), instance);
        WebHandler handler = dash::respond;

        // The SDK's web server matches whole paths, so each file is registered individually.
        List<String> paths = new ArrayList<>();
        paths.add("");
        paths.add("/");
        paths.addAll(DashHttp.API_PATHS);
        listAssets(assets, ASSET_ROOT, "", paths);
        for (String path : paths) manager.register(DashHttp.ROOT + path, handler);
    }

    /**
     * Collects the paths of all files under an asset folder.
     *
     * @param prefix the path of {@code folder} relative to the page root
     * @param out receives each file's path, with a leading slash
     */
    private static void listAssets(
            AssetManager assets, String folder, String prefix, List<String> out) {
        try {
            String[] children = assets.list(folder);
            if (children == null) return;
            for (String child : children) {
                // AssetManager cannot distinguish files from folders. A name with an extension
                // is taken to be a file.
                if (child.contains(".")) out.add(prefix + "/" + child);
                else listAssets(assets, folder + "/" + child, prefix + "/" + child, out);
            }
        } catch (IOException e) {
            RobotLog.ww(TAG, e, "Could not list WolfDash files");
        }
    }

    @OnDestroy
    /** Stops WolfKit. Called by the SDK when the robot controller app is destroyed. */
    public static void stop(Context context) {
        WolfKitRuntime.stop();
    }

    /** Listens for OpMode transitions on a manager and starts the monitor thread on first use. */
    private synchronized void watch(OpModeManagerImpl manager) {
        if (opModeManager != null) opModeManager.unregisterListener(this);
        opModeManager = manager;
        manager.registerListener(this);
        if (monitorThread == null) {
            monitorThread = new Thread(this::monitor, "WolfKit monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
    }

    /**
     * Logs the gamepads and the battery voltage at their configured intervals. Runs on the monitor
     * thread.
     */
    private void monitor() {
        WolfKitRuntime.Batch batch = new WolfKitRuntime.Batch();
        long lastBatteryMillis = 0;
        while (true) {
            try {
                Thread.sleep(Math.max(5, WolfKitConfig.gamepadIntervalMs));
                OpModeManagerImpl manager = opModeManager;
                if (manager == null || !WolfKitRuntime.isRunning()) continue;
                if (logging && WolfKitConfig.logGamepads && batch.begin()) {
                    OpMode opMode = manager.getActiveOpMode();
                    addGamepad(batch, GAMEPAD1_KEYS, opMode.gamepad1);
                    addGamepad(batch, GAMEPAD2_KEYS, opMode.gamepad2);
                    batch.send();
                }
                long now = System.currentTimeMillis();
                if (WolfKitConfig.logBattery
                        && now - lastBatteryMillis
                                >= WolfKitConfig.batteryIntervalSeconds * 1000L) {
                    lastBatteryMillis = now;
                    publishBattery(manager);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // The hardware map and OpMode are unavailable during a robot restart.
            }
        }
    }

    /** Publishes the lowest voltage reported by the robot's voltage sensors. */
    private static void publishBattery(OpModeManagerImpl manager) {
        double lowest = Double.POSITIVE_INFINITY;
        for (VoltageSensor sensor : manager.getHardwareMap().voltageSensor) {
            double volts = sensor.getVoltage();
            if (volts > 0) lowest = Math.min(lowest, volts);
        }
        if (lowest != Double.POSITIVE_INFINITY)
            WolfKitRuntime.publish("WolfKit/BatteryVolts", lowest);
    }

    private static final String[] GAMEPAD_FIELDS = {
        "LeftStickX",
        "LeftStickY",
        "RightStickX",
        "RightStickY",
        "LeftTrigger",
        "RightTrigger",
        "A",
        "B",
        "X",
        "Y",
        "DpadUp",
        "DpadDown",
        "DpadLeft",
        "DpadRight",
        "LeftBumper",
        "RightBumper",
        "LeftStickButton",
        "RightStickButton",
        "Start",
        "Back",
        "Guide",
        "Touchpad"
    };
    // Keys are built once, so that sampling does not allocate.
    private static final String[] GAMEPAD1_KEYS = gamepadKeys("Gamepad1/");
    private static final String[] GAMEPAD2_KEYS = gamepadKeys("Gamepad2/");

    private static String[] gamepadKeys(String prefix) {
        String[] keys = new String[GAMEPAD_FIELDS.length];
        for (int i = 0; i < keys.length; i++) keys[i] = prefix + GAMEPAD_FIELDS[i];
        return keys;
    }

    /**
     * Adds the state of a gamepad to a batch. Values are added in the order of {@link
     * #GAMEPAD_FIELDS}.
     *
     * @param keys the keys for this gamepad, in the same order
     */
    private static void addGamepad(WolfKitRuntime.Batch out, String[] keys, Gamepad pad) {
        if (pad == null) return;
        int i = 0;
        out.put(keys[i++], (double) pad.left_stick_x);
        out.put(keys[i++], (double) pad.left_stick_y);
        out.put(keys[i++], (double) pad.right_stick_x);
        out.put(keys[i++], (double) pad.right_stick_y);
        out.put(keys[i++], (double) pad.left_trigger);
        out.put(keys[i++], (double) pad.right_trigger);
        out.put(keys[i++], pad.a);
        out.put(keys[i++], pad.b);
        out.put(keys[i++], pad.x);
        out.put(keys[i++], pad.y);
        out.put(keys[i++], pad.dpad_up);
        out.put(keys[i++], pad.dpad_down);
        out.put(keys[i++], pad.dpad_left);
        out.put(keys[i++], pad.dpad_right);
        out.put(keys[i++], pad.left_bumper);
        out.put(keys[i++], pad.right_bumper);
        out.put(keys[i++], pad.left_stick_button);
        out.put(keys[i++], pad.right_stick_button);
        out.put(keys[i++], pad.start);
        out.put(keys[i++], pad.back);
        out.put(keys[i++], pad.guide);
        out.put(keys[i], pad.touchpad);
    }

    @Override
    public void onOpModePreInit(OpMode opMode) {
        String name = opModeManager.getActiveOpModeName();
        // The SDK runs a placeholder OpMode between user OpModes. It is not logged.
        logging = !OpModeManagerImpl.DEFAULT_OP_MODE_NAME.equals(name);
        if (logging) {
            WolfKitRuntime.opModeInit(name);
            if (WolfKitConfig.captureTelemetry) TelemetryTap.install(opMode);
            if (WolfKitConfig.registerOpModes) WolfKit.register(opMode);
        }
    }

    @Override
    public void onOpModePreStart(OpMode opMode) {
        if (logging) WolfKitRuntime.opModeStart();
    }

    @Override
    public void onOpModePostStop(OpMode opMode) {
        if (logging) WolfKitRuntime.opModeStop();
        logging = false;
    }

    @Override
    public List<String[]> opModes() {
        List<String[]> result = new ArrayList<>();
        for (OpModeMeta meta : RegisteredOpModes.getInstance().getOpModes()) {
            if (meta.flavor == OpModeMeta.Flavor.SYSTEM) continue;
            result.add(new String[] {meta.name, meta.group, meta.flavor.name()});
        }
        return result;
    }

    @Override
    public void init(String opModeName) {
        if (opModeManager != null) opModeManager.initOpMode(opModeName);
    }

    @Override
    public void start() {
        if (opModeManager != null) opModeManager.startActiveOpMode();
    }

    @Override
    public void stop() {
        if (opModeManager != null) opModeManager.stopActiveOpMode();
    }
}
