# K16 Firmware Release Builds

> Issue: [#154](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/154)
> Optimized K16 core codegen: [#158](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/158)

Bundled K16 kernel firmware can be built through either a debug or release
Cargo profile. Resource builds use the release profile by default because the
kernel image is a fixed-range firmware artifact; debug images can exceed that
range and must fail at link time instead of booting a corrupted chain. The BIOS
and bootloader are built from freestanding C with the staged K16 Clang path:
BIOS links directly to raw `.kflash`, and bootloader links to the fixed K16E
`kernel-loader.kb` artifact. Maintainers can still request a profile explicitly
for Rust firmware. The toolchain can be a local staged install from
`prepareBuiltK16Toolchain`; this does not require publishing a GitHub release
artifact:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainMode=prebuilt \
  -Pk16ToolchainDir=/absolute/path/to/.toolchain/k16/<pin>/<host> \
  -Pk16FirmwareProfile=release
```

The selected profile controls Rust Cargo invocation and Rust kernel artifact
lookup:

```text
build/generated/k16-guest-target/kernel/k16-unknown-kraftos/release/k16-kernel
```

There is no debug artifact fallback. If the selected profile does not produce
the expected kernel output, the Gradle task fails. The C BIOS and bootloader
tasks write `build/generated/k16-firmware-resources/firmware/k16-bios.kflash`
and `build/generated/k16-firmware-resources/kernel-loader.kb` directly from the
linked K16 outputs.

Release firmware uses Cargo's release profile and no longer overrides
`opt-level` back to zero. The firmware tasks still pass explicit K16-safe flags
for jump tables, debug info, debug assertions, overflow checks, UB checks, panic
strategy, and static relocation.

The selected prepared toolchain must include host `library/std` sysroot
artifacts as well as the K16 Rust source tree. Cargo needs host std for build
scripts even when the firmware target itself is freestanding and builds only
`core`.
