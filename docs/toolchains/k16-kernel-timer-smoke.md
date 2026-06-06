# K16 Kernel Timer Smoke

Issue: [#185](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/185), [#186](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/186), [#187](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/187), [#188](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/188), [#189](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/189), [#190](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/190), [#191](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/191), [#194](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/194)

`tools/k16-kernel-timer-smoke.sh` verifies that the real Rust kernel in `rust/guest/k16-kernel` owns the single-core `timer0` heartbeat path.

Run:

```bash
tools/k16-kernel-timer-smoke.sh
```

The smoke builds `k16-kernel` with the pinned K16 Rust toolchain and `--k16-target=kernel`, links the explicit `k16-cpu-helpers` runtime object for CSR/interrupt helper symbols, inspects the linked K16E artifact, then runs it through a temporary host runner using `K16ComputerHandle`.

The host runner loads the kernel artifact into guest RAM, enters the kernel directly, expects an initial `yield` from the live kernel idle loop with control status `READY`, advances the host game tick twice, resumes the VM after each tick, and expects the timer0 driver path to write a debug heartbeat marker for each dispatched interrupt.

After the timer heartbeat proof, the runner patches the current kernel continuation with a trampoline to a scratch RAM probe. The probe restores the overwritten bytes, executes the returning `syscall` instruction with `k16_abi::syscall::DEBUG_MARKER`, resumes through `iret`, copies returned `r0` into `r2`, then executes `k16_abi::syscall::DEBUG_WRITE_BYTE` with `trap_arg0 = 0x21`. The kernel writes the debug byte, returns `k16_abi::syscall::STATUS_OK`, and the probe copies returned `r0` into `r3`. The probe then executes `k16_abi::syscall::YIELD`; the kernel yields once to the host while still inside the syscall handler, resumes, returns `STATUS_OK` through `iret`, and the probe copies returned `r0` into `r4` before its final direct MMIO yield. This proves the kernel syscall path can return an explicit ABI value, consume one captured argument, host-yield during syscall handling, and still continue at the instruction after `syscall`.

Expected output:

```text
first_signal=yield timer_signals=yield,yield syscall_signal=yield continuation_signal=yield status=READY debug_suffix=7c7c5321 continuation_r2=83 continuation_r3=0 continuation_r4=0
K16 kernel timer smoke passed
```

`debug_suffix=7c7c5321` means the timer0 driver wrote `||` (`0x7c 0x7c`), `DEBUG_MARKER` wrote `S` (`0x53`), and `DEBUG_WRITE_BYTE` wrote the byte supplied through `trap_arg0` (`0x21`, ASCII `!`). `continuation_r2=83` means guest code after the returning syscall observed the kernel `r0` return value (`0x53`, ASCII `S`); `continuation_r3=0` means the argument syscall returned `STATUS_OK`; `continuation_r4=0` means the yield syscall also returned `STATUS_OK` after its host-visible yield.
