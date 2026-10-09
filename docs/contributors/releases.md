---
layout: default
title: Publish Compukters releases
section: contributors
permalink: /RELEASES/
---

# Publish Compukters releases

The `CI and mod release` workflow verifies branch pushes, pull requests and release tags, and builds the autonomous NeoForge JARs for
Minecraft 1.21.1 and 26.1.2. Each contains its tooling carrier and both Linux and
Windows natives. The workflow collects these and the Create, Sable and Propulsion
addon JARs into `dist/`, grouped by Minecraft version, and preserves that layout
as an Actions artifact. GitHub Releases receives all five JARs, release notes, a versioned
composition inventory, and SHA-256 checksums. Modrinth receives one version per
Minecraft target in the existing [Compukters project](https://modrinth.com/mod/compukters),
ID `xriOD3eh`.

## Configure publication

Create the GitHub environment `mod-release`. Add `MODRINTH_TOKEN` as an environment
secret or repository secret, using a Modrinth token allowed to create versions in
the Compukters project. The workflow uses GitHub's job token for GitHub Releases.
Environment protection rules may be configured to require maintainer review before
publishing. The build job needs no publishing credentials; the publishing job runs
only after a pushed release tag passes all verification.

Publish the matching native Runtime first. The mod's Runtime contract in
`config/runtime-release.properties` selects the published Runtime version and its
release commit. The mod accepts every revision of native ABI 21; the selected
release need not match the development VM submodule commit. To select another
compatible revision, provide its descriptor with `-PcompukterRuntimeReleaseFile=/path/to/runtime-release.properties`.
The selected identity must match the published Linux/Windows assets and their manifests. A source-built
native that implements newer artifact reader features does not establish that an
older published bundle supports those features. Keep these contracts aligned when
preparing the candidate; missing or mismatched Runtime assets stop the release.
Both mod JARs record the selected native release at `META-INF/compukters/runtime.properties`.
Inventory schema 3 records that actual component separately from the source submodule revision;
the transfer tool retains readers for inventories 1 and 2 when resuming their publication.

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
3. Run `./gradlew-sandbox-dev-parallel-summary -p addons/dev collectReleaseDistributionJars`
   on the clean exact tag. It downloads and admits the pinned published Runtime bundles,
   runs both `buildReleaseUniversalJar` gates, verifies the addon archives and collects
   the five JARs into `dist/`. For local Rust builds use `collectDistributionJars` instead.
4. Push the candidate branch to run the same full CI before creating a remote tag.
   A manual `CI and mod release` run verifies its selected branch; optional input
   `tag: vX.Y.Z` also assembles that existing tag. Manual runs never publish.
   JARs, complete verification evidence and reports are retained for seven days;
   full Gradle output remains in the job logs.

Local non-interactive commands use `./gradlew-sandbox-dev-parallel-summary` with
JDK 25 selected. GitHub Actions uses the standard `./gradlew` with live output and
`--no-daemon --max-workers=2`. The CI runner installs JDK 21/25 and the VM's pinned Rust toolchain,
fetches locked Cargo dependencies and runs `verifyLocalFull`. Untagged builds retain
`-S` in the base mod version and collect the same five archives with admitted Linux/Windows
Runtime bundles. Tagged builds use `collectReleaseDistributionJars`, including both
`buildReleaseUniversalJar` gates.
Existing Gradle checks own build and admission;
release-transfer tooling does not substitute for them.

## Reuse verification for one commit

Branch and tag pushes share a concurrency group for their commit SHA with a queued, non-cancelling
policy. Pushing a commit and its tag together works in either order. The first run performs full
verification; the next selects its complete evidence after it finishes. Only the same repository's
completed push runs with a successful verification job and a nonexpired, unambiguous artifact qualify.
A failed publication does not discard successful verification. Missing or expired evidence causes a
full check; an API error or a mismatched downloaded source/archive fails explicitly.

Evidence records the exact parent and VM revisions, base and effective versions, selected Runtime
release, and sizes and SHA-256/SHA-512 digests of all five JARs. Snapshot evidence is verified again
against the tag checkout. Since `X.Y.Z-S` and `X.Y.Z` have different product metadata, a tag that reuses
snapshot checks still assembles and verifies the stable JARs through the clean tagged universal gates.
It does not repeat `verifyLocalFull`. If the commit already had its version tag during the first build,
that build produced the stable release inventory, which is reused without another compilation.

## Publish and recover

Pushing `vX.Y.Z` reuses successful same-commit verification when available, applies the tagged packaging
gates when stable archives still need assembling, and then publishes. This is the explicit
publication action; neither building nor creating a local tag uploads anything.
The workflow checks `MODRINTH_TOKEN` before creating a GitHub release.

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
assembly job's original artifact through its recorded output, rather than rebuilding
the candidate. It reconciles the existing release and resumes missing
uploads. For example, if GitHub succeeded and the second Modrinth version failed,
the retry preserves GitHub assets and the first Modrinth version. Inspect remote
state before retrying; an ambiguous POST failure might already have created the
version. Never change the release contents, move the tag, or overwrite assets to
make a retry pass. A changed release requires a new version.

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
CurseForge publication and external component acquisition are subsequent stages.
