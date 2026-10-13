# Orbital prograde pointing

Experimental Guest Kotlin project for Compukters 0.6, Cosmonautics 26.08.307, Sable 2.0.6 and Propulsion 1.1.5.
This points a construction's local +Y axis along its Deep Space instance's orbital velocity. It is an attitude demo,
not a launch/orbit-insertion autopilot. It requires `cosmonautics`, `sable` and `propulsion` addon bundles.

## Setup

1. Use a loaded construction already inside a valid Deep Space instance. Place the computer and one creative vector
   thruster on that construction. Mount the engine to produce local +Y thrust, directly below the center of mass,
   with a useful lever arm and no lateral offset. The mount API verifies direction and construction identity;
   you must verify the COM placement yourself. Keep the nozzle/exhaust clear and avoid other engine/redstone inputs.
2. Bind the engine to the computer's peripheral network and name it `prograde_engine` with the configurator.
3. Open this project in the IDE, **Resolve Dependencies**, then build and deploy. Set `THRUST_KN` to a low positive
   value within the engine's maximum; the initial 0.5 kN is a starting value, not a tuned setting for every ship.
   Tune the P/D gains in `PointingCommand.kt` for your inertia, lever arm and upstream nozzle smoothing.
4. Start the program and observe the reported pointing error in radians. Ctrl+C exits and closes the engine handle.
   Closing releases digital commands to normal upstream/redstone control; it does not guarantee zero physical thrust
   if other inputs remain present.

Only two pointing axes are actuated. Roll around local +Y remains uncontrolled. One vectored engine generates
translation as well as torque. Its mount must be below COM: a mount above COM reverses feedback signs.
The controller uses shortest-arc `Rotation3d.fromTo` and real Sable angular velocity, not Euler differences.
At an exact opposite direction, the chosen turn axis is deterministic but inherently ambiguous nearby.
Thrust is constant while controlling; an orbital burn optimizer, opposing RCS pair and roll control are separate work.

## Coordinate and timing contract

`Space.guidance()` copies prograde, radial-out and orbit-normal from one instance PV observation and returns its
Minecraft dimension, instance ID, orbital frame name, server tick and instance-local universe tick. In the pinned
Cosmonautics implementation, transferred Sable world velocity components are added directly to the current orbital
frame; no extra rotation is inserted by this demo. These are unit **directions**, not Sable world positions.
`prograde` and `radialOut` need not be perpendicular on an eccentric orbit. `orbitNormal` is their normalized cross.

Sable's separate snapshot is not atomic with guidance. The program accepts at most two server ticks of skew,
stops on paused physics, a stale (>10 ticks) attitude, a different construction/dimension/instance, a changed orbital
frame or unavailable guidance. It does not promise continuous control through dimension transfer or unloading.
A zero velocity or nearly radial trajectory has no complete guidance triad and produces an explicit error.

Cosmonautics moves local solver motion into the instance orbit at the instance boundary. Inside the box an engine
first moves the construction locally. Orbital velocity therefore is not the same as Sable solver velocity, and a
working pointing controller does not prove a successful orbital burn.

## Verification and pending world scenario

`testKotlinOrbitalPointingVmConformance` executes the shipped controller in the pinned VM: exact alignment,
90°/180° errors, tiny direction components, saturation, damping, rotation covariance, invalid inputs and convergence
in an ideal two-axis torque plant. This plant does not model Propulsion's quantization/startup/nozzle smoothing.
`:compiler-k2:compileOrbitalProgradeExample` compiles the complete project against the three actual built addon bundles.

Pending manual verification: in a dimension-capable world, compare guidance with Sputnik, test known world-axis
thrust directions, start with a modest tilt and low thrust, and observe decreasing error/angular rate. Then check
Ctrl+C release and explicit failures for paused physics/frame changes. Record engine placement, mass/inertia,
settings and results. Compilation and numerical tests do not establish in-world flight stability.
