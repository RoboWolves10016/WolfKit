/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field, or a method with no parameters, whose value is logged on every loop.
 *
 * <pre>
 * public class Shooter {
 *     &#64;Logged private double velocityRpm;            // logged as "Shooter/velocityRpm"
 *     &#64;Logged(key = "Shooter/Ready") boolean atSpeed; // logged under the key given
 *     &#64;Logged public double getCurrentAmps() { ... } // logged as "Shooter/CurrentAmps"
 * }
 * </pre>
 *
 * <p>Numbers, booleans, strings, enums, {@code double[]} and types with a registered {@link
 * LogAdapter} are logged as values. A field holding any other object is traversed: that object's
 * own {@code @Logged} members are logged under its class name, or under {@link #key()} when one is
 * given. Giving a key distinguishes several instances of one class.
 *
 * <p>On a robot the active OpMode is registered automatically, so annotating its fields is
 * sufficient. Any other object must be passed to {@link WolfKit#register(Object)} once, unless it
 * is reachable through an annotated field.
 *
 * @see WolfKit#logAll()
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Logged {
    /**
     * The key to log under. Defaults to the declaring class's simple name, a slash and the member
     * name. For a method, a leading {@code get} is removed from the name.
     *
     * @return the key, or an empty string for the default
     */
    String key() default "";
}
