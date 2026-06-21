# K16 ABI Changelog

## Unreleased

- Bundled K16 storage is now split into production and development layouts:
  `firmware/k16-system-storage0.kv` excludes test binaries, while test resources
  provide `firmware/k16-system-storage0-dev.kv` with `/bin/alloc-test.kx` and
  `/bin/proc-test.kx`.
- The bundled K16 shell no longer carries a built-in `help` command or embedded
  command list. `help` now follows normal executable resolution as
  `/bin/help.kx` and reports the standard launch error when no such program is
  installed.
- Added `k16 size-report <map>...` plus Gradle
  `:v1_21_1-neoforge:reportK16UserlandSize` developer tooling to summarize
  per-program K16 userland map sizes and repeated retained-section
  contributors before shared-runtime work.
- `k16 link` now accepts `--map <output.map>` and writes a deterministic
  retained-section report with target, load address, payload/memory size, and
  per-section class, offset, object, file bytes, and memory bytes.
- `k16-ld` now accepts the same `--map <output.map>` option, and the Gradle
  bundled K16 firmware build emits retained-section maps beside generated K16E
  `.kb` and `.kx` artifacts for size analysis.
- `k16 inspect` now supports dynamic K16E v2 program images and reports
  entry offset, payload size, memory size, relocation count, and relocation
  table byte size. Fixed K16E v1 inspect output remains unchanged.
- The bundled K16 shell now includes an `exit [code]` builtin. `exit` ends the
  shell with status `0`, causing supervised init to restart it; `exit <code>`
  exits with the parsed decimal status, while invalid arguments print
  `ERR INVAL` and keep the shell running.
- `RUN` now accepts only the structured argv request format
  (`RUN_ARGV_MAGIC`) and `kraft-std` exposes only `process::run_with_args`.
  The legacy raw-path `RUN(path, len, 0)` / `process::run(path)` launch path
  was removed; zero-argument launches use the same argv request format with
  `argc = 0`.
- `WAIT(pid_or_zero, out_status_ptr)` now reports `ERROR_FAULT` to the
  waiting parent when the final exit-status write faults, instead of turning
  the fault into a kernel panic. The bundled syscall fault fixture now covers a
  spawned child plus a bad translated status pointer.
- Translated user fault exits now complete the same `WAIT` status write path as
  explicit `EXIT`, including returning `ERROR_FAULT` to the waiting parent when
  the wait-status pointer faults.
- The bounded K16 foreground process table now provides three child slots
  after init, allowing the production `init -> shell -> utility -> utility`
  shape used by shell-launched tools that spawn another program. The bundled
  `/bin/proc-test.kx` utility exercises `SPAWN` plus `WAIT` by spawning
  `/bin/cat.kx /etc/motd`, waiting for the exact PID, and reporting success
  only after the child exits with status `0`.
- Runtime init now clears every foreground process slot, trap frame, and wait
  pointer after the bounded foreground table grows beyond the first two child
  slots.
- Added guest-visible K16 `SPAWN` (`22`) and `WAIT` (`23`) syscalls plus
  `kraft-std::process::spawn_with_args`, `wait`, and `wait_any`.
  `SPAWN(request, len)` accepts the bounded argv request format with
  `SPAWN_ARGV_MAGIC`, creates a direct child, and returns its PID without
  immediately entering it. `WAIT(pid_or_zero, out_status_ptr)` starts a ready
  direct child, writes its exit status to `out_status_ptr` when it exits, and
  returns the reaped PID. `RUN` remains the synchronous launch path and still
  returns child exit status directly.
- The K16 kernel now implements `RUN` through the same internal child
  creation and wait lifecycle as `SPAWN` plus `WAIT`. This preserves the
  guest-visible synchronous `RUN` ABI while keeping process setup on one kernel
  path.
- The direct K16 runtime factory default RAM size is now 1 MiB while the boot
  floor remains 256 KiB, matching the production config headroom needed for
  nested translated shell smoke coverage.
- The K16 kernel process-table model now has an exited-child reap path: a child
  can retain PID, parent PID, and exit status metadata until its parent reaps it
  by PID or by `0 = any child`.
- K16 kernel process table entries are now internally PID-aware. Init has
  PID 1, child launches allocate stable non-slot PIDs starting at 2, and each
  child records parent PID metadata. The guest-visible `RUN`/`EXIT` ABI remains
  synchronous and unchanged: `RUN` still returns child exit status or a negative
  K16 error, not a PID.
- `kraft-std::fs` now exposes typed `path::PathRef` variants for existing
  path-taking filesystem helpers (`open`, `create`, `append`, `read_dir`,
  `metadata`, `remove_file`, `create_dir`, `remove_dir`, and `rename`) while
  preserving the existing `&str` call surface. Bundled filesystem utilities now
  use the typed calls for validated paths, and `kraft-std` has host-side
  allocator regression coverage for overflowing allocation-size calculations.
- `kraft-std` now exposes shared bundled-coreutils multi-path helpers and a
  bounded `path::PathRef` wrapper. Bundled `cat`, `ls`, `stat`, `rm`,
  `mkdir`, and `rmdir` reuse the shared helpers, while the shell now delegates
  `RUN` argv path preparation to focused helper functions without changing
  command output.
