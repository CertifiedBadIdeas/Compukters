# Kraft16 VM Microbenchmark Baseline - 2026-05-29

> Issue: [#115](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/115)

## Scope

This baseline records local release-mode Kraft16 VM microbenchmark results after
the `mmio-loop` workload was added in `fcaea5ba`.

The numbers are diagnostic comparison points for future VM optimization work.
They are not portable performance claims and they are not CI pass/fail budgets.

## Environment

- Commit: `fcaea5ba`
- OS: `Linux 7.0.10-zen1-1-zen x86_64 GNU/Linux`
- CPU: `AMD Ryzen 7 7700 8-Core Processor`
- Rust: `rustc 1.94.1 (e408947bf 2026-03-25)`
- Cargo: `cargo 1.94.1 (29ea6fb6a 2026-03-24)`

## Command

From `native/k16-vm`:

```bash
cargo run --release --example vm_microbenchmarks -- <iterations> <samples>
```

This run used:

- `1000 3`
- `10000 5`
- `100000 5`

## Results

```text
workload	vm	iterations	checksum	best_nanos	nanos_per_iteration
compute-loop	k16	1000	1000	61730	61.730
memory-loop	k16	1000	1000	103359	103.359
mmio-loop	k16	1000	1000	101100	101.100

workload	vm	iterations	checksum	best_nanos	nanos_per_iteration
compute-loop	k16	10000	10000	613949	61.395
memory-loop	k16	10000	10000	1029397	102.940
mmio-loop	k16	10000	10000	1007878	100.788

workload	vm	iterations	checksum	best_nanos	nanos_per_iteration
compute-loop	k16	100000	100000	6226766	62.268
memory-loop	k16	100000	100000	10406456	104.065
mmio-loop	k16	100000	100000	10111187	101.112
```

## Approximate Throughput

Using the `100000`-iteration rows:

| Workload | Loop iterations/sec | Guest instructions/loop | Approx guest instructions/sec |
| --- | ---: | ---: | ---: |
| `compute-loop` | 16.06 M | 4 | 64.24 M |
| `memory-loop` | 9.61 M | 7 | 67.27 M |
| `mmio-loop` | 9.89 M | 7 | 69.23 M |

The instruction/sec values are approximate because they use the benchmark loop
shape, not retired-instruction counters from the VM.
