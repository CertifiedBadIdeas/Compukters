# K16 Guest Interrupt Smoke

Issue: [#180](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/180)

`tools/k16-guest-interrupt-smoke.sh` verifies the first end-to-end guest Rust interrupt path.

Run:

```bash
tools/k16-guest-interrupt-smoke.sh
```

The smoke uses:

- `K16_CARGO` for the K16-capable Cargo binary;
- `K16_RUSTC` for the K16-capable Rust compiler;
- `K16_TOOL` for the `k16` host tool;
- `K16_LLVM_BIN_DIR` for the LLVM tools used to lower guest LLVM IR;
- `K16_RUST_TARGET_JSON` for `tools/k16-unknown-kraftos.json`.

If these variables are not set, the script resolves the pinned toolchain under `.toolchain/k16/<pin>/<host>/bin` and the repo-local LLVM build under `.toolchain/build/llvm/k16-min/bin`.

The guest program is generated in a temporary directory and depends on local `k16-abi` and `k16-rt`. It installs a timer0 handler with `install_trap_vector`, enables the timer0 interrupt mask, waits by writing to the resumable `control::YIELD` MMIO boundary, records `trap_cause`, `trap_pc`, `trap_value`, and `interrupt_pending`, then returns through `iret_once`.

The host runner loads the linked K16E program into `K16ComputerHandle`, runs until the initial yield, calls `advance_game_tick`, resumes the VM, and expects:

```text
first_signal=yield second_signal=halt debug_bytes=492a
K16 guest interrupt smoke passed
```

`debug_bytes=492a` means the interrupt handler wrote `I` (`0x49`) and the resumed guest `main` returned `42` (`0x2a`) through the normal startup path.