- `kraft-std` now exposes `path::PathBuffer` and
  `path::WorkingDirectory` for bounded K16 userland path normalization. The
  K16 shell now consumes that shared path API instead of owning its own cwd
  normalization types.
- `kraft-std` now exposes the bundled coreutils path-argument resolution policy
  used by the K16 shell when preparing `RUN` argv paths. The shell no longer
  owns the per-command filesystem argument table directly.
- `kraft-std` now exposes shared K16 syscall/fs status-name helpers. Bundled
  filesystem utilities and the shell use the shared names for diagnostics while
  preserving each command's existing generic fallback label for unknown errors.
- The bundled K16 shell now resolves relative filesystem path arguments for the
  write-side workflow consistently with read/list/remove utilities. `write`
  resolves only its path argument, preserving `--append` and payload arguments
  verbatim, while `stat`, `rm`, `mkdir`, and `rmdir` now also receive
  cwd-resolved paths from the shell.
- Bundled multi-path filesystem utilities now use a consistent aggregate
  failure policy. `cat`, `stat`, and `ls` continue processing later path
  arguments after a per-path error, print per-path diagnostics, and exit with
  status `1` if any path failed. `rm`, `mkdir`, and `rmdir` now also preserve
  their existing per-path diagnostics while returning a failing child status
  when any path operation failed.
- `kraft-std::process::run_with_args` returns
  `Result<process::ExitStatus, process::Error>` instead of exposing successful
  child exit statuses as raw `u32` values. `ExitStatus::code()` returns the
  child status word and `ExitStatus::success()` is true only for `0`; negative
  K16 launch/runtime statuses still return `Err(process::Error::Syscall(...))`.
- The bundled K16 shell now reports non-zero child process exit statuses as
  `ERR EXIT <status>` and tracks the last command status through a new
  `status` builtin. `status` prints `STATUS <decimal>` for non-negative child
  statuses and `STATUS <error-name>` for known negative K16 errors. `RUN`
  remains synchronous: a non-negative return is the child exit status, while
  negative values remain K16 launch/runtime errors such as `ERROR_NO_ENTRY`,
  `ERROR_BUSY`, and `ERROR_FAULT`.
- K16 synchronous exceptions now expose interrupted execution mode metadata in
  existing trap argument CSRs: for non-syscall exceptions, `trap_arg0` is
  `0 = physical` or `1 = translated`, and `trap_arg1` is `0 = kernel` or
  `1 = user`. The explicit `syscall` path still uses `trap_arg0..trap_arg2`
  for captured syscall arguments. The kernel uses this metadata to treat
  translated user instruction-fetch/load/store faults as `ERROR_FAULT` child
  exits instead of kernel panics.
- K16 translated user launches now reserve two 4 KiB user stack pages and keep
  a 4 KiB heap guard below them. Launch-time mappings cover the loaded image
  pages plus the committed user stack pages, and `BRK`/`SBRK` heap limits now
  stop below the whole reserved stack range instead of only below the stack
  pointer. This makes large userland syscall request builders fail by normal
  heap/stack policy instead of silently colliding with a one-page stack.
- Added K16 `RENAME` (`21`) plus bundled `/bin/mv.kx` for regular-file
  metadata moves inside ROOT/K16FS. The kernel rejects open source files with
  `ERROR_BUSY`, rejects existing destinations with `ERROR_INVALID`, and does
  not overwrite destination paths.
- Host-side K16FS tooling now matches the guest bounded inline extent mutation
  model: `k16 fs kfs mkdir` can grow full directories, and `k16 fs kfs put`
  can store files across multiple inline extents when the free space is
  fragmented.
- Added K16 `MKDIR` (`19`) and `RMDIR` (`20`) plus bundled `/bin/mkdir.kx`
  and `/bin/rmdir.kx` for userland directory mutation. `RMDIR` removes only
  empty directories and reports `ERROR_NOT_EMPTY` for directories with live
  entries. Guest-side K16FS directory mutation now reuses free/deleted entries
  and grows directories by extending the last inline extent when possible or by
  adding another bounded inline extent when the adjacent block is already used.
- Added K16 `UNLINK` (`18`) and bundled `/bin/rm.kx` for removing regular
  ROOT/K16FS files from userland. The kernel rejects directories and files that
  still have an open kernel fd, and guest K16FS marks deleted directory
  entries/inodes while freeing the file data blocks.
- Added K16 writable regular file fd modes for ROOT/K16FS. `OPEN` now accepts
  `OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_TRUNCATE` and
  `OPEN_WRITE_ONLY | OPEN_CREATE | OPEN_APPEND`; `WRITE` can write to returned
  regular file fds, `SEEK` (`17`) can move regular file fd offsets inside the
  current file size, and K16FS regular-file writes can grow files by extending
  the last inline extent when possible or by adding another bounded inline
  extent when the adjacent blocks are already used. Bundled userland includes
  `/bin/write.kx` with `write <path> <payload>` and
  `write --append <path> <payload>` smoke coverage.
