# Liquid-fuel boiler SCADA

A separate Guest project for a Create boiler heated by liquid Blaze Burners from **Create: Crafts & Additions**.
START runs the water and fuel pumps from a **Create: Connected Kinetic Battery**. After sustained engine-only RPM,
water and active boiler readings, the installation switches to steam power and charges the battery.
STOP disables the feed pump and runs a separate drain pump from battery power, returning recoverable liquid to
the reservoir. Once **every configured burner** is empty for eight consecutive samples, pumps stop and the panel
waits for already-burning fuel to cool.

## Devices and screen

Use Minecraft 1.21.1 with Create 6.0.10, Crafts & Additions 1.7.2, Create: Connected 1.3.3,
Compukters and its **Create** and **Crafts & Additions** integration addons. The all-addon development client
already selects these versions. The program selects `create` and `createaddition` in `compukter.toml`.

Assemble a flat 3x3 display, name it `boiler_scada`, and bind these devices to the computer's peripheral network:

| Name | Device |
| --- | --- |
| `boiler` | The formed Fluid Tank boiler with an attached Steam Engine |
| `boiler_engine_speed` | A Speedometer on the engine branch **before its isolation clutch** |
| `boiler_control` | One Redstone Port controlling the actuators below |
| `boiler_burner_1` through `boiler_burner_9` | Nine liquid Blaze Burners, each given a straw |

The default stand has a 3x3 burner floor beneath a 3x3x4 tank boiler. For a smaller installation, edit `configuredBurners()` in
`src/main.kt` to list its burners explicitly; one through nine are supported. Include every burner sharing the fuel
manifold. Boiler segments share one configured name; burners remain individual fuel stores.
The program selects HIGH density, producing a 384x384 panel on a 3x3 screen.

## Mechanical and fluid connections

```text
Steam Engine -> engine-only Speedometer -> [steam clutch] ---+
                                                           +-> pump bus -> water pump -> boiler
Kinetic Battery <----------------------- [battery clutch] --+          |
                                                                       +-> [feed clutch] -> feed pump
                                                                       +-> [drain clutch] -> drain pump

Fuel reservoir -> feed pump -> feed manifold -> straw burners
Fuel reservoir <- drain pump <- return manifold <- straw burners
```

The common bus must have compatible rotation direction and speed from either source. Gear the engine branch to
match the battery's configured discharge RPM and choose pump gearing that supplies enough water for the desired
boiler level. Give the battery enough charge for startup **and** shutdown. Leave room in the reservoir for all stored
burner fuel. Use two separate fuel paths: a feed line from the reservoir to the burners and a return line from the burners
back to the reservoir. Orient each pump's flow arrow accordingly; changing shaft rotation does not reverse fluid
flow. Connect the feed and return manifolds to the burners through separate pipe connections, and keep the lines
separate between the burners and reservoir so drainage cannot bypass the burners. Do not put the two pumps in
series or use a Gearshift to select fuel flow. Isolate other fuel consumers and use separate water plumbing.
A clutch at each power branch isolates both sources during switching.

Port sides are relative to **the port's front**, independently of the computer orientation:

| Port output | Connection | Signal 15 | Signal 0 |
| --- | --- | --- | --- |
| FRONT | Kinetic Battery, directly | Discharge | Charge if mechanically connected |
| RIGHT | Battery branch clutch **through a redstone torch inverter** | Connect battery | Isolate battery |
| LEFT | Steam branch clutch **through a redstone torch inverter** | Connect engine | Isolate engine |
| TOP | Feed pump clutch **through a redstone torch inverter** | Feed reservoir → burners | Stop feed pump |
| BOTTOM | Drain pump clutch **through a redstone torch inverter** | Drain burners → reservoir | Stop drain pump |
| BACK | Unused | — | — |

The clutch lines are inverted because Create clutches disconnect when powered. With all port outputs zero, both
power branches and both fuel pumps must be isolated. Keep the five redstone circuits separate; direct short runs or
independent Redstone Links can carry them. Test the polarity before attaching fluid supplies.
For steam operation, FRONT is zero and both source clutches are connected: the engine drives the pumps and charges
the battery. Before discharge can be enabled again, the engine branch is isolated. Both fuel clutches disconnect before
switching pumps or power sources; the feed and drain pumps are never enabled together. Two-tick waits allow
redstone torch and mechanical updates to settle between stages.

