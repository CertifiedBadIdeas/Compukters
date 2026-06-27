# K16 Firmware Release Builds

> Issue: [#154](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/154)
> Optimized K16 core codegen: [#158](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/158)

Bundled K16 bootloader and kernel firmware can be built through either a debug
or release Cargo profile. Resource builds use the release profile by default
because bootloader and kernel images are fixed-range firmware artifacts; debug
images can exceed those ranges and must fail at link time instead of booting a
corrupted chain. The BIOS is built from freestanding C with the staged K16
Clang path and links directly to raw `.kflash`. Maintainers can still request a
profile explicitly for Rust firmware. The toolchain can be a local staged
install from `prepareBuiltK16Toolchain`; this does not require publishing a
GitHub release artifact:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=prebuilt \
  -Pk16ToolchainDir=/absolute/path/to/.toolchain/k16/<pin>/<host> \
  -Pk16FirmwareProfile=release
```

The selected profile controls Rust Cargo invocation and Rust artifact lookup:

```text
build/generated/k16-guest-target/boot/k16-unknown-kraftos/release/k16-boot
build/generated/k16-guest-target/kernel/k16-unknown-kraftos/release/k16-kernel
```

There is no debug artifact fallback. If the selected profile does not produce
the expected bootloader or kernel output, the Gradle task fails. The C BIOS
task writes `build/generated/k16-firmware-resources/firmware/k16-bios.kflash`
directly from the linked raw flash output.

Release firmware uses Cargo's release profile and no longer overrides
`opt-level` back to zero. The firmware tasks still pass explicit K16-safe flags
for jump tables, debug info, debug assertions, overflow checks, UB checks, panic
strategy, and static relocation.

The selected prepared toolchain must include host `library/std` sysroot
artifacts as well as the K16 Rust source tree. Cargo needs host std for build
scripts even when the firmware target itself is freestanding and builds only
`core`.