- K16 translated `BRK`/`SBRK` now commit heap pages through the kernel page
  allocator when the program break crosses a previously unmapped 4 KiB VM page
  boundary. Launch-time mappings cover only the loaded image pages and the
  initial committed user stack pages; process-owned heap backing pages are
  released on `EXIT` together with the translated address space backing.
- K16 now launches `/bin/init.kx` itself in a host-managed translated address
  space. The kernel reserves the top 4 KiB page below `BootInfo.ram_size` as
  init's physical kernel trap stack, starts init's user arena at the next 4 KiB
  page after `BootInfo.program_base`, storage loader scratch, kernel image, and
  kernel terminal state, and enters init through `mmu0`
  `activate_user_address_space` instead of physical `enter_loaded_image`.
- Added `mmu0` `destroy_address_space` (`9`) and wired the K16 kernel process
  lifecycle to destroy a translated child address space on `EXIT` before
  returning to the blocked parent frame. This keeps translated shell/utility
  runs from accumulating stale host-managed address spaces.
- Added bundled `/bin/stat.kx` as the first user-facing `STAT` syscall
  utility. The shell now lists `STAT <PATH...>` in help, and `stat` prints
  stable `FILE <size> <path>`, `DIR <size> <path>`, or `ERR <status> <path>`
  lines using `kraft-std::fs::metadata`.
- `K16SNAP` now records translated execution state needed to restore
  VM-enabled user processes. Snapshot device record kind `9` stores `mmu0`
  address-space mappings plus per-CPU address/privilege mode overrides, so a
  snapshot taken while `/bin/shell.kx` or a nested utility is running in
  translated user mode can resume after restore instead of rebooting into a
  broken physical-mode continuation.
- K16 production `RUN` now launches `/bin/shell.kx` and shell-started
  `/bin/*.kx` utility children in host-managed translated address spaces. The
  first production mappings are identity-mapped over kernel-selected child
  backing ranges, and the current foreground depth supports translated init ->
  translated shell -> translated utility execution. Added `mmu0`
  `set_trap_return_address_space`, which takes a parent address-space id plus a
  physical kernel trap stack pointer, so `EXIT` from a translated utility can
  resume a translated shell frame through `iret`. The existing
  `set_trap_return_physical` command remains for compatibility with older
  physical-parent paths.
- Added K16 syscall `GAME_TICKS` (`16`) so translated userland reads the
  current timer tick through the kernel instead of direct `timer0` MMIO.
  `k16_rt::game_ticks_syscall(out)` writes the 8-byte little-endian
  `{ low, high }` tick value to a user buffer, and `kraft-std::time` exposes
  this as a `Result` so syscall errors are reported explicitly.
- K16 translated child activation now restores the saved child trap register
  frame before entering user-translated mode, so argv and other entry
  registers survive the `mmu0` activation boundary. The launcher also reserves
  a dedicated physical kernel trap stack below the physical parent stack and
  passes it through `mmu0` `activate_user_address_space` using
  `physical_start`.
- K16 trap entry from translated user execution now switches the live stack
  pointer to the physical kernel stack captured by `mmu0`
  `activate_user_address_space`, while `iret` still restores the interrupted
  user stack through `trap_stack_pointer`. `K16SNAP` CPU records now include
  `trap_kernel_stack_pointer`, increasing the fixed K16 CPU record size from
  204 to 208 bytes.
- K16 syscall handlers now route user-buffer copies through a shared kernel
  helper. Physical foreground processes keep the existing process-range
  validation and physical RAM copies, while VM-enabled processes use the
  `mmu0` `copy_from_user`/`copy_to_user` commands for `WRITE`, `READ`,
  `OPEN`, `RUN`, `READ_DIR`, `STAT`, and stdin/file output buffers. Failed
  translated copies return the existing negative K16 `ERROR_FAULT` status.
- Added K16 `mmu0` `copy_from_user` and `copy_to_user` commands for physical
  kernel syscall handlers. They copy bytes between translated user virtual
  memory and physical kernel buffers, report deterministic `mmu0` errors for
  invalid address spaces, translation faults, physical bounds failures, and
  byte-count overflow, and return the copied byte count in `result`.
- K16 trap, syscall, fault, and interrupt entry now save the interrupted
  address/privilege mode, enter the trap vector in physical/kernel mode, and
  restore the interrupted mode on `iret`. This lets translated user code call a
  physical-mode kernel handler and resume translated execution afterward.
- Added the K16 `mmu0` MMIO control device as the first privileged guest
  interface to the host-managed MMU. Kernel-mode guest code can create address
  spaces, map/protect pages, and activate translated user execution for the
  current K16 CPU while physical boot remains the default.
- Added the planned K16 virtual-memory/address-space ABI direction. The current
  VM remains physical-memory-only, but the documented direction is a small
  user-translated process model: physical BIOS/boot/kernel execution, 4 KiB
  user pages, host-managed address-space maps, page-fault traps, and explicit
  kernel copy helpers for user syscall buffers.
