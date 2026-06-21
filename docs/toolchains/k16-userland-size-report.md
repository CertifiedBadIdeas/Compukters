# K16 Userland Size Report

K16 userland programs are linked with retained-section maps beside each bundled
`.kx` artifact. The development size report compares those maps and highlights
contributors that are retained in more than one production userland program.

Run the report with:

```bash
./gradlew-sandbox-dev :v1_21_1-neoforge:reportK16UserlandSize
```

The task depends on the production userland compile tasks and reports only the
bundled init, shell, and core utility maps. It intentionally excludes hosted
proof programs, development test binaries, and fault fixtures.

The report has two sections:

- `program`: per-program `payload_bytes`, `memory_bytes`, retained section
  count, and map-derived program name.
- `duplicate_file_bytes`: retained section contributors that appear in at least
  two programs, sorted by duplicated file bytes. These rows are the first input
  for deciding what belongs in a future shared runtime or shared library.
