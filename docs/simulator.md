---
title: Simulator
nav_order: 9
---

# Simulator

The simulator runs WolfKit on a laptop with a simulated robot. Use it to try WolfDash or to
work on the dashboard itself without a Control Hub.

From a clone of the WolfKit repository:

```
./gradlew :sim:run
```

Then open `http://localhost:8080/dash/`.

The simulated robot has three OpModes: a figure-eight autonomous, a TeleOp with a flywheel,
and a flywheel step test. They log poses, a path, tunables, buttons, gamepads and telemetry,
so every widget has data.

The page is served from `core/src/main/assets/wolfdash`, so edits to the dashboard's files show
on reload. Layouts and log files from the simulator are kept in `sim/data.nosync`.
