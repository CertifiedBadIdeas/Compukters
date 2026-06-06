# K16 Kernel Timer Smoke

Issue: [#185](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/185)

`tools/k16-kernel-timer-smoke.sh` verifies that the real Rust kernel in `rust/guest/k16-kernel` owns the single-core `timer0` heartbeat path.

Run:

```bash
tools/k16-kernel-timer-smoke.sh
```

The smoke builds `k16-kernel` with the pinned K16 Rust toolchain and `--k16-target=kernel`, links the explicit `k16-cpu-helpers` runtime object for CSR/interrupt helper symbols, inspects the linked K16E artifact, then runs it through a temporary host runner using `K16ComputerHandle`.

The host runner loads the kernel artifact into guest RAM, enters the kernel directly, expects an initial `yield` from the live kernel idle loop with control status `READY`, advances the host game tick, resumes the VM, and expects the timer0 handler to write the debug heartbeat marker.

Expected output:

```text
first_signal=yield second_signal=yield status=READY debug_suffix=7c
K16 kernel timer smoke passed
```

`debug_suffix=7c` means the timer0 handler wrote `|` (`0x7c`) after `advance_game_tick()`.
