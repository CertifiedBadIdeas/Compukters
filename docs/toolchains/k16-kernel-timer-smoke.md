# K16 Kernel Timer Smoke

Issue: [#185](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/185), [#186](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/186), [#187](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/187), [#188](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/188), [#189](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/189), [#190](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/190)

`tools/k16-kernel-timer-smoke.sh` verifies that the real Rust kernel in `rust/guest/k16-kernel` owns the single-core `timer0` heartbeat path.

Run:

```bash
tools/k16-kernel-timer-smoke.sh
```

The smoke builds `k16-kernel` with the pinned K16 Rust toolchain and `--k16-target=kernel`, links the explicit `k16-cpu-helpers` runtime object for CSR/interrupt helper symbols, inspects the linked K16E artifact, then runs it through a temporary host runner using `K16ComputerHandle`.

The host runner loads the kernel artifact into guest RAM, enters the kernel directly, expects an initial `yield` from the live kernel idle loop with control status `READY`, advances the host game tick twice, resumes the VM after each tick, and expects the timer0 driver path to write a debug heartbeat marker for each dispatched interrupt.

After the timer heartbeat proof, the runner patches the current kernel continuation with a trampoline to a scratch RAM probe. The probe restores the overwritten bytes, executes the returning `syscall` instruction with syscall number `2`, resumes through `iret`, copies returned `r0` into `r2`, then executes syscall number `3` with `trap_arg0 = 0x21`. The kernel writes the debug byte, returns status `0`, the probe copies returned `r0` into `r3`, restores `r2`, and yields. This proves the kernel syscall path can return an explicit ABI value, consume one captured argument, and continue at the instruction after `syscall`.

Expected output:

```text
first_signal=yield timer_signals=yield,yield syscall_signal=yield status=READY debug_suffix=7c7c5321 continuation_r2=83 continuation_r3=0
K16 kernel timer smoke passed
```

`debug_suffix=7c7c5321` means the timer0 driver wrote `||` (`0x7c 0x7c`), syscall `2` wrote `S` (`0x53`), and syscall `3` wrote the byte supplied through `trap_arg0` (`0x21`, ASCII `!`). `continuation_r2=83` means guest code after the returning syscall observed the kernel `r0` return value (`0x53`, ASCII `S`); `continuation_r3=0` means the argument syscall returned status `0` before yielding.