## Controls and observations

- **START** is available in IDLE with valid telemetry. It enables battery power and fuel feed. Handover requires
  eight consecutive completed samples with engine-only absolute RPM at least 8, positive water supply/water level,
  active heat and effective boiler level. Rotation from the battery must not reach the engine Speedometer.
- **STOP / DRAIN** stops the feed pump and uses the battery to run the separate drain pump. A single remaining mB
  keeps drainage active. Eight consecutive all-empty samples enter COOLING; burning time and active heat must then
  reach zero before IDLE.
- **RESET** clears a latched fault only when valid readings confirm no stored liquid, no burning time and no active
  heat. It does not start the installation. STOP can recover fuel after a fault while telemetry is available.

The screen shows water input in mB/t, water/heat/effective levels, independent engine RPM, total and per-burner
recoverable fuel, the maximum remaining burn time, a 96-sample boiler-level graph, control phase and faults.
Battery/pump indicators describe applied **commands**; fuel and heat readings come from the actual devices.
The program does not read battery charge or measure fluid flow directly. Low charge, a blocked return line or an
undersized self-powered drive must be diagnosed from RPM/fuel progress and timeout indicators.

Loss of self-power for three samples requests battery-assisted drainage and latches an alarm. Startup and drainage
have limits of 240 completed samples each; unavailable/stale telemetry or failed actuator writes release outputs and
latch a fault. Samples sleep at least five server ticks apart; peripheral I/O can lengthen these intervals, so the
limits are not precise wall-clock deadlines. A separate cooperative watchdog rejects a sampler that stops advancing.
Recovery does not replay clicks or automatically start. Data reads started before actuator switching are discarded.

Fuel already converted into burn time cannot be extracted. Empty burners can remain hot until that finite portion
burns out; SCADA displays this as COOLING. Use STOP and wait for IDLE before stopping the program. Abrupt program
termination releases the transient Redstone Port outputs and isolates the pumps; it cannot execute a complete fuel
recovery sequence. The last display image remains after the program exits.

## Install and verify

Copy this directory to a new IDE project, retaining `compukter.toml` and both files under `src/`. Attach the IDE to
the stand's computer so both selected addon bundles are available, compile and run the project. Startup itself sends
no actuator command. `runDashboard(iterations)` is also available for a bounded program run.

From the Compukters root, build SDK and addon bundles **sequentially**, because addon builds include parent outputs:

```sh
./gradlew-sandbox-dev-parallel-summary stageAddonDevelopmentDependencies
addons/create/gradlew-sandbox-dev-parallel-summary assembleCompuktersAddon
addons/createaddition/gradlew-sandbox-dev-parallel-summary check
./gradlew-sandbox-dev-parallel-summary :compiler-k2:compileBoilerScadaExample \
  -PcompuktersCreateGuestApiBundle="$PWD/addons/create/build/compukters-addon/compukters-create.cagb" \
  -PcompuktersCreateadditionGuestApiBundle="$PWD/addons/createaddition/build/compukters-addon/compukters-createaddition.cagb"
```

Controller behavior is exercised by `testKotlinBoilerControlVmConformance`, compiling the exact Guest controller
and running all seven lifecycle/interlock scenarios in the pinned VM with native value records.
`DiagnosticQueryTest` also analyzes the shipped controller through the native IDE frontend, with platform sources
attached and without them; compiler-only checks do not establish that the editor accepts the same source.
The Crafts & Additions real GameTest uses the actual burner, fuel capability, configurator, Guest compiler and VM;
it verifies one-mB observation/recovery, device replacement and network loss.

For the complete client check: start with empty burners and a charged, isolated battery. Verify IDLE and zero outputs,
press START, observe battery-driven water/fuel pumping, then RUNNING with the discharge signal removed and battery
charging. Check that only the feed pump is running. Press STOP, observe the feed pump stopping before the drain
pump starts, fuel returning through its separate line and falling per-burner amounts, then COOLING with both fuel
pumps stopped and every amount zero, then IDLE after the displayed burning time reaches zero. Block the return line and repeat STOP to check DRAIN TIMEOUT;
remove a named device during operation to check a latched fault and absent automatic restart on recovery.
A successful compilation or client launch alone does not establish this complete physical/visual scenario.
