# Third-party notices

This inventory covers third-party material checked into the repository or
distributed in the Compukters production mod archive. It does not change any
third-party license. The complete Apache License 2.0 text is available at
`licenses/project/Apache-2.0.txt` and is packaged as
`META-INF/licenses/Compukters-Apache-2.0.txt`.

`licenses/distribution-components.tsv` is the machine-readable inventory used
by archive verification. Dependency version changes must update both files.

## Kotlin compiler and libraries

The compiler worker contains JetBrains Kotlin compiler and runtime artifacts
from Kotlin 2.4.10, plus `kotlin-reflect` 1.6.10. The outer mod archive also
nests `kotlin-stdlib` 2.4.10. JetBrains-owned Kotlin code is licensed under
Apache-2.0. Kotlin contains third-party code under the additional licenses
identified by its upstream license inventory.

- Upstream: <https://github.com/JetBrains/kotlin>
- Pinned tag: `v2.4.10`
- Pinned commit: `5687445832cd835b4509b9fbc264cdf1a8201093`
- Complete license corpus: `licenses/kotlin/v2.4.10/`
- Packaged location: `META-INF/licenses/kotlin/v2.4.10/`

The worker also contains JetBrains annotations 13.0, licensed under
Apache-2.0: <https://github.com/JetBrains/java-annotations>.

## Kotlin coroutines and logging

The worker contains `kotlinx-coroutines-core-jvm` 1.8.0 and the outer archive
nests version 1.11.0. Kotlin coroutines are copyright JetBrains and the Kotlin
contributors and are licensed under Apache-2.0:
<https://github.com/Kotlin/kotlinx.coroutines>.

The outer archive nests `kotlin-logging-jvm` 8.0.4. Kotlin Logging is copyright
Ohad Shai and contributors and is licensed under Apache-2.0:
<https://github.com/oshai/kotlin-logging>.

## Statically linked Rust crates

The packaged native `compukter_ffi` library statically links the crates below.
For crates offered under `MIT OR Apache-2.0`, this distribution selects
Apache-2.0. Versions are pinned by `host/compukter-ffi/Cargo.lock`.

- `block-buffer` 0.10.4 — Apache-2.0
- `cfg-if` 1.0.4 — Apache-2.0
- `cpufeatures` 0.2.17 — Apache-2.0
- `crypto-common` 0.1.7 — Apache-2.0
- `digest` 0.10.7 — Apache-2.0
- `libc` 0.2.189 — Apache-2.0
- `sha2` 0.10.9 — Apache-2.0
- `typenum` 1.20.1 — Apache-2.0
- `version_check` 0.9.5 — Apache-2.0
- `generic-array` 0.14.7 — MIT; complete text at
  `licenses/rust/generic-array-0.14.7-LICENSE.txt`

Crate sources and authorship metadata are available through
<https://crates.io/> and the source URLs recorded in Cargo package metadata.

## Terminal fonts

The mod distributes generated bitmap atlases derived from these pinned fonts:

- Cozette v1.30.0, copyright Ines, MIT. Complete license and provenance:
  `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Cozette-MIT.txt`
  and `Cozette-PROVENANCE.txt`.
- Dina v2.92 Regular 6pt, copyright Joergen Ibsen, MIT. Complete license and
  provenance: `Dina-LICENSE.txt` and `Dina-PROVENANCE.txt` in the same
  `META-INF/licenses` directory.
- ProggyTiny at commit `139ec08a38096161291792313ef5803fc4f0e37b`,
  copyright Tristan Grimmer, MIT. Complete license and provenance:
  `Proggy-MIT.txt` and `Proggy-PROVENANCE.txt` in that directory.

The source inputs and duplicate provenance records are under `tools/fonts/`.

## Gradle Wrapper

The repository includes the Gradle Wrapper from Gradle 9.7.1. Gradle is
licensed under Apache-2.0: <https://github.com/gradle/gradle>. The wrapper is a
repository/build tool and is not embedded in the Compukters production mod
archive.
