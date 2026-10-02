---
title: Annotations
nav_order: 3
---

# Annotations

`@Logged` marks a field, or a method with no parameters, to be logged every loop without a
`WolfKit.log` call.

```java
public class Shooter {
    @Logged private double velocityRpm;                 // Shooter/velocityRpm
    @Logged(key = "Shooter/Ready") boolean atSpeed;     // Shooter/Ready
    @Logged public double getCurrentAmps() { ... }      // Shooter/CurrentAmps
}
```

## Keys

The default key is the class name, a slash and the member name. For a method, a leading `get`
is dropped. `key` replaces the whole key.

## What can be annotated

| Type | Logged as |
|---|---|
| `double`, `int`, `long`, `float` and other numbers | Number |
| `boolean` | Boolean |
| `String`, enums | Text |
| `double[]` | Number array |
| SDK `Pose2D`; Pedro `Pose` and `Path` with the `pedro` module | Pose or path |
| Any other object | Its own `@Logged` members, under its class name |

Fields may be private or static. Prefer annotating a field over a method where you can: a
method's return value is boxed on every loop.

## Nested objects

An annotated field that holds one of your own objects logs that object's `@Logged` members.
Give the field a key to tell instances of the same class apart:

```java
public class Drive {
    @Logged(key = "FrontLeft") Module frontLeft;   // FrontLeft/angle, FrontLeft/speed
    @Logged(key = "FrontRight") Module frontRight; // FrontRight/angle, FrontRight/speed
}
```

## Registering objects

WolfKit needs to know which objects to read.

- **The OpMode** is registered automatically. Annotated fields on the OpMode, and objects
  reachable through annotated fields, need nothing more.
- **Anything else** is registered once with `WolfKit.register(this)`, typically in its
  constructor.

Registrations last for one OpMode run.

## When values are logged

- **Iterative OpModes** (`OpMode`): after every `loop()`, automatically.
- **LinearOpModes**: each time the OpMode calls `telemetry.update()`.
- **Explicitly**: call `WolfKit.logAll()` once per loop. After an OpMode calls it, the
  automatic logging is switched off for that run so nothing is logged twice.

## Other types

`WolfKit.adapter` teaches `@Logged` a type it does not know:

```java
WolfKit.adapter(Vector2d.class, (key, v) -> WolfKit.log(key, new double[] {v.x, v.y}));
```
