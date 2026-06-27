# K16 Userland Size Report

K16 userland programs and shared runtime objects are linked with retained-section
maps beside each bundled artifact. The development size report compares those
maps and highlights contributors that are retained in more than one production
userland program. It also prints the generated production and development
storage image sizes so shared-runtime work can be measured against the current
packaging baseline.

Run the report with:

```bash
./gradlew-sandbox-dev --parallel :v1_21_1-neoforge:reportK16UserlandSize -Pk16BuildJobs=32
```

The task depends on the production and development storage image tasks. It
reports the generated production image, the generated development image, and the
development-minus-production byte delta. These `.kv` image files are currently
fixed-size volume containers, so this delta is expected to be `0` until the
volume sizing policy changes:

```text
storage_image name=production bytes=<bytes> path=<.../k16-system-storage0.kv>
storage_image name=development bytes=<bytes> path=<.../k16-system-storage0-dev.kv>
storage_image_delta name=development_minus_production bytes=<bytes>
```

The useful packaging delta is the installed-entry total. These rows sum the
host artifact bytes that are written into the K16FS ROOT partition:

```text
storage_entries group=production files=<count> bytes=<bytes>
storage_entries group=shared_libraries files=<count> bytes=<bytes>
storage_entries group=development_only files=<count> bytes=<bytes>
storage_entries group=development_total files=<count> bytes=<bytes>
```

After the storage lines, the task runs the retained-section map report for three
explicit artifact groups:

- `map_section name=production_userland`: bundled init, shell, and production
  core utility maps.
- `map_section name=shared_libraries`: shared userland library maps under
  `/lib`, currently including `libkraft.kso`.
- `map_section name=development_only`: development-only proof program maps such
  as `alloc-test` and `proc-test`.

`libkraft.kso` is the first project-owned userland shared OS ABI boundary.
It exports explicitly prefixed syscall-shaped symbols such as `kraft_sys_open`,
`kraft_sys_read`, `kraft_sys_write`, `kraft_sys_close`,
`kraft_sys_read_dir`, `kraft_sys_stat`, `kraft_sys_rename`,
`kraft_sys_mkdir`, `kraft_sys_rmdir`, `kraft_sys_unlink`,
`kraft_sys_sbrk`, and `kraft_sys_exit`; `kraft-std` remains the Rust
convenience layer above that boundary.

Production `/bin/uname.kx`, `/bin/cat.kx`, `/bin/write.kx`, `/bin/rm.kx`,
`/bin/mkdir.kx`, `/bin/rmdir.kx`, `/bin/stat.kx`, `/bin/ls.kx`, `/bin/cp.kx`,
and `/bin/mv.kx` are built from C with the source-built-dev K16 `clang`, use
the small libc-lite startup/header layer under `guest/c/libc`, and call the same
`libkraft.kso` shared OS ABI as the Rust import proofs.
Their K16 CPU/syscall helper surface comes from the checked-in
`guest/c/arch/k16/cpu-helpers.kasm` source runtime assembled by `k16 asm`, not
from host-generated helper text.
`/bin/uname.kx` remains the smallest bundled importer: it imports `write`
through K16E import metadata instead of retaining that syscall-boundary call in
its own payload. The public libc-lite surface now includes minimal `unistd.h`,
`fcntl.h`, `stddef.h`, `string.h`, and `kraft/fs.h` headers, while
`kraft/syscalls.h` remains the low-level K16 ABI header. Public C calls whose
arguments differ from the syscall-shaped shared exports use `kraft_*` wrappers
and macros, so source can call `stat(path, &metadata)` while the dynamic import
still resolves to `libkraft`'s `kraft_sys_stat(path, len, metadata)` export.
The same pattern lets C source call `rename(old_path, new_path)` while importing
the structured `kraft_sys_rename(request, len)` shared export. This proves the
dynamic ABI can host C userland without making `libkraft` a Rust stdlib
replacement or pulling in a full libc.

Each map section has two `k16 size-report` subsections:

- `program`: per-program `payload_bytes`, `memory_bytes`, retained section
  count, and map-derived program name.
- `duplicate_file_bytes`: retained section contributors that appear in at least
  two programs, sorted by duplicated file bytes. These rows are the first input
  for deciding what belongs in a future shared runtime or shared library.

Fault fixtures and hosted examples that are not installed into either bundled
storage image are intentionally excluded from this report.
