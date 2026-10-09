# Release transfer tools

These Python standard-library tools transfer artifacts produced by the existing
Gradle release gates. ZIP inspection and transfer tests do not establish release
readiness. Run `verifyLocalFull` on the exact candidate, then
`buildReleaseUniversalJar` on its clean exact tag with the correct published
Runtime bundles before preparing a release directory.

`release.py prepare --tag vX.Y.Z --output build/release` requires the version tag
at HEAD, a clean pinned checkout, a released changelog section, both official-name
JARs and bundled tooling plus Linux/Windows natives. The output directory must be
new. It records and copies only the two supported NeoForge targets.

`release.py verify --directory build/release` validates the staged files against
the inventory and checksums. `release.py modrinth --directory build/release`
publishes with `MODRINTH_TOKEN` from the environment. It checks all preexisting
versions for conflicts before any upload and resumes missing targets on retry.
There is no automatic POST retry after an ambiguous transport failure: rerun the
command to reconcile remote versions first.

## Inventory schema 1

`release.json` has `schema`, `repository`, `modrinth_project`, `tag`, `version`,
`revision`, `vm_revision`, `components`, and `artifacts`. Revisions identify the
parent and pinned VM commits. `components.tooling` records `bundle_sha256`
(the canonical tooling identity), `manifest_sha256`, `carrier_sha256`, and
`delivery: bundled`.

Each artifact records its basename, Minecraft version, loader, Modrinth version
number, byte length, SHA-256/SHA-512 digests, and `distribution: bundled`.
`release-notes.md` contains only the current changelog section; `checksums.sha256`
covers the inventory, notes, and both JARs. Schema 1 deliberately admits only the
current autonomous composition. A future downloaded composition must add an
explicit contract for pinned component locations, lengths, hashes, and offline
behavior instead of inferring delivery from absent JAR entries or using `latest`.

`release.py github --directory build/release` uses the authenticated `gh` CLI to
create or resume a draft, verify the remote tag and every existing asset, upload
missing files, and publish only the complete release. It never uses `--clobber`.

Run tests with `python3 -m unittest discover -s tools/release -v`.
