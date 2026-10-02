/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit.sim;

import com.robowolves.wolfkit.DashHttp;
import com.robowolves.wolfkit.Logged;
import com.robowolves.wolfkit.WolfKit;
import com.robowolves.wolfkit.WolfKitRuntime;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A simulated robot with three OpModes that log values typical of a real robot: poses, a path, a
 * flywheel under closed-loop control, gamepads, tunables and buttons.
 */
final class SimRobot implements DashHttp.Robot {
    private static final String AUTO = "Auto: Figure Eight";
    private static final String TELEOP = "TeleOp Demo";
    private static final String FLYWHEEL = "Flywheel Tuning";

    private final Random random = new Random(7);
    private Thread opMode;
    private volatile boolean started;
    private volatile boolean stopRequested;

    /** A subsystem logged through {@link Logged}. */
    static final class Intake {
        enum State {
            IDLE,
            INTAKING
        }

        @Logged State state = State.IDLE;
        @Logged double currentAmps;

        @Logged(key = "Intake/Running")
        boolean isRunning() {
            return state == State.INTAKING;
        }
    }

    private final Intake intake = new Intake();
    private final WolfKitRuntime.Batch gamepad = new WolfKitRuntime.Batch();

    private double x, y, heading;
    private double flywheelRpm;
    private double battery = 13.1;

    SimRobot() {
        Thread health =
                new Thread(
                        () -> {
                            while (true) {
                                WolfKitRuntime.publish(
                                        "WolfKit/BatteryVolts",
                                        battery + random.nextGaussian() * 0.01);
                                sleep(10_000);
                            }
                        },
                        "Sim battery");
        health.setDaemon(true);
        health.start();
    }

    @Override
    public List<String[]> opModes() {
        return Arrays.asList(
                new String[] {AUTO, "Auto", "AUTONOMOUS"},
                new String[] {TELEOP, "Drive", "TELEOP"},
                new String[] {FLYWHEEL, "Test", "TELEOP"});
    }

    @Override
    public synchronized void init(String name) {
        stop();
        started = false;
        stopRequested = false;
        x = -48;
        y = -48;
        heading = 0;
        flywheelRpm = 0;
        WolfKitRuntime.opModeInit(name);
        WolfKit.register(intake);
        opMode = new Thread(() -> run(name), "Sim OpMode");
        opMode.start();
    }

    @Override
    public void start() {
        if (opMode == null || started) return;
        started = true;
        WolfKitRuntime.opModeStart();
    }

