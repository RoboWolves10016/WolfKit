/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

/**
 * WPILib struct schemas for the pose types WolfKit logs.
 *
 * <p>AdvantageScope decodes these structs natively. As WPILib defines them, distances are in metres
 * and angles in radians.
 */
final class Schemas {
    private Schemas() {}

    /**
     * Returns the schemas needed to decode a kind of value.
     *
     * @param kind a {@link Frame} kind
     * @return {@code {type name, schema}} pairs with nested types first; empty if none are needed
     */
    static String[][] of(byte kind) {
        if (kind == Frame.POSE2D || kind == Frame.POSE2D_ARRAY) {
            return new String[][] {
                {"struct:Translation2d", "double x;double y"},
                {"struct:Rotation2d", "double value"},
                {"struct:Pose2d", "Translation2d translation;Rotation2d rotation"},
            };
        }
        if (kind == Frame.POSE3D) {
            return new String[][] {
                {"struct:Translation3d", "double x;double y;double z"},
                {"struct:Quaternion", "double w;double x;double y;double z"},
                {"struct:Rotation3d", "Quaternion q"},
                {"struct:Pose3d", "Translation3d translation;Rotation3d rotation"},
            };
        }
        return new String[0][];
    }
}
