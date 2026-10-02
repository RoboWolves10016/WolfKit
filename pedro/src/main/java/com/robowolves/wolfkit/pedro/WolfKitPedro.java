/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit.pedro;

import com.pedropathing.math.Pose;
import com.pedropathing.paths.Path;
import com.robowolves.wolfkit.WolfKit;

/**
 * Logs Pedro Pathing poses and paths.
 *
 * <p>Pedro Pathing measures from a corner of the field. Values are converted to the field-centre
 * coordinates WolfKit uses.
 *
 * <p>Loading this class registers adapters so that {@link com.robowolves.wolfkit.Logged} fields may
 * hold a {@link Pose} or {@link Path}. WolfKit loads it at start-up when this module is present.
 */
public final class WolfKitPedro {
    private static final int PATH_SAMPLES = 50;

    static {
        WolfKit.adapter(Pose.class, WolfKitPedro::log);
        WolfKit.adapter(Path.class, WolfKitPedro::log);
    }

    private WolfKitPedro() {}

    /**
     * Logs a pose.
     *
     * @param key the key to log under
     * @param pose the pose, in Pedro coordinates
     */
    public static void log(String key, Pose pose) {
        WolfKit.logPedroPose(key, pose.x(), pose.y(), pose.heading());
    }

    /**
     * Logs a path as a sequence of poses sampled along its length. A path does not change once
     * built, so it need only be logged once.
     *
     * @param key the key to log under
     * @param path the path, in Pedro coordinates
     */
    public static void log(String key, Path path) {
        double[] xs = new double[PATH_SAMPLES],
                ys = new double[PATH_SAMPLES],
                headings = new double[PATH_SAMPLES];
        for (int i = 0; i < PATH_SAMPLES; i++) {
            Pose pose = path.get(i / (PATH_SAMPLES - 1.0));
            xs[i] = pose.y() - 72;
            ys[i] = 72 - pose.x();
            headings[i] = pose.heading() - Math.PI / 2;
        }
        WolfKit.logPoses2d(key, xs, ys, headings);
    }
}