    @Override
    public synchronized void stop() {
        if (opMode == null) return;
        stopRequested = true;
        try {
            opMode.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        opMode = null;
        WolfKitRuntime.opModeStop();
    }

    /** Runs an OpMode until a stop is requested. Runs on the OpMode thread. */
    private void run(String name) {
        long startNanos = 0;
        int loops = 0;
        if (name.equals(AUTO)) logPath();
        while (!stopRequested) {
            if (started && startNanos == 0) startNanos = System.nanoTime();
            double t = startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1e9;
            double dt = 0.01;

            if (name.equals(AUTO)) auto(t, dt);
            else if (name.equals(TELEOP)) teleop(t, dt);
            else flywheel(t, dt, 2000 + 1500 * (((int) (t / 3)) % 2));

            battery = 13.1 - 0.4 * Math.min(1, t / 120) - flywheelRpm / 20000;
            // A 10 ms loop with an occasional slow iteration.
            sleep(++loops % 400 == 0 ? 45 : 10);
        }
    }

    /** Logs the figure-eight path as a sequence of poses. */
    private void logPath() {
        int points = 120;
        double[] xs = new double[points], ys = new double[points], headings = new double[points];
        for (int i = 0; i < points; i++) {
            double s = 2 * Math.PI * i / (points - 1);
            xs[i] = pathX(s);
            ys[i] = pathY(s);
            headings[i] = Math.atan2(pathY(s + 0.01) - ys[i], pathX(s + 0.01) - xs[i]);
        }
        WolfKit.logPoses2d("Drive/Path", xs, ys, headings);
    }

    private static double pathX(double s) {
        return 48 * Math.sin(s);
    }

    private static double pathY(double s) {
        return 40 * Math.sin(2 * s);
    }

    /**
     * Advances the autonomous OpMode, in which the robot follows a target moving along the path.
     *
     * @param t the time since start, in seconds
     * @param dt the time step, in seconds
     */
    private void auto(double t, double dt) {
        double s = t * 0.5;
        double targetX = pathX(s), targetY = pathY(s);
        double targetHeading = Math.atan2(pathY(s + 0.01) - targetY, pathX(s + 0.01) - targetX);
        if (t == 0) {
            x = targetX - 6;
            y = targetY - 4;
            heading = targetHeading;
        }

        // Proportional control toward the target. A lower gain gives a larger following error.
        double kP = WolfKit.tunable("Drive/kP", 10.0);
        double headingKP = WolfKit.tunable("Drive/HeadingkP", 8.0);
        x += (targetX - x) * Math.min(1, kP * dt);
        y += (targetY - y) * Math.min(1, kP * dt);
        heading += wrap(targetHeading - heading) * Math.min(1, headingKP * dt);

        WolfKit.log(
                "Auto/State",
                t == 0
                        ? "WAITING"
                        : ((int) (s / Math.PI)) % 2 == 0
                                ? "FOLLOW_LEFT_LOOP"
                                : "FOLLOW_RIGHT_LOOP");
        WolfKit.logPose2d("Drive/Target", targetX, targetY, targetHeading);
        logDrive(t);
        WolfKit.log("Drive/ErrorInches", Math.hypot(targetX - x, targetY - y));
        WolfKit.log("Drive/HeadingErrorDeg", Math.toDegrees(wrap(targetHeading - heading)));
        if (WolfKitRuntime.telemetryDue())
            WolfKitRuntime.telemetry(
                    String.format(
                            "State : %s\nError : %.2f in\nPose : (%.1f, %.1f)",
                            t == 0 ? "WAITING" : "FOLLOWING",
                            Math.hypot(targetX - x, targetY - y),
                            x,
                            y));
    }

    /** Advances the TeleOp OpMode, driven by simulated gamepad input. */
    private void teleop(double t, double dt) {
        double stickX = Math.sin(t * 0.4), stickY = Math.cos(t * 0.27);
        double speed = started ? 30 : 0;
        heading = wrap(heading + stickX * 1.5 * dt);
        x = clamp(x + Math.cos(heading) * stickY * speed * dt, -62, 62);
        y = clamp(y + Math.sin(heading) * stickY * speed * dt, -62, 62);
        boolean intaking = ((int) (t / 4)) % 3 == 1;
        if (WolfKit.button("Reset Pose")) {
            x = 0;
            y = 0;
            heading = 0;
        }

        // On a robot the gamepads are logged by the core module.
        if (gamepad.begin()) {
            gamepad.put("Gamepad1/LeftStickY", stickY);
            gamepad.put("Gamepad1/RightStickX", stickX);
            gamepad.put("Gamepad1/RightTrigger", started ? 1.0 : 0.0);
            gamepad.put("Gamepad1/A", intaking);
            gamepad.put("Gamepad1/RightBumper", false);
            gamepad.send();
        }
        if (WolfKitRuntime.telemetryDue())
            WolfKitRuntime.telemetry(
                    String.format(
                            "Intake : %s\n"
                                + "Flywheel : %.0f rpm\n"
                                + "Pose : (%.1f, %.1f) %.0f deg\n"
                                + "Left stick : %.2f",
                            intaking ? "INTAKING" : "IDLE",
                            flywheelRpm,
                            x,
                            y,
                            Math.toDegrees(heading),
                            stickY));
        intake.state = intaking ? Intake.State.INTAKING : Intake.State.IDLE;
        intake.currentAmps = intaking ? 2.4 + random.nextGaussian() * 0.15 : 0.1;
        WolfKit.logAll();
        logDrive(t);
        flywheel(t, dt, started ? WolfKit.tunable("Shooter/TargetRPM", 3000.0) : 0);
    }

    /**
     * Advances the flywheel model under feedforward and proportional control.
     *
     * @param target the target speed, in RPM
     */
    private void flywheel(double t, double dt, double target) {
        double kP = WolfKit.tunable("Shooter/kP", 0.0015);
        double kF = WolfKit.tunable("Shooter/kF", 0.00021);
        boolean enabled = WolfKit.tunable("Shooter/Enabled", true);
        if (!started || !enabled) target = 0;

        double power = clamp(kF * target + kP * (target - flywheelRpm), 0, 1);
        // First-order motor model with a free speed of 4800 RPM.
        flywheelRpm += (power * 4800 - flywheelRpm) * 1.8 * dt;
        if (WolfKit.button("Fire")) flywheelRpm *= 0.82;

        WolfKit.log("Shooter/TargetRPM", target);
        WolfKit.log("Shooter/VelocityRPM", flywheelRpm + random.nextGaussian() * 6);
        WolfKit.log("Shooter/Power", power);
        WolfKit.log("Shooter/AtSpeed", target > 0 && Math.abs(target - flywheelRpm) < 60);
    }

    /** Logs the odometry pose every iteration and a noisy vision pose every tenth. */
    private void logDrive(double t) {
        WolfKit.logPose2d("Drive/Odometry", x, y, heading);
        if (((int) (t * 100)) % 10 == 0) {
            WolfKit.logPose2d(
                    "Drive/Vision",
                    x + random.nextGaussian() * 1.5,
                    y + random.nextGaussian() * 1.5,
                    heading + random.nextGaussian() * 0.03);
        }
    }

    /** Returns an angle normalised to the range -pi to pi. */
    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
