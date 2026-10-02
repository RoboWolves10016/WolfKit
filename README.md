# WolfKit

Logging, live telemetry and tuning for FTC robots, with its own dashboard, WolfDash.

```java
WolfKit.log("Shooter/VelocityRPM", rpm);
WolfKit.logPose2d("Drive/Robot", x, y, heading);
double kP = WolfKit.tunable("Shooter/kP", 0.002);
```

Every value logged is shown live in WolfDash, a page served by the robot, and recorded to a
`.wpilog` file per OpMode run that opens in AdvantageScope.

```groovy
implementation 'io.github.robowolves10016:wolfkit-core:0.2.0'
```

Documentation: <https://robowolves10016.github.io/WolfKit/>

## Modules

| Module | Contents |
|---|---|
| `engine` (`wolfkit-engine`) | Logging, the live stream, log files and the dashboard's HTTP API. Plain Java. |
| `core` (`wolfkit-core`) | What robot projects depend on: `engine`, FTC SDK integration and the WolfDash page. |
| `pedro` (`wolfkit-pedro`) | Optional Pedro Pathing 3 support. |
| `sim` | Desktop simulator for developing WolfDash. Not published. |

## Building

```
./gradlew build            # compile and run the compatibility checks
./gradlew :sim:run         # run the simulator at http://localhost:8080/dash/
```

Java is formatted with google-java-format (AOSP style) and the dashboard with Prettier, both
at 100 columns.

Releases are published to Maven Central by a GitHub Actions workflow. See
[RELEASING.md](RELEASING.md).

## Licence

MIT. See [LICENSE](LICENSE).