- K16 host tooling no longer caps bootloader and kernel `K16E` payloads at the
  next fixed-image load base. Their fixed load bases remain unchanged, loaders
  still validate encoded address ranges, and the fixed-image
  `program`/`program-dynamic` targets remain bounded by the current program
  stack top. The old kernel payload budget/headroom script is now a kernel
  payload inspector.
- K16 host tooling now uses a 512 KiB standalone program RAM/stack profile and no
  longer applies the fixed-image payload cap to `program-dynamic` artifacts.
  Dynamic user programs remain base-relative and are still range-checked by the
  loader/runtime that chooses their concrete arena.
- The K16 Rust target spec now identifies the OS as `kraftos` instead of using
  `target_os = "none"` with a KraftOS environment marker. KraftOS-specific
  `std` platform hooks should key off `target_os = "kraftos"`.
- The K16 kernel now derives init's upper process memory boundary from profile
  v2 `BootInfo.ram_size` instead of a kernel-local machine-profile constant.
  Boot-info validation rejects missing or too-small RAM boundaries before
  entering userland. The boot chain still uses physical memory only; this does
  not add virtual memory or page tables.
- K16 foreground processes now carry explicit guest-physical memory ranges.
  Init derives its range from the loaded image plus the boot-provided RAM
  boundary, children derive theirs from the selected load base and
  stack top, and syscall/stdin user-pointer validation checks the current
  foreground process range instead of a global hard-coded user window. This is
  still physical memory only; it does not add virtual memory or page tables.
- The bundled K16 shell now resolves executable command names containing `/`
  through the current working directory. Bare commands still launch
  `/bin/<name>.kx`, while explicit paths such as `/bin/uname.kx` and
  `./uname.kx` run without adding another `/bin/` prefix or `.kx` suffix.
- Added read-only K16 path metadata syscall `STAT(path, len, metadata_ptr)`.
  The kernel resolves absolute ROOT/K16FS paths on `storage0`, returns
  `FILE_TYPE_REGULAR` or `FILE_TYPE_DIRECTORY` plus `size_bytes` in a fixed
  16-byte response buffer, and maps missing paths to the existing negative K16
  error statuses. `kraft-std::fs::metadata` exposes this to userland, and
  bundled `/bin/ls.kx` uses it to mark directories with `/`.
- Regular K16 filesystem descriptors are now owned by the foreground process
  that opened them. `READ` and `CLOSE` reject regular fds owned by another
  process, and `EXIT` releases only the exiting process's regular fds before
  resuming its waiting parent. Stdio descriptors `0`, `1`, and `2` remain a
  shared convention rather than entries in the regular fd table.
- Added a bounded K16 foreground process table. `RUN` now blocks the current
  foreground process rather than a hard-coded init slot, so bundled init can
  launch `/bin/shell.kx` and the shell can synchronously run utilities such as
  `/bin/uname.kx`, `/bin/cat.kx`, and `/bin/alloc-test.kx`. `EXIT` resumes the
  waiting parent frame with the child status in `r0`. This remains cooperative:
  there is no scheduler, fork, pipe, or virtual-memory isolation.
- Split bundled init from the interactive shell. `/bin/init.kx` is now a small
  supervisor that spawns `/bin/shell.kx`, waits for it, restarts clean shell
  exits, and propagates non-zero shell status or launch/wait failures; the
  interactive shell is packaged separately as `/bin/shell.kx`.
- Added bounded-argv K16 child process launches. `RUN(request, len, 1)` accepts
  a bounded request block containing up to four argv byte strings. The kernel
  copies argv entries into the child arena before the heap, enters the child
  with `argc` in `r1` and the argv table pointer in `r2`, and `kraft-std`
  exposes `process::run_with_args` plus a small `process::Argv` reader.
  Bundled `cat` and `ls` now consume every supplied path argv entry in order,
  and the bundled shell tokenizes external commands into whitespace-separated
  argv entries while resolving each `cat`/`ls` path argument against the
  current directory.
- Added K16 userland heap syscalls: `BRK(addr)` sets the current foreground
  program break, and `SBRK(delta)` grows it and returns the previous break.
  The Rust kernel bounds the heap to the process arena below the saved parent
  stack guard and returns `ERROR_NO_MEMORY` for out-of-range growth.
  `kraft-std` now exposes `heap::{brk, sbrk}` and installs a guest
  `SbrkAllocator`, while bundled userland includes `/bin/alloc-test.kx` as an
  `alloc::Vec` smoke utility reachable from init through the `alloc` command.
- The init userland program now has its own `BRK`/`SBRK` heap, and child
  program load arenas start after init's current program break. The init shell
  input line is backed by `alloc::Vec`, so normal line editing is no longer
  capped by the old 128-byte fixed buffer; allocation failure rejects the next
  printable byte without dispatching a partial command.
- K16 dynamic user-program relocation staging no longer reuses the storage
  scratch buffer. Relocation records can straddle K16FS block boundaries, and
  reading such a record into the same scratch page used by block staging could
  corrupt the first half of the copied record before relocation parsing.
