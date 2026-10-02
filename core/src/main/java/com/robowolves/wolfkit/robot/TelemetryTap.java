/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit.robot;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.robowolves.wolfkit.WolfKitRuntime;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A pass-through for an OpMode's {@link Telemetry} that publishes its text to WolfKit.
 *
 * <p>The OpMode's telemetry is replaced by a dynamic proxy. Every call is forwarded to the original
 * object, so the Driver Station is unaffected. The proxy implements every interface of the
 * original, which the SDK tests for.
 *
 * <p>Lines added with {@code addData} and {@code addLine} are captured. Changes made later through
 * {@code Item.setValue}, and data added to a {@code Line}, are not.
 *
 * <p>Each call to {@code update} is also reported to {@link WolfKitRuntime#loopTick()}.
 */
final class TelemetryTap implements InvocationHandler {
    private final Telemetry real;

    /**
     * The lines added since the last clear. An element is a {@code String}, or an {@code Object[]}
     * of {@code {caption, value}} or {@code {caption, value, format}}.
     */
    private final List<Object> lines = new ArrayList<>();

    private TelemetryTap(Telemetry real) {
        this.real = real;
    }

    /** Replaces an OpMode's telemetry with a pass-through, unless one is already installed. */
    static void install(OpMode opMode) {
        Telemetry real = opMode.telemetry;
        if (real == null || Proxy.isProxyClass(real.getClass())) return;
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = real.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> implemented : type.getInterfaces()) interfaces.add(implemented);
        }
        opMode.telemetry =
                (Telemetry)
                        Proxy.newProxyInstance(
                                real.getClass().getClassLoader(),
                                interfaces.toArray(new Class<?>[0]),
                                new TelemetryTap(real));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object result;
        try {
            result = method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        try {
            listen(method.getName(), args == null ? new Object[0] : args, result);
        } catch (RuntimeException ignored) {
            // A failure to capture must not affect the OpMode's telemetry.
        }
        return result;
    }

    /**
     * Records the effect of a telemetry call that has already been forwarded.
     *
     * @param name the name of the method called
     * @param args its arguments
     * @param result the value the original returned
     */
    private void listen(String name, Object[] args, Object result) {
        switch (name) {
            case "addData":
                if (args.length == 2) lines.add(new Object[] {args[0], args[1]});
                else if (args.length == 3 && args[2] instanceof Object[]) {
                    lines.add(
                            args[0] + " : " + String.format((String) args[1], (Object[]) args[2]));
                } else if (args.length == 3) lines.add(new Object[] {args[0], args[2], args[1]});
                break;
            case "addLine":
                lines.add(args.length == 1 ? String.valueOf(args[0]) : "");
                break;
            case "clear":
            case "clearAll":
            case "resetTelemetryForOpMode":
                lines.clear();
                break;
            case "update":
                // The SDK calls update() after every loop() of an iterative OpMode, and a
                // LinearOpMode typically calls it once per iteration.
                WolfKitRuntime.loopTick();
            // fall through
            case "tryUpdateIfDirty":
                if (!Boolean.TRUE.equals(result)) break; // The SDK did not transmit this update.
                if (WolfKitRuntime.telemetryDue()) WolfKitRuntime.telemetry(compose());
                if (real.isAutoClear()) lines.clear();
                break;
            default:
        }
    }

    /** Returns the captured lines as text, formatted as the Driver Station shows them. */
    private String compose() {
        StringBuilder text = new StringBuilder();
        for (Object line : lines) {
            if (text.length() > 0) text.append('\n');
            if (line instanceof String) {
                text.append(line);
                continue;
            }
            // A Func value is evaluated at update time, as the SDK does.
            Object[] parts = (Object[]) line;
            Object value = parts[1] instanceof Func ? ((Func<?>) parts[1]).value() : parts[1];
            text.append(parts[0]).append(" : ");
            text.append(
                    parts.length == 3
                            ? String.format((String) parts[2], value)
                            : String.valueOf(value));
        }
        return text.toString();
    }
}
