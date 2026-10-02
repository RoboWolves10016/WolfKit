---
title: WolfDash
nav_order: 5
---

# WolfDash

WolfDash is served by the robot at `http://192.168.43.1:8080/dash`. Open it from a laptop
connected to the robot's Wi-Fi. Nothing is installed on the laptop.

The top bar shows the connection, the active OpMode, battery voltage and loop time, and has a
Stop button that is always available.

## Widgets

| Widget | Shows |
|---|---|
| Field | A top-down field. Add any pose or path as a layer. |
| Graph | Numbers and booleans over the last 5 to 120 seconds. |
| Values | Every logged value, grouped by key, with search. |
| Tunables | Editors for tunables, and buttons. |
| Telemetry | The text the OpMode sends to the Driver Station. |
| OpModes | Select an OpMode, then Init, Start and Stop. |
| Logs | Recorded log files, to download. |

### Field

Use **Add layer**, or drag a pose from Values onto the field. Each layer has a style:

| Style | Drawn as |
|---|---|
| Robot | A filled robot outline with a heading line |
| Ghost | A dashed outline, for a second pose source such as vision or a target |
| Arrow | A heading arrow only |
| Path | A line, for keys logged with `logPoses2d` |

Tick **Trail** on a layer to draw where it has been. **View** sets the rotation, robot size,
trail length, and whether the cursor position reads in Pedro or field-centre coordinates.

### Graph

Use **Add**, or drag a value from Values onto the graph. Up to eight series share one axis.
Hover for values at a point in time. **Pause** freezes the graph without stopping the data.

## Layouts

The page is split into panes, and each pane holds widgets as tabs.

| To | Do this |
|---|---|
| Add a widget | Click **+** on a pane |
| Move a tab to another pane | Drag it onto that pane's tab strip or middle |
| Split a pane | Drag a tab onto the edge of a pane |
| Resize | Drag the gap between panes |
| Close a widget | Click the **×** on its tab |

Layouts are saved on the robot as you change them, so every laptop sees the same ones. Use the
**Layout** menu to switch between them and **Manage** to save a copy under a new name, export
layouts to a file, import them, or restore the built-in ones.
