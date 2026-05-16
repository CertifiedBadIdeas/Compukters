# Rux Low ABI Fixtures

Each fixture pair contains:

- `<name>.ruxi`: serialized Rux low image bytes;
- `<name>.json`: manifest with fixture kind and expected result.

Golden fixtures must decode, validate, run, and produce the manifest's expected halt signal.

Negative fixtures are intentionally invalid. Their manifest names the phase that must reject them:

- `decode`: byte stream cannot be decoded as a v1 image;
- `validation`: byte stream decodes, but the executable image violates VM invariants.

Runtime-error fixtures are valid images that must decode and validate successfully, then trap while running:

- `runtime`: execution reaches a VM error such as divide by zero, memory fault, or call/return mismatch.

## Running Fixtures With `rux`

The fixture manifests describe the reference ABI behavior. The reference conformance runner executes each fixture with the machine memory size required by that fixture.

The repository-level `./rux run` helper is a developer runner. By default it creates a computer machine with `64 KiB` of RAM:

```bash
./rux run docs/abi/fixtures/minimal_return_i32.ruxi
```

For memory-fault fixtures, pass the same machine RAM size that the fixture was written for. For example, `runtime_memory_out_of_bounds.ruxi` requests an access at `1022..1026` and is intended to run against `1024` bytes of RAM:

```bash
./rux run docs/abi/fixtures/runtime_memory_out_of_bounds.ruxi --memory 1024
```

Without `--memory 1024`, the default `64 KiB` developer machine has enough RAM for that access, so the image can halt normally instead of trapping.

The reference generator is:

```text
native/rux-vm/examples/write_abi_fixtures.rs
```

Regenerate fixtures with:

```bash
cargo run --example write_abi_fixtures
```

from `native/rux-vm`.

Run the reference conformance check with:

```bash
cargo run --example rux_abi_conformance
```

from `native/rux-vm`.
