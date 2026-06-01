# K16 Firmware Release Builds

> Issue: [#154](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/154)
> Optimized K16 core codegen: [#158](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/158)

Bundled K16 firmware can be built through either a debug or release Cargo
profile. Development resource builds use the debug profile by default.
`buildProductionUniversalJar` selects the release firmware profile by default,
and maintainers can force the same path explicitly:

```bash
./gradlew-sandbox :v1_21_1-neoforge:processResources \
  -Pk16ToolchainDir=/absolute/path/to/k16-toolchain \
  -Pk16FirmwareProfile=release
```

The selected profile controls both Cargo invocation and artifact lookup:

```text
build/generated/k16-guest-target/bios/k16-unknown-kraftos/release/k16-bios
build/generated/k16-guest-target/boot/k16-unknown-kraftos/release/k16-boot
build/generated/k16-guest-target/kernel/k16-unknown-kraftos/release/k16-kernel
```

There is no debug artifact fallback. If the selected profile does not produce
the expected BIOS, bootloader, or kernel output, the Gradle task fails.

Release firmware uses Cargo's release profile and no longer overrides
`opt-level` back to zero. The firmware tasks still pass explicit K16-safe flags
for jump tables, debug info, debug assertions, overflow checks, UB checks, panic
strategy, and static relocation.
