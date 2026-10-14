# Controlled vertical launch and landing

Experimental Guest Kotlin project for Minecraft 1.21.1 with Compukters, Sable, Propulsion Simulated and
Cosmonautics. A single +Y creative vector thruster lifts a rocket, holds height, descends and releases
control as soon as a near-pad stop is observed. This is a local flight experiment, not orbital flight or terrain navigation.

## Setup

1. Start on a flat, clear pad in a planet dimension. Keep the rocket loaded. Use a symmetrical construction with
   the creative vector thruster below its center of mass, producing local +Y thrust, with clear exhaust.
   Bind it to the computer's network as `ascent_engine`. Keep the computer and engine on that same construction.
2. Remove redstone and other engine controllers. Calibrate the engine's saved thrust for level hover in this world;
   alternatively set `HOVER_KN` in `src/main.kt` explicitly. At least 20% available thrust reserve is required.
   Sable mass uses kpg: this example does not assume kpg is kg or calculate hover thrust from mass.
3. Place the assembled rocket upright and at rest **on the intended landing pad**, with no obstacles above it.
   The first `Physics.snapshot().position.y` is recorded as the known pad reference height, including the rocket's
   geometry. It is not the surface block Y, nozzle height or a terrain measurement. Do not start while hovering.
4. Open this project in the IDE, **Resolve Dependencies**, then **Build** and deploy. Run manually on the rocket's
   computer. Default settings climb 200 m above that reference and hold for five seconds. Configure `ASCENT_SPEED`
   and `DESCENT_SPEED` in `src/main.kt` (defaults 20 and 15 m/s). Keep at least 10 m between
   the planned top height and the planet's Deep Space transition. Start lower by setting `CLIMB_HEIGHT = 5.0`.
5. Observe phase, relative height, measured/target vertical speed, thrust and horizontal drift. Horizontal feedback
   requests a small tilt to slow drift and return to the recorded starting position, within a 6 m corridor.
   Roll around +Y is uncontrolled. Ctrl+C exits and releases program ownership.

## Flight phases

- **ASCENT:** speed target decreases with remaining height, capped at the configured ascent speed (20 m/s by default). Arrival needs one second within
  0.3 m of the target and 0.3 m/s vertical speed, within 0.2 m horizontally and 0.1 m/s horizontal pose motion.
- **HOLD:** hold height and horizontal position for five consecutive settled seconds; movement resets the hold timer.
- **DESCENT:** cruise target is capped at 15 m/s, decreasing early according to remaining braking distance.
  Near the pad, the proportional final-approach limit still tapers to 0.25 m/s for the final half metre. PI feedback raises thrust to brake a fall rather than cutting it.
  While horizontal displacement exceeds 0.2 m or horizontal pose speed exceeds 0.1 m/s, approach/hold a 2 m
  reference clearance instead of continuing the final descent. Return guidance remains active throughout.
- **COMPLETE:** the first observation within 0.25 m vertically and 0.2 m horizontally of the pad reference with vertical/horizontal pose-displacement speeds
  at most 0.05 m/s and angular speed at most 0.1 rad/s. Immediately command zero throttle and close the engine handle.

Pose-displacement speed is measured between consecutive position snapshots at their server ticks. The controller
continues to use the solver linear velocity for thrust feedback, but does not require that instantaneous velocity
to become zero for completion: it can differ from observed pose motion near contact. The landing diagnostic reports
pose speed and angular speed; flight phases print their enum names.

There is no contact sensor: COMPLETE means near-stationary telemetry near the reference, not proof of ground
contact. Downward motion remains requested during final approach; stationarity far above the pad does not satisfy
completion. Stationary hovering within the 0.25 m reference window cannot be distinguished from contact. An obstacle
at the same reference height cannot be distinguished from the chosen pad. This experiment requires a known, clear pad throughout; arbitrary terrain and landing at other coordinates need further sensing/control.

The vertical planner solves `distance = speed * responseSeconds + speed² / (2 * brakingAcceleration)`.
Defaults use 1 m/s² and a one-second response allowance. This accounts for planned stopping distance; actual
thrust, calibration and engine lag still determine motion, so cruise settings are targets rather than guaranteed
instantaneous speed bounds. The last half metre remains a 0.25 m/s approach, with immediate near-stationary cutoff.

