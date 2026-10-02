---
title: Pedro Pathing
nav_order: 8
---

# Pedro Pathing

The optional `pedro` module logs Pedro Pathing 3 objects directly. Pedro measures from a
corner of the field; the module converts to the field-centre coordinates WolfKit uses.

```groovy
implementation 'io.github.robowolves10016:wolfkit-pedro:0.2.0'
```

```java
WolfKitPedro.log("Drive/Robot", follower.getPose());
WolfKitPedro.log("Drive/Path", path);   // once, when the path is built
```

With the module installed, `@Logged` fields can hold a Pedro `Pose` or `Path`:

```java
@Logged private Pose targetPose;
```

On Pedro Pathing 2, whose pose class differs, use the core method with the pose's numbers:

```java
WolfKit.logPedroPose("Drive/Robot", pose.getX(), pose.getY(), pose.getHeading());
```

To read the cursor position on WolfDash's field in Pedro coordinates, open **View** on the
Field widget and choose Pedro coordinates. The view rotation is set in the same place.
