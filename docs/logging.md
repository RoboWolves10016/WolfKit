---
title: Logging
nav_order: 2
---

# Logging

All logging goes through static methods on `com.robowolves.wolfkit.WolfKit`. Call them from
anywhere in robot code, once per loop for each value.

```java
WolfKit.log("Shooter/VelocityRPM", rpm);        // number
WolfKit.log("Intake/Running", running);         // boolean
WolfKit.log("Auto/State", state);               // enum or string, logged as text
WolfKit.log("Drive/WheelPowers", powers);       // double[]
```

There is no `update()` call. WolfKit groups the values logged in one loop into a frame with
one timestamp; it starts a new frame when a key is logged a second time.

## Keys

A key is the name of a value. Slashes group keys: `Shooter/VelocityRPM` appears under
`Shooter` in the dashboard. A key keeps the type it was first logged with; logging a different
type to the same key is ignored.

Keys under `WolfKit/`, `Gamepad1/`, `Gamepad2/`, `Tuning/`, `Buttons/` and `Telemetry` are used
by WolfKit itself.

## Poses

Poses are in inches and radians in the FTC field coordinate system, with the origin at the
centre of the field.

| Method | Use |
|---|---|
| `logPose2d(key, x, y, heading)` | A pose on the floor: the robot, a target, a vision estimate |
| `logPedroPose(key, x, y, heading)` | The same, given in Pedro Pathing coordinates (origin at a field corner) |
| `logPoses2d(key, xs, ys, headings)` | A sequence of floor poses, such as a path |
| `logPose3d(key, x, y, z, roll, pitch, yaw)` | A pose in space: a mechanism, a camera, a game piece |

```java
WolfKit.logPose2d("Drive/Odometry", x, y, heading);
WolfKit.logPose2d("Drive/Vision", visionX, visionY, visionHeading);
WolfKit.logPedroPose("Drive/Target", target.x(), target.y(), target.heading());
```

Each pose key can be shown on the field in WolfDash as a robot, a ghost or an arrow. Logging
several pose sources under different keys is how you compare them on one field.

A path changes rarely, so log it when it is built, not every loop.

## Cost

Values that have not changed since the previous loop are not sent or written again. Logging a
number, boolean or pose does not allocate memory once its key has been used. Text allocates
only if the string itself is built fresh each loop.

Logging does not read hardware. A value that needs an extra sensor read to obtain costs that
read whether or not it is logged.
