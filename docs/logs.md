---
title: Log files and AdvantageScope
nav_order: 6
---

# Log files and AdvantageScope

## Log files

WolfKit records one `.wpilog` file for each OpMode run, from init to stop. A file contains
everything logged during the run, including gamepads, tunables and telemetry text.

Files are stored in `WolfKit/logs` on the robot's storage and named with the date, time and
OpMode name. The 50 most recent are kept.

To get a file, open the **Logs** widget in WolfDash and click it. Open the downloaded file in
AdvantageScope.

Logging runs whether or not a laptop is connected, so matches are recorded.

## AdvantageScope live

AdvantageScope can also connect while the robot is running.

1. Connect the laptop to the robot's Wi-Fi.
2. In AdvantageScope's preferences, set the robot address to `192.168.43.1` and the live source
   to NetworkTables 4.
3. Connect.

Poses logged with `logPose2d`, `logPoses2d` and `logPose3d` are standard WPILib `Pose2d`,
`Pose2d[]` and `Pose3d` values, so they can be dragged onto AdvantageScope's 2D and 3D field
tabs. AdvantageScope and WolfDash can be connected at the same time.
