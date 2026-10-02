---
title: Automatic features
nav_order: 7
---

# Automatic features

WolfKit logs several things without being asked.

| Feature | Logged under | Notes |
|---|---|---|
| Gamepads | `Gamepad1/`, `Gamepad2/` | Every stick, trigger and button, sampled every 20 ms while an OpMode is active |
| Battery | `WolfKit/BatteryVolts` | Read every 10 seconds |
| Loop time | `WolfKit/LoopMs` | Time between loops, in milliseconds |
| OpMode | `WolfKit/OpMode`, `WolfKit/OpModeStatus` | Name, and `INIT`, `RUNNING` or `STOPPED` |
| Telemetry text | `Telemetry` | What the OpMode sends with `telemetry.addData` and `addLine`, at most ten times a second |

Telemetry capture needs no change to existing OpModes. It sees lines added with `addData` and
`addLine`. It does not see later changes made through `Item.setValue`, or data added to a
`Line` object.

Gamepads are read on a timer, not in step with your loop. A press shorter than the sampling
interval can be missed in the log.

## Settings

Every automatic feature has a switch in `WolfKitConfig`. Set them from a method annotated with
the SDK's `@OnCreate`, which runs once when the Robot Controller app starts:

```java
public class WolfKitSettings {
    @OnCreate
    public static void configure(Context context) {
        WolfKitConfig.logGamepads = false;
        WolfKitConfig.batteryIntervalSeconds = 30;
    }
}
```

| Setting | Default | Effect |
|---|---|---|
| `recordLogFiles` | `true` | Record a log file for each OpMode run |
| `keepLogFiles` | `50` | Number of log files kept before the oldest are deleted |
| `logGamepads` | `true` | Log both gamepads |
| `gamepadIntervalMs` | `20` | Milliseconds between gamepad samples |
| `logBattery` | `true` | Log battery voltage |
| `batteryIntervalSeconds` | `10` | Seconds between battery readings |
| `captureTelemetry` | `true` | Publish Driver Station telemetry text |
| `registerOpModes` | `true` | Register each OpMode for `@Logged` before its init |
| `logAnnotatedOnTelemetryUpdate` | `true` | Log `@Logged` members on each telemetry update |
| `logLoopTime` | `true` | Log loop time |

Each battery reading is a command to the hub and can delay one loop by 2 to 3 ms, which is why
it defaults to a long interval.

Turning off `captureTelemetry` also stops `@Logged` members being logged automatically. Call
`WolfKit.logAll()` in your loop instead.

These settings only affect what WolfKit does by itself. `WolfKit.log`, `tunable` and the rest
always work.
