# Release transfer tools

These Python standard-library tools transfer artifacts produced by the existing
Gradle release gates. They require Python 3.11 or newer for NeoForge TOML inspection.
ZIP inspection and transfer tests do not establish release
readiness. Run `verifyLocalFull` on the exact candidate, then
`buildReleaseUniversalJar` on its clean exact tag with the correct published
Runtime bundles before preparing a release directory.

`release.py prepare --tag vX.Y.Z --output build/release` requires the version tag
at HEAD, a clean pinned checkout, a released changelog section, both official-name
JARs and bundled tooling plus Linux/Windows natives. The output directory must be
new. Sources are the exact named JARs under `dist/<minecraft>/`, produced by
`./gradlew-sandbox-dev-parallel-summary -p addons/dev collectReleaseDistributionJars`.
Run `packageAddonDevelopmentDependencies` in the same clean tagged checkout as well.
It creates `build/distribution/compukters-addon-development-X.Y.Z.zip`.
The inventory records both NeoForge targets, the four selected first-party addons (Create, CreateAddition, Sable and Propulsion) and this SDK archive.

`release.py verify --directory build/release` validates the staged files against
the inventory and checksums. `release.py modrinth --directory build/release`
publishes with `MODRINTH_TOKEN` from the environment. It checks all preexisting
versions for conflicts before any upload and resumes missing targets on retry.
There is no automatic POST retry after an ambiguous transport failure: rerun the
command to reconcile remote versions first.

## Inventory schema 5

`release.json` has `schema`, `repository`, `modrinth_project`, `tag`, `version`,
`revision`, `vm_revision`, `components`, `artifacts`, `addons`, and `development`. Revisions identify the
parent and pinned VM commits. `components.tooling` records `bundle_sha256`
(the canonical tooling identity), `manifest_sha256`, `carrier_sha256`, and
`delivery: bundled`.
`components.runtime` records the actual selected Runtime `version`, native `abi`,
release `revision` and `delivery: bundled`, read from each JAR's
`META-INF/compukters/runtime.properties`. Both targets must carry the same release.
This component revision is independent of the source submodule `vm_revision`;
native compatibility is determined by ABI rather than package revision.

Each artifact records its basename, Minecraft version, loader, Modrinth version
number, byte length, SHA-256/SHA-512 digests, and `distribution: bundled`.
`release-notes.md` contains only the current changelog section; `checksums.sha256`
covers the inventory, notes, all six JARs and the SDK ZIP. Addons have independent `x.y` versions;
their entries record addon identity, target Compukters line, Minecraft version,
loader, filename, byte length and SHA-256/SHA-512. Their packaged mod identity,
Guest bundle and bounded Compukters dependency are verified before staging.
The `development` entry records the SDK version, archive filename, byte length and hashes.
The SDK ZIP contains a file Maven repository with the plugin marker, plugin, compiler tooling, platform bundle,
host contracts, both adapters and both development mod JARs, plus metadata, licenses and shared tag-only addon release tools.
GitHub receives all six JARs and the SDK; Modrinth receives the two base-mod artifacts,
and CurseForge receives both base-mod JARs and all four selected addon JARs. Cosmonautics
remains available in local development collections but is excluded from this release.
Schema 5 adds CreateAddition to the ordered addon set. Readers accept schema 4 with the
original three addons and SDK, schema 3 without development assets, schema 1 without addons
and schema 2 without a native component identity
for recovery of previously staged releases. All schemas deliberately admit only the
current autonomous composition. A future downloaded composition must add an
explicit contract for pinned component locations, lengths, hashes, and offline
behavior instead of inferring delivery from absent JAR entries or using `latest`.

`release.py github --directory build/release` uses the authenticated `gh` CLI to
create or resume a draft, verify the remote tag and every existing asset, upload
missing files, and publish only the complete release. It never uses `--clobber`.

## CurseForge

`curseforge.py publish --directory build/release` submits both base JARs to
project `1528063` and the four addon JARs to their reviewed projects using `CURSEFORGE_TOKEN`
(author upload API) and the authenticated
`gh` CLI (read contents, write deployments). Destination and loader mapping are
fixed in reviewed source. Schema 5 uploads six JARs; schemas 1–4 retain base-only CurseForge
publication so recovery never introduces new addon submissions. Each upload uses
markdown notes, release type `release` and exact `gameVersionNames` for its Minecraft
target plus NeoForge, Client and Server. The API names are preflighted before any intent.
Addon destinations are Create `1746054`, CreateAddition `1746058`, Sable `1746063` and Propulsion `1746068`.
Each addon requires Compukters and its upstream mod through CurseForge `requiredDependency` relations;
relation `projectID` values are JSON integers, as required by the upload API validator.
CreateAddition and Propulsion also require Create. Propulsion targets Create Propulsion: Simulated (`1522736`),
not the separate 1.20.1 original. No SDK or development JAR is submitted to CurseForge.
Credentials stay in headers, redirects are refused, responses are bounded and HTTP/
transport errors are redacted. Upload errors identify the filename and destination project.
For bounded JSON error responses, only the API's `errorMessage` or `message` is shown,
with the token redacted and whitespace collapsed; raw response bodies are never printed.
No upload POST is automatically retried.

GitHub Deployments with task/environment `curseforge-upload` form a durable journal:
intent precedes upload, success records the accepted file ID. The payload binds the
project, candidate revision, inventory, artifact bytes and metadata. All known
conflicts and ambiguous outcomes are checked before any target is uploaded. A
successful upload is skipped on retry, including files awaiting moderation; acceptance
is not a visibility or approval claim. The journal is separate from immutable release assets.

An older addon intent using string relation IDs may be retried with numeric IDs only
after its outcome is recorded as confirmed absent. Only that exact metadata correction
is admitted; all artifact and inventory identities must still match. Pending and accepted
intents retain strict metadata matching.

For an unresolved intent, inspect the CurseForge author portal first, including
pending files. With no other publisher running, `curseforge.py reconcile --directory
build/release --artifact FILENAME --file-id ID` attests that the exact staged file and
metadata were accepted. Use `--not-uploaded` instead only after confirming that no file
was created; then retry. Recovery never uploads, and does not use an undocumented
read API or require a second CurseForge developer API key. Details and credential
setup are in [the publication guide](../../docs/contributors/releases.md).

Run tests with `python3 -m unittest discover -s tools/release -v`.

## Unified CI evidence

The current tag-only `Mod release` workflow does not use this evidence/reuse path and temporarily runs
no tests. It publishes the local addon SDK, builds stable archives with both packaged native integration
test tasks excluded, then verifies and uploads the release inventory. The helpers below remain available
for complete verification evidence; the current build must not be described as full verification.

`ci.py` admits complete same-commit verification evidence for the branch/tag workflow.
`resolve` selects only same-repository push runs with a successful full verification job and one
nonexpired `mod-verified-<commit>` artifact. Runs are serialized by commit SHA, including simultaneous
branch and tag pushes. `stage` records all six admitted production archives and exact source/component
identity in `verification.json`; `verify` checks both identity and the actual archives again.

Untagged archives retain the normal `-SNAPSHOT` product version. Their full checks can be reused by the tag,
but stable archives are assembled anew through the unchanged tagged release gates. When the first
build already saw the exact version tag, the evidence also contains a verified stable release inventory,
which the tag reuses without recompilation. A publishing failure does not invalidate its successful
verification job. Expired evidence requires full verification; API/provenance/content errors are explicit.
