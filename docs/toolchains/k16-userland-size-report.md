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
storage_entries group=shared_runtime files=<count> bytes=<bytes>
storage_entries group=development_only files=<count> bytes=<bytes>
storage_entries group=development_total files=<count> bytes=<bytes>
```

After the storage lines, the task runs the retained-section map report for three
explicit artifact groups:

- `map_section name=production_userland`: bundled init, shell, and production
  core utility maps.
- `map_section name=shared_runtime`: shared runtime object maps under `/lib`.
- `map_section name=development_only`: development-only smoke/proof program
  maps such as `alloc-test`, `proc-test`, `shared-runtime-test`, and
  `hosted-cat`.

Each map section has two `k16 size-report` subsections:

- `program`: per-program `payload_bytes`, `memory_bytes`, retained section
  count, and map-derived program name.
- `duplicate_file_bytes`: retained section contributors that appear in at least
  two programs, sorted by duplicated file bytes. These rows are the first input
  for deciding what belongs in a future shared runtime or shared library.

Fault fixtures and hosted examples that are not installed into either bundled
storage image are intentionally excluded from this report.
