# Controlled vertical launch and landing

Experimental Guest Kotlin project for Minecraft 1.21.1 with Compukters, Sable, Propulsion Simulated and
Cosmonautics. A single +Y creative vector thruster lifts a rocket, holds height, descends and releases
control after a sustained near-pad stop. This is a local flight experiment, not orbital flight or terrain navigation.

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
5. Observe phase, relative height, measured/target vertical speed, thrust and horizontal drift. The flight must stay
   over the original pad: this version stabilizes upright attitude and rejects drift over 2 m, but does not steer
   back to the pad. Roll around +Y is uncontrolled. Ctrl+C exits and releases program ownership.

## Flight phases

- **ASCENT:** speed target decreases with remaining height, capped at 4 m/s. Arrival needs one second within
  0.3 m of the target and 0.3 m/s vertical speed.
- **HOLD:** hold height for five consecutive settled seconds; movement resets the hold timer.
- **DESCENT:** target is proportional to remaining height over the last four metres, capped at 2 m/s downward
  and tapering to 0.25 m/s for the final half metre. PI feedback raises thrust to brake a fall rather than cutting it.
- **COMPLETE:** two consecutive seconds within 0.25 m of the pad reference with vertical/horizontal pose-displacement speeds
  at most 0.05 m/s and angular speed at most 0.1 rad/s. Then command zero throttle and close the engine handle.

Pose-displacement speed is measured between consecutive position snapshots at their server ticks. The controller
continues to use the solver linear velocity for thrust feedback, but does not require that instantaneous velocity
to become zero for completion: it can differ from observed pose motion near contact. The landing diagnostic reports
pose speed, angular speed and the settling timer; flight phases print their enum names.

There is no contact sensor: COMPLETE means sustained stationary telemetry near the reference, not proof of ground
contact. Downward motion remains requested during final approach; stationarity far above the pad does not satisfy
completion. Stationary hovering within the 0.25 m reference window cannot be distinguished from contact. An obstacle
at the same reference height cannot be distinguished from the chosen pad. This experiment requires a known, clear pad throughout; arbitrary terrain and landing at other coordinates need further sensing/control.

The feedback uses real angular velocity and shortest-arc upright rotation. PI commands have saturation and anti-windup.
Limits include 30-degree tilt, 2 rad/s angular speed, 2 m/s horizontal speed, -0.5 m to climb+10 m vertical envelope,
up to ten ticks between observations and a 120-second flight timeout. Paused physics, changed dimension/construction,
invalid feedback or excessive drift abort and release the engine. An abort is not an automatic landing maneuver.

Closing the handle removes program overrides and returns the engine to ordinary upstream control. External redstone
can restart thrust. Propulsion startup, shutdown and nozzle smoothing affect actual motion; zero throttle alone is
not a guarantee of immediate zero physical thrust. A single engine has no attitude authority at zero thrust.

## Verification

`testKotlinRocketLandingVmConformance` runs the shipped controller in the pinned VM. It checks descent braking,
near-pad approach, touchdown debounce with solver/pose motion disagreement, saturation/anti-windup, attitude correction,
invalid observations and a complete launch/hold/descent/contact cycle in an independent ideal vertical plant, including 10% hover-calibration error.
`:compiler-k2:compileRocketLandingExample` compiles the full program against actual Sable/Propulsion/Cosmonautics bundles.
These checks do not model the actual engine startup/nozzle envelope or establish Minecraft landing stability.

Pending manual acceptance: first fly the 5 m cycle with no other inputs, then the 40 m cycle. Record hover thrust,
engine placement, drift, maximum height, descent speed just before contact, COMPLETE and physical engine shutdown.
Check that a wrong pad/start while airborne is rejected or visibly identified; there is no terrain sensor to detect
stationary hovering automatically. Re-test with a deliberately small tilt only after the upright flight succeeds.