- Added read-only K16 filesystem fd syscalls for ROOT/K16FS on `storage0`:
  `OPEN(path, len, flags)` allocates file descriptors starting at `3`,
  `READ(fd, ptr, len)` now reads both stdin and regular files, and
  `CLOSE(fd)` releases regular file descriptors. `kraft-std::fs` exposes the
  initial `open`, `File::read`, and `File::close` surface, and bundled
  userland now includes `/bin/cat.kx` as the first file-reading utility.
- `k16 runtime k16-memory-helpers` now exports freestanding `memcpy`,
  `memset`, and `memmove` aliases in addition to the existing `__k16_*`
  symbols, so Rust-generated aggregate copies can link without relying on
  synthesized helper symbols.
- Added the K16 `RUN(path, len)` syscall for init-owned child process launch
  from `/bin/*.kx`, with negative K16 error status values for invalid paths,
  missing entries, invalid executables, no memory, and busy child state. The
  active fixed-image program profile now starts at `0x00015000`, and the
  default K16 VM RAM profile is 192 KiB with the first user stack top at
  `0x00030000`, so the kernel has room for the dynamic-loader path before the
  first user program without shrinking the foreground child arena.
- K16E now has a v2 dynamic user-program extension for future kernel-selected
  userland load addresses. Dynamic `program` artifacts store an entry offset,
  load payload, memory size, and relocation table instead of a fixed physical
  `load_addr`; `k16 link --target program-dynamic` emits this format, while
  `k16 runtime k16-startup --target program-dynamic` emits startup code that
  uses the kernel-provided `r15` stack top. Fixed-image K16E v1 remains valid
  for bootloader, kernel, and standalone tool paths.
- LLVM K16 lowering now reserves outgoing stack-argument space in the function
  call frame instead of moving `sp` around each call. This keeps incoming stack
  arguments stable while a callee prepares another call, fixing direct Rust
  `u64`/`i64` div/rem and 64-bit shift helper calls through
  `k16-memory-helpers`.
- The bundled `/bin/init.kx` now owns the minimal interactive shell proof over
  fd stdin/stdout (`help`, `clear`, `echo`, `ticks`, and prompt handling). The
  kernel no longer carries the legacy shell/line/keyboard command dispatcher;
  it keeps terminal, driver, syscall, and init-loading responsibilities.
- `kraft-std` now exposes `time::game_ticks()` and `time::game_ticks_parts()`
  for userland programs. The init `ticks` command formats full-width timer0
  game ticks without relying on 64-bit division.
- The K16 terminal treats form feed (`0x0c`) as a clear-screen control byte, so
  userland programs can request terminal clear through stdout.
- `k16 disasm` now accepts `--start <pc>` and `--count <instructions>` for
  strict PC-window disassembly. This keeps current `K16E` inspection useful
  when linked payloads contain code plus data/rodata and the executable format
  does not carry `.text` boundaries.
- K16E single-load sections now allow `memory_size >= file_size`. Loaders copy
  the file payload and zero-fill the tail, which lets the object linker
  represent trailing `.bss` without serializing zero bytes into the file.
- K16 trap entry now saves the interrupted register frame. `iret` restores
  `r1..r15`, keeps the handler's current `r0` as the caller-visible return
  value, and restores the saved interrupt-enable state. `K16SNAP` CPU records
  now include the saved trap register frame so snapshots taken inside a trap can
  resume through `iret`.
- `k16-cpu-helpers` now emits fixed-number `READ`/`WRITE` fd syscall helpers
  that pass `fd`, `ptr`, and `len` without relying on a stack-passed fourth
  Rust argument and preserve the scratch register around the trap boundary.
  `kraft-std` fd methods are now normal cross-crate methods; the K16 Rust smoke
  suite verifies `Result`-returning fd reads and writes through `kraft-std`
  without relying on an inline-only ABI guard.
- Added `READ(fd, ptr, len)` to the K16 fd syscall ABI for `FD_STDIN`.
  The Rust kernel blocks by waiting for the host until keyboard input is
  available, copies bytes into validated user-program memory, and returns the
  byte count or a negative K16 error.
- Added POSIX-aware K16 fd syscall ABI primitives: `EXIT(status)` and
  `WRITE(fd, ptr, len)`, with reserved descriptors `0` stdin, `1` stdout, and
  `2` stderr.
- Extended K16 syscall argument capture to `trap_arg0..trap_arg2` and added
  `k16-rt::syscall3`.
- Updated K16 program startup to terminate through `EXIT` instead of debug
  MMIO.
- Added K16 `wait` (`0x0006`) as a resumable, non-terminal CPU signal and
  exposed it through `k16-rt::wait_once()`. The Rust kernel idle loop drains
  keyboard input and then waits instead of busy-yielding directly.
- Added K16 computer-profile `keyboard0` as a PC-like MMIO event queue at
  `0x1000_0700` with hardware id `8` and interrupt source `0x00000002`.
  It delivers trap cause `0x80000002`; the host-side Rust VM now snapshots
  pending keyboard events separately from the existing UART-style
  `serial-input` byte queue.