The horizontal outer PD loop uses world-space displacement and pose motion, with proportional gain 0.02 s⁻²
and velocity gain 0.2 s⁻¹. Desired horizontal acceleration is capped at 0.5 m/s²; mapping against nominal
9.81 m/s² gives a target tilt under 3 degrees. The inner shortest-arc attitude PD uses real angular velocity and
stronger damping (1.8 rather than the original 0.6 nozzle units per rad/s) to reduce coupled oscillation. Vertical
thrust compensation uses the actual tilt, rather than the error from the desired tilt. PI commands have
saturation and anti-windup. Gains remain experimental and depend on engine lever arm, inertia and startup/nozzle
response; the numerical tests are not automatic tuning for arbitrary constructions.
Abort diagnostics distinguish solver speed from observed pose speed and report the offending value. A 6 m
return corridor does not guarantee recovery from every speed below 2 m/s: braking distance and attitude response
also matter. Start disturbance trials around 0.15 m/s; 1 m/s descent impulses are covered by the numerical model,
but require a fresh in-world trial. This wider corridor permits braking displacement without relaxing the final
0.2 m centering requirement.

Limits include 30-degree tilt, 2 rad/s angular speed, 2 m/s horizontal speed, -0.5 m to climb+10 m vertical envelope,
up to ten ticks between observations, 40 m/s solver vertical speed and 45 m/s observed pose speed.
Climb settings cover 5–1000 m (always below the Deep Space transition); cruise settings allow 1–30 m/s upward
and 1–25 m/s downward. Timeout is `120 + 2 * height / ascentSpeed + 2 * height / descentSpeed + holdSeconds`
in seconds, including an allowance for acceleration, settling and local return. The default 200 m profile has about 172 s. Paused physics, changed dimension/construction,
invalid feedback or excessive drift abort and release the engine. An abort is not an automatic landing maneuver.

Closing the handle removes program overrides and returns the engine to ordinary upstream control. External redstone
can restart thrust. Propulsion startup, shutdown and nozzle smoothing affect actual motion; zero throttle alone is
not a guarantee of immediate zero physical thrust. A single engine has no attitude authority at zero thrust.

## Program organization

`src/main.kt` owns the flight loop, observation timing and the engine handle lifetime. `FlightHardware.kt` adapts
preflight, command delivery and cleanup to the addon APIs; `FlightReporting.kt` owns status output.
`FlightProfile.kt` owns cruise limits, braking-distance planning, configuration validation and timeout allowance.
Pure logic in `src/control` has no peripheral calls: `FlightModels.kt` defines state and outputs,
`FlightPhases.kt` owns mission transitions, `VerticalCommand.kt` owns speed/thrust PI, and `AttitudeCommand.kt`
owns tilt steering. `HorizontalCommand.kt` maps pad error and pose motion to a target up direction. `FlightControl.kt` validates the feedback and combines their commands; `FlightMath.kt`
provides finite bounded command arithmetic. VM conformance compiles this same control directory.

The wider orbital mission is tracked in [#795](https://github.com/CertifiedBadIdeas/Compukters/issues/795).
Horizontal return is implemented for the local 6 m corridor; orbital insertion, revolution counting and deorbit guidance remain subsequent stages.

## Verification

`testKotlinRocketLandingVmConformance` runs the shipped controller in the pinned VM. It checks descent braking,
near-pad approach, immediate touchdown cutoff with solver/pose motion disagreement, saturation/anti-windup, attitude correction,
invalid observations and a complete launch/hold/descent/contact cycle in an independent ideal vertical plant, including 10% hover-calibration error.
Vertical cycles cover 5, 40, 200 and 1000 m with 10% calibration error; 200 m must finish within 90 s in the ideal
plant, reaching over 10 m/s in both directions while touching down below 0.5 m/s.
It also checks horizontal guidance and descent/hold gates, then runs six 3D coupled flights with lateral
velocity impulses, different headings and isotropic inertia/mass values of 6, 12, 24 and 48 m². Each off-centre
nozzle force drives both translation and torque; the model integrates at 20 Hz and includes the pinned upstream
20% nozzle tween and 0.001 snap. Additional 40 m and 200 m cycles apply 1 m/s lateral impulses during descent
four metres below the top height and must return after drifting beyond 2 m without leaving the 6 m corridor. These models assume a 2 m lever arm and a flat inelastic ground plane, with no engine startup,
aerodynamic drag, asymmetric inertia or detailed landing geometry.
`:compiler-k2:compileRocketLandingExample` compiles the full program against actual Sable/Propulsion/Cosmonautics bundles.
These checks do not model the actual engine startup/nozzle envelope or establish Minecraft landing stability.

The preceding upright vertical cycle was confirmed working manually. Horizontal guidance needs fresh manual acceptance:
first fly the 5 m cycle with no other inputs, then 40 m and the 200 m profile. Apply only a small lateral disturbance after lift-off
and check that drift decreases, HOLD waits for return, and final descent/COMPLETE occur over the starting position. Record hover thrust,
engine placement, drift, maximum height, descent speed just before contact, COMPLETE and physical engine shutdown.
Check that a wrong pad/start while airborne is rejected or visibly identified; there is no terrain sensor to detect
stationary hovering automatically. Re-test with a deliberately small tilt only after the upright flight succeeds.
