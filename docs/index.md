---
title: Overview
nav_order: 1
---

# WolfKit

WolfKit is a logging library for FTC robots with its own dashboard, WolfDash.

Robot code logs values with one call. WolfKit then does three things with every value:

| Where it goes | What for |
|---|---|
| WolfDash, a page served by the robot | Watching values, graphing, field view and tuning while you work on the robot |
| A `.wpilog` file per OpMode run | Reviewing a run afterwards in AdvantageScope |
| A NetworkTables 4 stream on port 5810 | Connecting AdvantageScope live |

WolfKit does not depend on FTC Dashboard or Panels, and adds no libraries to the robot app
beyond its own.

**Status:** version 0.2.0 is built against FTC SDK 12.0.0 and has been exercised in the
simulator. It has not yet been validated on a Control Hub.

## Install

In `build.dependencies.gradle` of the robot project:

```groovy
dependencies {
    implementation 'io.github.robowolves10016:wolfkit-core:0.2.0'
    implementation 'io.github.robowolves10016:wolfkit-pedro:0.2.0' // only if you use Pedro Pathing 3
}
```

WolfKit is on Maven Central, which FTC projects already list as a repository.

There is no setup call. WolfKit starts with the Robot Controller app.

## First use

```java
@TeleOp(name = "Example")
public class Example extends OpMode {
    @Override
    public void init() {}

    @Override
    public void loop() {
        double kP = WolfKit.tunable("Shooter/kP", 0.002);

        WolfKit.log("Shooter/VelocityRPM", shooter.getVelocity());
        WolfKit.log("Shooter/AtSpeed", shooter.atSpeed());
        WolfKit.logPose2d("Drive/Robot", x, y, heading);
    }
}
```

Connect a laptop to the robot's Wi-Fi and open `http://192.168.43.1:8080/dash`.

## Pages

| Page | Covers |
|---|---|
| [Logging](logging.md) | `WolfKit.log`, poses, keys and units |
| [Annotations](annotations.md) | `@Logged`, `register`, `logAll`, adapters |
| [Tuning](tuning.md) | `WolfKit.tunable` and `WolfKit.button` |
| [WolfDash](dashboard.md) | The dashboard's widgets and layouts |
| [Log files and AdvantageScope](logs.md) | Recorded runs and live AdvantageScope |
| [Automatic features](automatic.md) | Gamepads, battery, telemetry capture, loop time, and their settings |
| [Pedro Pathing](pedro.md) | Logging Pedro poses and paths |
| [Simulator](simulator.md) | Running WolfDash without a robot |
