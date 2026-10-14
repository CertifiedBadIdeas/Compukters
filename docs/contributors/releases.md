---
layout: default
title: Publish Compukters releases
section: contributors
permalink: /RELEASES/
---

# Publish Compukters releases

The `Mod release` workflow runs only for pushed `v*` tags and builds the autonomous NeoForge JARs for
Minecraft 1.21.1 and 26.1.2. Each contains its tooling carrier and both Linux and
Windows natives. The workflow collects these and the Create, Sable and Propulsion
addon JARs into `dist/`, grouped by Minecraft version, and preserves that layout
as an Actions artifact. GitHub Releases receives all five JARs, release notes, a versioned
composition inventory, SHA-256 checksums, and `compukters-addon-development-X.Y.Z.zip`.
The SDK archive supplies development dependencies for standalone addon builds without a separate hosted Maven repository. Modrinth receives one version per
Minecraft target in the existing [Compukters project](https://modrinth.com/mod/compukters),
ID `xriOD3eh`. CurseForge receives the same two base-mod JARs in project `1528063`,
with separate Minecraft 1.21.1 and 26.1.2 releases marked for NeoForge, Client and Server.
Addon JARs and the SDK remain GitHub-only.

## Configure publication

Create the GitHub environment `mod-release`. Add `MODRINTH_TOKEN` as an environment
secret or repository secret, using a Modrinth token allowed to create versions in
the Compukters project. Add `CURSEFORGE_TOKEN` in the same environment or as a
repository secret, using an author upload token with access to CurseForge project
`1528063`. Generate it through the [CurseForge author API token settings](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-api).
Never put either token in source, workflow inputs or command-line arguments. For example,
use GitHub's environment settings or `gh secret set CURSEFORGE_TOKEN --env mod-release
--repo CertifiedBadIdeas/Compukters` and enter the value through its secure prompt.

The workflow uses GitHub's job token for GitHub Releases and the CurseForge upload
journal. Only the GitHub publication job has `contents: write`; only the CurseForge
job has `deployments: write`. Both distribution jobs run independently after GitHub
publication, so a Modrinth failure does not block CurseForge and vice versa.
Environment protection rules may be configured to require maintainer review before
publishing. The build job needs no publishing credentials; the publishing job runs
only after a pushed release tag passes archive and inventory verification.

Publish the matching native Runtime first. The mod's Runtime contract in
`config/runtime-release.properties` selects the published Runtime version and its
release commit. The current JNI and FFM transports require native C ABI 23; a compatible selected
release need not match the development VM submodule commit. To select another
compatible revision, provide its descriptor with `-PcompukterRuntimeReleaseFile=/path/to/runtime-release.properties`.
The selected identity must match the published Linux/Windows assets and their manifests. A source-built
native that implements newer artifact reader features does not establish that an
older published bundle supports those features. Keep these contracts aligned when
preparing the candidate; missing or mismatched Runtime assets stop the release.
The checked-in descriptor currently selects Runtime 0.22.0, while this checkout requires ABI 23. Before tagged
packaging, select a compatible published Runtime 0.23.x descriptor; the old default does not establish release
readiness. Check the selected version and commit against `config/runtime-release.properties` when preparing a candidate.
Both mod JARs record the selected native release at `META-INF/compukters/runtime.properties`.
Inventory schema 4 records that actual component separately from the source submodule revision;
the transfer tool retains readers for inventories 1, 2 and 3 when resuming their publication.

## Prepare and validate a candidate

Mainline mod releases advance the minor version. Patch versions are reserved for fixes
to an already published release on its `fix/X.Y.x` branch. After successful publication,
run `bumpAfterRelease` to start the next development minor; for example, `0.5.0` becomes
`0.6.0`. Do not advance the development version before publication succeeds.

1. Set the intended stable version in `gradle.properties`, date its cumulative
   section in `docs/CHANGELOG.md`, and commit the final candidate and pinned VM
   revision.
2. Run `verifyLocalFull` on that exact candidate before release preparation is
   declared complete. Use the existing release tasks to create the local tag;
   `tagRelease` creates `vX.Y.Z` without changing the version.
3. Run `./gradlew-sandbox-dev-parallel-summary -p addons/dev collectReleaseDistributionJars :Compukters:packageAddonDevelopmentDependencies`
   on the clean exact tag. It downloads and admits the pinned published Runtime bundles,
   runs both `buildReleaseUniversalJar` gates, verifies the addon archives and collects
   the five JARs into `dist/`. For local Rust builds use `collectDistributionJars` instead.
4. Push the candidate branch and its `vX.Y.Z` tag. Only the tag starts the release workflow;
   ordinary branch pushes and pull requests do not start mod CI.

Local non-interactive commands use `./gradlew-sandbox-dev-parallel-summary` with
JDK 25 selected. GitHub Actions uses standard `./gradlew` with live output and
`--no-daemon --parallel --max-workers=2`. The runner installs JDK 21/25, downloads the exact Linux/Windows Runtime bundles, stages the addon SDK using those
published natives, and then builds the five stable JARs and the addon SDK archive.
This path neither installs a Rust toolchain nor fetches Cargo dependencies: the native Runtime is already published.

CI tests are temporarily disabled. It does not run `verifyLocalFull`, JVM/Rust tests, GameTests,
conformance scenarios or IDE latency measurements. The two `packagedNativeIntegrationTest` tasks
are explicitly excluded from the composite release build. Archive composition, exact clean-tag state,
native bundle identity and release inventory checks remain enabled. This build establishes packaging
and publication evidence; it does not establish full test coverage or native execution evidence.
Local full verification and the complete tagged gates above remain available for release preparation.

The build uploads five JARs, the SDK ZIP, release notes, inventory and checksums as one Actions artifact retained
for seven days. Runs for the same tag are queued without cancellation. There is no branch verification
artifact to reuse in this temporary tag-only workflow.

## Publish and recover

Pushing `vX.Y.Z` builds the stable archives, verifies their inventory, and then publishes. This is the explicit
publication action; neither building nor creating a local tag uploads anything.
Each distribution job checks its own credentials before contacting its upload API.
Missing site credentials fail that job without preventing GitHub or the other site
from publishing. Configure both secrets before pushing a release tag.

GitHub publication creates a draft, checks existing assets, uploads only missing
files, verifies the complete file set, and publishes the draft. It also checks
that the remote tag still points to the candidate revision recorded in the
inventory. A differing asset or release description fails instead of being
replaced. An upload failure leaves the draft available for recovery.

Modrinth publication compares an existing version's file hash, filename, size,
Minecraft version, loader, release type, status, dependencies, and changelog.
Identical versions are skipped; conflicting versions fail. All known conflicts are
checked before any missing version is uploaded. The two version numbers are
`1.21.1-neoforge-X.Y.Z` and `26.1.2-neoforge-X.Y.Z`.

After a transport failure or partial publication, use **Re-run failed jobs** on
the original tag-triggered run. A retried publishing job downloads the successful
build job's original artifact through its recorded output, rather than rebuilding
the candidate. It reconciles the existing release and resumes missing
uploads. For example, if GitHub succeeded and the second Modrinth version failed,
the retry preserves GitHub assets and the first Modrinth version. Inspect remote
state before retrying; an ambiguous POST failure might already have created the
version. Never change the release contents, move the tag, or overwrite assets to
make a retry pass. A changed release requires a new version.

CurseForge submissions use the [author upload API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-api)
and the same verified inventory and markdown notes. The tool checks the available
Minecraft/NeoForge/environment names before uploading. A returned file ID means
**accepted for processing**, not approved or visible; check the project's author
portal for moderation results. No Modrinth visibility setting is changed.

The author upload API does not document a file-listing endpoint for reconciling
pending submissions. Before each POST, the tool records an immutable upload intent
in [GitHub Deployments](https://docs.github.com/en/rest/deployments/deployments),
using task/environment `curseforge-upload`. It binds the project, inventory hash,
JAR name/size/hash, metadata hash and candidate revision. An accepted file ID is
recorded in a deployment status. Successful submissions are skipped on retry;
conflicting identities or unresolved attempts stop before uploading any missing JAR.
These records are upload history, not proof of CurseForge approval. They do not
alter the immutable GitHub release assets or release inventory schema.

If a runner stops after recording an intent, or CurseForge accepts a file but its
response/status recording is lost, **do not just upload again**. With no publication
job running, inspect project `1528063` in the author portal, including pending and
rejected files. Restore the original successful build artifact and use the tooling
from its tagged revision with a GitHub credential permitted to read repository
contents and write deployments:

```bash
python3 tools/release/curseforge.py reconcile --directory build/release \
  --artifact compukters-1.21.1-neoforge-X.Y.Z.jar --file-id FILE_ID
```

Use `--file-id` only after checking that the remote file's project, exact JAR/hash,
Minecraft/loader/environment metadata and changelog match this inventory. This is an
explicit operator attestation; the upload API cannot independently verify it. If the
file is rejected, resolve the rejection first rather than attesting it as successful.
Only after confirming that no file was created, replace `--file-id FILE_ID` with
`--not-uploaded`. This records confirmed absence and permits a new attempt. Neither
recovery command uploads anything. Retry the failed CurseForge job afterward using
its original artifact. Serialize local recovery/publication with Actions runs;
do not delete deployment history to bypass an unresolved attempt.

The temporary staged directory can also be inspected with:

```bash
python3 tools/release/release.py verify --directory build/release
```

The [transfer tooling contract](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/tools/release/README.md)
describes the inventory and local behavioral tests. A validated workflow file or
successful transfer test does not establish a live CI run or published release.

## Future component delivery

The current inventory explicitly records `distribution: bundled`, the shared
tooling identity and the carrier digest, so release identity and artifact
composition are represented separately. A future smaller JAR will need a new
admitted composition with pinned component location, size and hashes, bounded
acquisition, cache reuse and offline failure behavior on both physical clients
and dedicated servers. The autonomous JAR will use the same component identities.
External component acquisition remains a subsequent stage.
