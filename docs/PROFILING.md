# VM Profiling

## RV32 Decoder Benchmark

The active benchmark compares the standard RV32 guest binary across Direct,
Cached, and Predecoded execution plus a native Rust reference. It reports cold
time, warm median and p95, guest instruction counts, retained VM bytes, and
steady-state host allocations.

Run it from the repository root:

```bash
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_decoder_benchmarks -- 1000 7
```

The first argument is the positive workload iteration count. The second is the
requested sample count; the runner always takes at least seven warm samples.

The attributable decoder baseline captured on 2026-08-11 is stored in
[`rv32-decoder-baseline-2026-08-11.txt`](benchmarks/rv32-decoder-baseline-2026-08-11.txt).
It records the exact source revision, host, Rust toolchain, command, and complete
output. Treat it as comparison evidence for later decoder work, not as a stable
performance promise across hosts.

Committed Gate and XLEN outputs under `docs/benchmarks/` are immutable
historical evidence. They are not active benchmark runners and do not select a
supported product architecture. The accepted architecture decision is recorded
in [ADR 0001](architecture-decisions/0001-adopt-rv32.md).
