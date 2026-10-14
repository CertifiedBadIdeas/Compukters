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
   computer. Default settings climb 40 m above that reference and hold for five seconds. Keep at least 10 m between
   the planned top height and the planet's Deep Space transition. Start lower by setting `CLIMB_HEIGHT = 5.0`.
5. Observe phase, relative height, measured/target vertical speed, thrust and horizontal drift. Horizontal feedback
   requests a small tilt to slow drift and return to the recorded starting position, within a 2 m corridor.
   Roll around +Y is uncontrolled. Ctrl+C exits and releases program ownership.

## Flight phases

- **ASCENT:** speed target decreases with remaining height, capped at 4 m/s. Arrival needs one second within
  0.3 m of the target and 0.3 m/s vertical speed, within 0.2 m horizontally and 0.1 m/s horizontal pose motion.
- **HOLD:** hold height and horizontal position for five consecutive settled seconds; movement resets the hold timer.
- **DESCENT:** target is proportional to remaining height over the last four metres, capped at 2 m/s downward
  and tapering to 0.25 m/s for the final half metre. PI feedback raises thrust to brake a fall rather than cutting it.
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

The horizontal outer PD loop uses world-space displacement and pose motion, with proportional gain 0.02 s⁻²
and velocity gain 0.2 s⁻¹. Desired horizontal acceleration is capped at 0.5 m/s²; mapping against nominal
9.81 m/s² gives a target tilt under 3 degrees. The inner shortest-arc attitude PD uses real angular velocity and
stronger damping (1.8 rather than the original 0.6 nozzle units per rad/s) to reduce coupled oscillation. Vertical
thrust compensation uses the actual tilt, rather than the error from the desired tilt. PI commands have
saturation and anti-windup. Gains remain experimental and depend on engine lever arm, inertia and startup/nozzle
response; the numerical tests are not automatic tuning for arbitrary constructions.
Limits include 30-degree tilt, 2 rad/s angular speed, 2 m/s horizontal speed, -0.5 m to climb+10 m vertical envelope,
up to ten ticks between observations and a 120-second flight timeout. Paused physics, changed dimension/construction,
invalid feedback or excessive drift abort and release the engine. An abort is not an automatic landing maneuver.

Closing the handle removes program overrides and returns the engine to ordinary upstream control. External redstone
can restart thrust. Propulsion startup, shutdown and nozzle smoothing affect actual motion; zero throttle alone is
not a guarantee of immediate zero physical thrust. A single engine has no attitude authority at zero thrust.

## Program organization

`src/main.kt` owns the flight loop, observation timing and the engine handle lifetime. `FlightHardware.kt` adapts
preflight, command delivery and cleanup to the addon APIs; `FlightReporting.kt` owns status output.
Pure logic in `src/control` has no peripheral calls: `FlightModels.kt` defines state and outputs,
`FlightPhases.kt` owns mission transitions, `VerticalCommand.kt` owns speed/thrust PI, and `AttitudeCommand.kt`
owns tilt steering. `HorizontalCommand.kt` maps pad error and pose motion to a target up direction. `FlightControl.kt` validates the feedback and combines their commands; `FlightMath.kt`
provides finite bounded command arithmetic. VM conformance compiles this same control directory.

The wider orbital mission is tracked in [#795](https://github.com/CertifiedBadIdeas/Compukters/issues/795).
Horizontal return is implemented for the local 2 m corridor; orbital insertion, revolution counting and deorbit guidance remain subsequent stages.

## Verification

`testKotlinRocketLandingVmConformance` runs the shipped controller in the pinned VM. It checks descent braking,
near-pad approach, immediate touchdown cutoff with solver/pose motion disagreement, saturation/anti-windup, attitude correction,
invalid observations and a complete launch/hold/descent/contact cycle in an independent ideal vertical plant, including 10% hover-calibration error.
It also checks horizontal guidance and descent/hold gates, then runs four 3D coupled flights with a small lateral
velocity impulse, different headings and isotropic inertia/mass values of 6, 12, 24 and 48 m². Each off-centre
nozzle force drives both translation and torque; the model integrates at 20 Hz and includes the pinned upstream
20% nozzle tween and 0.001 snap. These models assume a 2 m lever arm and a flat inelastic ground plane, with no engine startup,
aerodynamic drag, asymmetric inertia or detailed landing geometry.
`:compiler-k2:compileRocketLandingExample` compiles the full program against actual Sable/Propulsion/Cosmonautics bundles.
These checks do not model the actual engine startup/nozzle envelope or establish Minecraft landing stability.

The preceding upright vertical cycle was confirmed working manually. Horizontal guidance needs fresh manual acceptance:
first fly the 5 m cycle with no other inputs, then the 40 m cycle. Apply only a small lateral disturbance after lift-off
and check that drift decreases, HOLD waits for return, and final descent/COMPLETE occur over the starting position. Record hover thrust,
engine placement, drift, maximum height, descent speed just before contact, COMPLETE and physical engine shutdown.
Check that a wrong pad/start while airborne is rejected or visibly identified; there is no terrain sensor to detect
stationary hovering automatically. Re-test with a deliberately small tilt only after the upright flight succeeds.
