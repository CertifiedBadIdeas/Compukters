# Rocket ascent experiment

Surface ascent for a primitive rocket with one **Creative Vector Thruster**. This first experiment ramps to
20 m/s upward, keeps local +Y upright and ends when Cosmonautics reports Deep Space. It does not perform
orbital insertion: a vertical trajectory can fall back even after crossing into space.

## Build the test rocket

1. Use the 1.21.1 addon development stand with Sable, Propulsion Simulated and Cosmonautics.
   Choose a planet dimension that permits space entry; the program reads its configured transition height.
2. Assemble a compact, symmetrical vertical construction with a computer and a creative vector engine facing
   **local +Y**. Put the engine directly below the center of mass, with a useful lever arm and clear exhaust.
   Keep the computer, cables and engine on the same construction. Leave roll around +Y uncontrolled.
3. Bind the engine to the computer and name it `ascent_engine` using the peripheral configurator.
   Remove redstone input and other engine controllers. Releasing a lease restores ordinary engine control,
   so external inputs can restart thrust after this program ends.
4. Calibrate the engine's saved thrust setting for approximately level hover in the launch world. `HOVER_KN = 0`
   uses that setting; an explicit positive value overrides it. Do not assume Sable's kpg mass is kilograms.
   Start with a light test construction and verify that small tilt corrections point the rocket back upright.
5. Copy this directory as an IDE project, **Resolve Dependencies**, then build and run it on the rocket's computer.
   Keep the rocket and computer loaded while testing. Run manually; do not put it in an automatic startup script.

## Controller and limits

The velocity target grows at 0.5 m/s² to `ASCENT_SPEED`. The program uses Sable's world-space linear velocity
and real angular velocity, transformed into local axes with `Rotation3d.inverse()`. Upright error comes from
`Rotation3d.fromTo`. Angular velocity is not reconstructed from Euler-angle differences.

A bounded PI loop adjusts the calibrated hover thrust. Its integral compensates for changing gravity;
9.81 is a tunable reference scale for the correction, not a gravity observation from the host. Commands stay
between zero and twice the hover setting, or the engine's smaller configured maximum. Integration stops when
saturated unless the error pulls the command back into range. Nozzle commands are limited to ±0.5; these are
normalized engine controls, not angles. Feedback signs assume the engine is below COM, not merely below the computer.
The mount API verifies direction and construction identity; check the center-of-mass placement yourself.

Preflight checks the planet, mount, initial physics and thrust setting while throttle is zero. ASCENT rejects
paused physics, construction replacement, an unexpected dimension, more than two ticks between status and physics,
more than ten ticks between feedback updates, nonfinite controller values or tilt above 60°. The program then exits
through cleanup. Fault cutoff is a failed experiment, not an automatic landing or recovery maneuver.

At zero thrust this one engine has no attitude authority. Startup, nozzle smoothing, atmosphere, exhaust obstruction,
physics configuration and the actual moment of inertia affect stability. The ideal numerical tests do not model those.
Tune the speed ramp and gains only after observing a low-altitude test. Above the current target by more than 1 m/s, commanded thrust immediately drops to zero.
A speed target is regulated, not a hard guarantee that actual speed never overshoots it. In zero gravity the engine
cannot brake upward motion; cutting thrust leaves the existing velocity.

## Deep Space handoff

Status is read before surface feedback. On observing Deep Space, the program commands zero throttle, prints COMPLETE
and releases the engine. It does not reuse the surface +Y target in the new coordinate system or automatically start
a prograde burn. Inspect `space-telemetry` afterward; Deep Space membership alone does not prove a bound orbit.

Sable block transfers preserve peripheral network membership, and computer removal starts asynchronous hibernation.
That is not evidence of uninterrupted execution: transfer can suspend the process, invalidate its handle, release
engine control or restore a previously saved command before this loop observes the new dimension. A stale feedback
tick gap stops the example. If it terminates or fails to resume, check the engine and stop it manually; do not infer
that COMPLETE or a cleanup command executed. Handle errors during cleanup are reported and process exit releases leases.
There is no cross-dimension thrust-cutoff guarantee in this example.

## Verification

Automated checks compile the full project against the real three addon bundles and execute the shipped controller
in the pinned Guest VM. Scenarios cover hover, speed feedback, saturation and anti-windup, torque/damping signs,
tiny rotations, invalid inputs, handoff decisions and an ideal vertical plant changing from launch gravity to zero.
They prove numerical behavior, not a successful Minecraft launch.

Manual dev-world check (pending): start upright below the transition height with no competing controls; observe the
velocity target ramp and bounded thrust, then the actual surface-to-space transfer. Record whether the program resumes,
prints COMPLETE, and whether thrust is zero. Also test interruption and a deliberately tilted launch at low altitude.
Only after this handoff is understood should orbital acceleration be added.
