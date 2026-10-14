# Space telemetry

Read-only Guest Kotlin example for Compukters: Cosmonautics on Minecraft 1.21.1.
Open this directory in the IDE, Resolve Dependencies against a server with the addon, then compile/deploy.

On a surface world it reports the planet transition altitude. On a loaded construction in Deep Space it reports
instance/frame/tick identity, orbital radius and speed, radial and transverse speed, circular speed at that radius,
and matching planet apsis distances. Positive radial speed means motion away from the central body. Circular speed
is a reference for a circular two-body orbit, not a throttle command or a burn duration. Transverse speed alone does
not establish a circular orbit: radial motion and the complete trajectory also matter.

All velocity values describe the **Deep Space instance orbit**, rather than the construction's local Sable solver.
`Bound` only describes the orbital elements. It does not prove clearance of the planet or its transition region.
Displayed planet-relative distances subtract `planet.radius`; they are not terrain height or distance from a cube's
surface. Frame mismatches omit these distances. Missing data produces an explicit message.

Run this program before and after a short manual burn to observe the orbital response. It does not acquire an engine
or change controls. For the pinned upstream coordinate and transfer contracts, see
[orbital navigation](../../docs/developers/orbital-navigation.md). Orbital insertion and return to a pad remain subsequent
mission stages in [#795](https://github.com/CertifiedBadIdeas/Compukters/issues/795).
