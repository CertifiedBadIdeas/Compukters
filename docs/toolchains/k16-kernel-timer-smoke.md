# K16 Kernel Timer Smoke

Issue: [#185](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/185), [#186](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/186), [#187](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/187)

`tools/k16-kernel-timer-smoke.sh` verifies that the real Rust kernel in `rust/guest/k16-kernel` owns the single-core `timer0` heartbeat path.

Run:

```bash
tools/k16-kernel-timer-smoke.sh
```

The smoke builds `k16-kernel` with the pinned K16 Rust toolchain and `--k16-target=kernel`, links the explicit `k16-cpu-helpers` runtime object for CSR/interrupt helper symbols, inspects the linked K16E artifact, then runs it through a temporary host runner using `K16ComputerHandle`.

The host runner loads the kernel artifact into guest RAM, enters the kernel directly, expects an initial `yield` from the live kernel idle loop with control status `READY`, advances the host game tick twice, resumes the VM after each tick, and expects the timer0 driver path to write a debug heartbeat marker for each dispatched interrupt.

After the timer heartbeat proof, the runner patches the current idle instruction with a write to the read-only `trap_cause` CSR. That raises `EXPLICIT_TRAP` with `trap_value = cpu::csr::TRAP_CAUSE`, proving the kernel synchronous trap dispatcher reaches the first non-returning syscall proof without changing the VM ABI.

Expected output:

```text
first_signal=yield timer_signals=yield,yield syscall_signal=yield status=READY debug_suffix=7c7c53
K16 kernel timer smoke passed
```

`debug_suffix=7c7c53` means the timer0 driver wrote `||` (`0x7c 0x7c`) after two `advance_game_tick()` calls, then the synchronous trap syscall proof wrote `S` (`0x53`).