- Added `k16_abi::syscall::SLEEP_TICKS` and the matching `k16-rt`
  `sleep_ticks_syscall(ticks)` wrapper. The Rust kernel implements the first
  single-task blocking sleep syscall by waiting until `timer0.game_ticks`
  reaches the target tick. The BIOS splash now uses `k16_rt::sleep_ticks(20)`
  instead of a plain yield boundary.
- `iret` now defers pending interrupt delivery for two resumed guest
  instructions after returning. This lets returning syscall helpers expose `r0`
  to the caller before a pending timer interrupt can enter the trap vector.
- Added `k16_abi::syscall::YIELD` and the matching `k16-rt`
  `yield_syscall()` wrapper. The Rust kernel yields once to the host while
  handling the syscall, then returns `STATUS_OK` through `iret`.
- Named the current K16 syscall ABI v0 proof services in `k16_abi::syscall`
  and exposed `k16-rt` wrappers for `debug_marker()` and
  `debug_write_byte(byte)`.
- Added K16 syscall argument capture for `syscall1(number, arg0) -> u32`.
  `syscall` now captures guest `r2` into the read-only `trap_arg0` CSR, and the
  Rust kernel uses it for the first debug-write-byte syscall proof.
- Defined K16 syscall ABI v0 for the first Rust kernel services. `k16-rt`
  exposes a returning `syscall0(number) -> u32` helper, the kernel returns
  through `r0`, and `k16-cpu-helpers` provides `__k16_iret_with_r0` for
  explicit kernel-side syscall returns.
- Added K16 `syscall rA` as a returning explicit-trap entry. It records the
  next PC in `trap_pc`, stores the syscall number from `rA` in `trap_value`,
  disables interrupt delivery during the handler, and resumes through `iret`.
- K16 profile v2 hardware table entries now include `irq_source` as a fourth
  `u32` field. `timer0` advertises source bit `0x00000001`; non-interrupting
  devices advertise `0`.
- Added K16 CPU timer interrupt foundation: interrupt enable/mask/pending
  CSRs, `iret`, and `timer0` pending delivery on host game-tick advance.
  `K16SNAP` CPU records now preserve interrupt CSR state and pending timer0
  interrupt values.
- Added `timer0` to K16 computer profile v1. It exposes `game_ticks` for
  Minecraft/server simulation time and `monotonic_nanos` for host monotonic
  elapsed-time diagnostics. `game_ticks` is included in `K16SNAP`; the host
  monotonic origin is intentionally recreated on restore.
- Added `control + 0x0c` as a guest-visible `yield` request register. A
  non-zero write pauses the current VM tick after the current instruction and
  resumes execution on the next host tick without changing durable control
  snapshot state.
- Added `gpu0` to K16 computer profile v1 as a guest-visible RGB565
  pixel MMIO device. Guest code can blit little-endian RGB565 rectangles from
  RAM and explicitly present dirty tiles through the existing display-frame
  path.
- `k16 link` now performs reachability-based alloc section GC from `_start`,
  and K16 LLVM honors function sections as `.text.k16.<symbol>` so Rust
  `core`/`compiler_builtins` code that is not reached by firmware is not copied
  into VM payloads.
- Added `k16-ld`, a Rust/Cargo linker-driver entry point for K16 `bin`
  artifacts. It accepts rustc-style linker arguments with explicit
  `--k16-target`, expands K16 ELF members from `.rlib` archives on demand, and
  rejects missing targets or unsupported linker flags without falling back to
  host linker behavior.
- NeoForge bundled BIOS generation now builds `rust/guest/k16-bios` as a Rust
  `bin` crate through `k16-ld`, so the `.kflash` is the linker output rather
  than an object-only Cargo emission followed by a separate Gradle link step.
- NeoForge bundled bootloader and kernel generation now build
  `rust/guest/k16-boot` and `rust/guest/k16-kernel` as Rust `bin` crates
  through `k16-ld --k16-target=boot|kernel`. The bundled guest firmware path no
  longer emits intermediate objects through Gradle for BIOS, bootloader, or
  kernel artifacts.
- Bundled K16 firmware builds now resolve the K16 Rust toolchain through the
  shared Gradle `prepareK16Toolchain` path. Source-built prepared toolchains
  stage the K16 stage1 rustc, `k16-ld`, Rust source, and matching host
  `library/std` sysroot artifacts so Cargo can compile host build scripts while
  building freestanding K16 `core`.
- Cargo-built K16 Rust bins now link `k16-rt` explicitly for freestanding
  platform symbols such as `abort`, while Rust `compiler_builtins` remains the
  provider for compiler arithmetic helper symbols pulled from `.rlib` archives.
- `k16 link --target bios` now emits raw linked K16 BIOS flash bytes from K16
  object inputs with a reset-address trampoline that initializes `sp` and jumps
  to `_start`, so Rust-authored BIOS firmware has a host-tool path to `.kflash`
  without the retired Rux compiler.
- NeoForge bundled BIOS generation now points at `rust/guest/k16-bios`; missing
  prepared Rust BIOS toolchain state is a hard build error, not a fallback to
  deleted `.rx` sources.
