# Rux Low ABI Fixtures

Each fixture pair contains:

- `<name>.ruxi`: serialized Rux low image bytes;
- `<name>.json`: manifest with fixture kind and expected result.

Golden fixtures must decode, validate, run, and produce the manifest's expected halt signal.

Negative fixtures are intentionally invalid. Their manifest names the phase that must reject them:

- `decode`: byte stream cannot be decoded as a v1 image;
- `validation`: byte stream decodes, but the executable image violates VM invariants.

The reference generator is:

```text
native/rux-vm/examples/write_abi_fixtures.rs
```

Regenerate fixtures with:

```bash
cargo run --example write_abi_fixtures
```

from `native/rux-vm`.
