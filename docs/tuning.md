---
title: Tuning
nav_order: 4
---

# Tuning

## Tunables

`WolfKit.tunable` returns a value that can be changed from WolfDash while the robot runs. Call
it every loop and use what it returns.

```java
double kP = WolfKit.tunable("Shooter/kP", 0.002);
boolean enabled = WolfKit.tunable("Shooter/Enabled", true);
String mode = WolfKit.tunable("Auto/Mode", "LEFT");
```

The second argument is the value used until someone changes it. Tunables appear in the
Tunables widget, grouped by the slashes in their key, and are logged under `Tuning/`.

A changed value lasts until the Robot Controller app restarts. It is not written back to your
code: the Tunables widget marks what has changed and "Copy changed" puts those values on the
clipboard so you can update the defaults yourself.

## Buttons

`WolfKit.button` puts a button in the Tunables widget and returns `true` once for each click.

```java
if (WolfKit.button("Zero Turret")) {
    turret.zero();
}
```

Call it every loop. A click that is not read within half a second is discarded, so a click made
while no OpMode is running does nothing later.