- Added guest-owned Rust bootloader and kernel crate scaffolds under
  `rust/guest/k16-boot` and `rust/guest/k16-kernel`. NeoForge boot/kernel
  artifact generation now uses the same prepared K16 Rust toolchain path, with
  missing toolchain state reported as a hard guest Rust build error.
- The public `rux` CLI surface and active Rux compiler/frontend sources are
  retired. K16 artifact work stays under `k16`, and guest-owned source belongs
  under `rust/guest`.
- `k16 runtime k16-memory-helpers` now compiles its helper source from
  `rust/guest/k16-rt`, so host tooling no longer owns guest runtime code.
- `k16 runtime k16-cpu-helpers` now provides explicit K16 CPU helper symbols
  for halt, yield, CSR access, and interrupt return used by `k16-rt`.
- `k16 runtime k16-memory-helpers` now owns the first integer compiler-rt
  helper implementations: `__divdi3`, `__udivdi3`, `__moddi3`, and
  `__umoddi3`.
- `k16 runtime k16-memory-helpers` now owns the 64-bit shift compiler-rt
  helpers: `__ashldi3`, `__lshrdi3`, and `__ashrdi3`.
- LLVM K16 lowering now maps wide integer div/rem and soft-float operations to
  explicit compiler-rt helper symbols, including i64/i128 div/rem, f32/f64
  arithmetic and comparisons, f16/f32/f64 conversion helpers, and f32/f64
  integer conversion helpers. Helper implementations remain runtime object and
  link-time requirements.
- LLVM K16 lowering now supports indirect calls through the existing
  register-target `call rN` instruction and expands i32 byte-swap/rotate
  operations into regular K16 shifts and bitwise ops. Rust `core` now advances
  to the next integer runtime/libcall legalization blocker.
- LLVM K16 lowering now covers the next Rust `core` prerequisites: memory
  helper libcalls, i32 div/rem libcalls, logical right shift, non-strict integer
  comparisons, signed narrow loads, bit-count expansion, i64 shift-parts,
  switch lowering without jump tables, and branch insertion/removal for branch
  folding.
- LLVM K16 lowering now materializes global and external symbol addresses with
  `const32` plus `R_K16_ABS32` relocations.
- LLVM K16 lowering now supports branchless scalar `select` from compare
  results plus `load16`/`store16` instruction selection and object emission.
- LLVM K16 lowering now emits caller-cleaned stack arguments after `r1..r3`,
  matching the documented external call ABI.
- The LLVM-facing call ABI now supports up to four scalar `i32` return values
  in `r0..r3`, matching small Rust/LLVM multi-value returns without introducing
  hidden return pointers.
- Added K16 `mul` as canonical ALU subop `0xc`, with VM execution,
  disassembly, assembler helper, and LLVM lowering/emission support.
- Added K16 `mulh_u` and `mulh_s` as canonical ALU subops `0xd` and `0xe`
  for high-half integer multiply and LLVM `*_lohi` lowering support.
- `k16 disasm` now validates K16 instruction encodings against the active VM
  decode rules and fails clearly on reserved bits, unknown opcodes, and
  truncated multi-word instructions instead of printing `.word` fallback lines.
  K16 assembler helpers now cover both zero and non-zero branch predicates.
- Added `k16 runtime k16-startup`, which emits the first freestanding K16
  startup object. It defines `_start`, calls application `main`, initializes the
  program stack, writes the low byte of `main`'s `r0` return value to
  `debug::WRITE`, and leaves reserved helper symbols such as `__k16_memcpy`
  as explicit link-time requirements instead of fallback VM hooks.
- `k16 runtime k16-memory-helpers` now builds the helper object from bundled
  Rust `#![no_core]` source with `K16_RUSTC` and K16 `llc` from
  `K16_LLVM_BIN_DIR`. It defines `__k16_memcpy`, `__k16_memset`, and
  `__k16_memmove`; programs still pass this object to `k16 link`, and the
  linker still rejects missing helper symbols instead of synthesizing hidden
  bodies.
- Added `k16 link`, a static object-to-`K16E` linker for the experimental
  K16 ELF32 `ET_REL` object ABI. It emits bootloader, kernel, or program
  `K16E` images and rejects unsupported allocated sections and relocation kinds
  without falling back to raw K16 bytes or VM-side relocation.
- Added `k16-object-v1.md` as the experimental ELF32 `ET_REL` relocatable
  object ABI for LLVM-facing K16 tooling, including section names, symbol
  rules, relocation kinds, unsupported feature diagnostics, and the boundary
  that keeps ELF parsing/linking outside the VM.
- Defined the implementation-ready K16 calling convention for external
  LLVM-facing lowering, including scalar ABI slots, caller-saved registers,
  stack argument layout, frame-pointer offsets, caller cleanup, and the current
  Rux compiler boundary that still rejects helper calls needing stack-passed
  arguments.
- Replaced the experimental K16 integer ALU encoding with the canonical
  two-word `alu_rrr` format `0x2a0s 0x00bc`, covering `add`, `sub`, bitwise
  ops, shifts, equality, inequality, unsigned less-than, and signed less-than.
  Added `load16` and `store16` to the memory width encoding.
- Documented the initial LLVM-facing K16 target model, including the
  `k16-unknown-kraftos` shape, register classification, caller-saved model,
  stack-passed argument boundary, required integer ISA families, and the
  object-to-`K16E` executable pipeline. The boundary explicitly keeps LLVM
  backend/toolchain concerns outside the VM implementation.
- Added experimental `K16SNAP` v1 as the host-side `ComputerMachine` snapshot
  container. It records a versioned header, full RAM bytes, and K16 CPU
  continuation records; `control`, `debug`, serial input device state,
  `storage0` controller registers, `timer0` game ticks, and pending
  `keyboard0` events are now restored. The retired host-owned text display
  surface is not part of active snapshots.
- Added `k16-cpu-v1.md` and reserved `r15` as the K16 stack pointer. The stack
  lives in guest RAM, grows downward, and uses 4-byte slots in the first ABI
  slice.
- Added K16 `call rN` and `ret` instructions backed by the `r15` stack
  pointer convention.
- The K16 compiler now saves and restores live local registers around
  compiler-generated helper calls that use `call`/`ret`.
- K16 helper parameters now enter through `r1..r3` and are copied into stable
  callee-local storage before helper body lowering, so scratch register use does
  not clobber parameters.
- Added experimental `K16E` v1 as the guest-loadable fixed-image container for
  K16 bootloader and kernel artifacts.
- `K16E` now carries an ABI kind: `bootloader` or `kernel`.
- `rux compile` now emits `K16E` for `boot`, `kernel`, and `program` targets.
  Explicit `bios` continues to emit raw K16 instruction bytes for BIOS flash.
- `K16E` now carries ABI kind `program`, and the program target emits a
  filesystem-backed user-space executable profile linked at `0x8000`.
- The VM runtime now has a read-only `storage0 media -> K16PT ROOT -> K16FS`
  reader and validates already-read `K16E` bytes as `program` images for the
  future OS exec path.
- The runtime `K16ComputerHandle` can transfer an already-read `K16E` program
  into guest RAM and start K16 execution at the executable `entry_pc`.
- Added a guest-side kernel init loader that reads `/bin/init.kx` from
  `storage0` `ROOT`/K16FS, validates `K16E` ABI kind `program`, loads the
  payload, and enters the program `entry_pc`.
- The bundled BIOS now loads `/boot/loader.kb` from the `BOOT` K16FS
  partition in the partitioned boot path, validates `K16E` ABI kind
  `bootloader`, and enters the bootloader `entry_pc`.
- `k16 volume put-boot` now accepts a `K16E` boot artifact and writes the
  bootloader file to `BOOT`/K16FS `/boot/loader.kb` for partitioned volumes.
  Kernel artifacts are rejected for boot media.
- Added `k16 volume put-kernel`, which writes the kernel `K16E` file to
  `ROOT`/K16FS `/boot/kernel.kx` for the bootloader-to-kernel chain.
- Retired the legacy fixed `K16B` raw boot path from active BIOS and
  `put-boot` behavior. Partitioned `K16PT` plus K16FS is now the only supported
  boot path.
- Added `k16 volume init`, which creates a partitioned `K16PT` volume with
  `BOOT` and `ROOT` partitions for the next filesystem-backed boot chain.
- Added byte-level `k16 volume extract-partition` and `replace-partition`
  commands for moving partition images in and out of `K16PT` volumes without
  filesystem-specific logic.
- Added `k16 volume inspect`, which prints the `K16VOL` header summary and
  decoded `K16PT` partition layout.
- Added CLI workflow coverage for building a partitioned `storage0.kv`
  with a K16FS `ROOT` partition containing `/boot/kernel.kx`.
- Added a host-side K16FS volume reader that models the future bootloader read
  path from `K16PT` `ROOT` to `/boot/kernel.kx`.
- Added experimental `K16FS` v1 as the extent-based filesystem contract for
  the partitioned `ROOT` partition, with empty formatting and structural
  validation in compiler tooling.
- `K16FS` tooling now supports fixed-size directory entries, absolute-path
  directory creation, file creation, file reads, and directory listing over an
  in-memory filesystem image.
- Added `k16 fs kfs`, keeping filesystem-specific commands separate from
  `k16 volume` so additional filesystems can be introduced explicitly.
- Added `k16-storage-volume-v1.md` for the earlier fixed-record `K16VOL`,
  `K16B`, and `K16K` storage0 media layout.
- Retired the previous host-decoded executable ABI package from active
  documentation.
- Removed the obsolete decoder, runner, disassembler, conformance examples,
  and fixture tests from `native/rux-vm`.
- Moved the active runtime contract to K16 guest execution from mapped
  BIOS flash with optional storage0 boot media.
- Kept machine profile v2 and computer profile v1 as the active guest-visible
  hardware contracts.

## Current Active Contracts

- `k16-machine-profile-v2.md`
- `k16-computer-profile-v1.md`
- `k16-cpu-v1.md`
- `k16-object-v1.md`
- `k16e-v1.md`
- `k16-storage-volume-v1.md`
- `k16fs-v1.md`
- `k16-computer-snapshot-v1.md`
