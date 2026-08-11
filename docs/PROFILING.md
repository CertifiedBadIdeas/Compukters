# VM Profiling

## RV32 Decoder Benchmark

The active benchmark first compares eager and opcode-first field extraction in
the same release binary. It measures isolated legal decode and forced misses
through the actual bounded product cache. The existing second report compares
the standard RV32 guest binary across Direct, Cached, and Predecoded execution
plus a native Rust reference.

Run it from the repository root:

```bash
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_decoder_benchmarks -- 1000 21
```

The first argument is the positive workload iteration count. The second is the
requested sample count. The decoder extraction report always takes at least 21
warm samples; the end-to-end report always takes at least seven.

Run the five-process extraction gate with:

```bash
bash scripts/tests/rv32-decoder-extraction-gate.sh 1000 21
```

The attributable decoder baseline captured on 2026-08-11 is stored in
[`rv32-decoder-baseline-2026-08-11.txt`](benchmarks/rv32-decoder-baseline-2026-08-11.txt).
It records the exact source revision, host, Rust toolchain, command, and complete
output. Treat it as comparison evidence for later decoder work, not as a stable
performance promise across hosts.

The post-RV32A measurement is stored in
[`rv32-decoder-rv32a-2026-08-11.txt`](benchmarks/rv32-decoder-rv32a-2026-08-11.txt).
Against the baseline on the same host and toolchain, host-overhead geomean moved
by +3.27% for Direct, +4.52% for Cached, and -1.51% for Predecoded. CPU state
grew from 144 to 152 bytes for the exact-word reservation, while retained
translation bytes and steady-state allocation counts stayed unchanged in every
workload. These small single-run timing movements bound the attributable RV32A
decoder impact; they are not a cross-host performance guarantee.

The opcode-first extraction measurement is stored in
[`rv32-decoder-opcode-first-2026-08-11.txt`](benchmarks/rv32-decoder-opcode-first-2026-08-11.txt).
Opcode-first won 5/5 processes in both scenarios. Median improvement was 1.75%
for isolated legal decode and 1.34% for bounded-cache forced misses; checksums,
retained bytes, and zero steady-state allocations matched eager extraction.
Those values missed the predeclared automatic 2% threshold, but the project
owner retained opcode-first because its direction was consistent and its
maintenance complexity is no greater. The report preserves both the formal
gate rejection and the explicit owner decision.

The historical `rv32-cached` end-to-end candidate still uses the HashMap-backed
`CachedRv32imProgram`. The new forced-miss scenario uses the product
`BoundedCachedRv32imProgram`; do not use the former to attribute decoder
extraction cost in the latter.

## RV32 Product Machine Benchmark

The Direct, Cached, and Predecoded rows in `rv32_decoder_benchmarks` use the
lightweight ISA executor. They do not include the complete product machine
loop, ELF-owned address space, machine-mode traps, or platform MMIO.

Measure public `Rv32Machine::run` and resident many-VM construction separately:

```bash
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_machine_benchmarks --locked --offline -- 1000 21 7
```

The arguments are workload iterations, warm execution samples, and resident
construction samples. The runner enforces minima of 21 and seven samples. Its
active report rotates NativeHost, Cached, and Predecoded sample order. Native
work is calibrated in batches of at least one millisecond and normalized back
to one workload invocation; Cached and Predecoded time one fresh
`Rv32Machine::run` and use one byte-identical strict ELF32 image. The report
includes complete-workload operations per second and VM/native median ratios.
The resident report remains VM-only and excludes the shared ELF from the
population heap delta.

The 2026-08-11 baseline, complete raw output, environment, and interpretation
are stored in
[`2026-08-11-rv32-product-machine-baseline.md`](benchmarks/2026-08-11-rv32-product-machine-baseline.md).
The subsequent same-process native reference and normalized absolute ratios are
stored in
[`2026-08-11-rv32-product-native-reference.md`](benchmarks/2026-08-11-rv32-product-native-reference.md).
Absolute timings are host-specific comparison evidence, not a server-capacity
promise.

## Optimized C and QEMU Ceiling

The focused external comparison compiles one portable C translation unit for
maximum native host execution and once as an RV32IM/Zicsr ILP32 object. That
same RV32 object is linked into the product and QEMU platform images:

```bash
bash scripts/tests/rv32-c-qemu-comparison.sh
```

The gate requires stock Clang, LLD, LLVM inspection tools, and
`qemu-system-riscv32`. It uses QEMU `virt` system emulation with bare-metal
machine mode and TCG; it does not use Linux, OpenSBI, user-mode QEMU, KVM, or a
host sysroot. Each candidate receives an independent power-of-two batch. QEMU
calibration requires at least 250 ms total and 50 times measured startup, and
the report does not subtract startup.

Generated objects, calibrated images, optimization remarks, disassemblies, and
the raw report remain under `host/compukter-vm/target/rv32-c-comparison/`. This
focused command is deliberately absent from normal Gradle/Cargo verification,
Minecraft runs, and production JAR assembly. Missing external tools are a hard
error; there is no alternate benchmark candidate or fallback.

Committed Gate and XLEN outputs under `docs/benchmarks/` are immutable
historical evidence. They are not active benchmark runners and do not select a
supported product architecture. The accepted architecture decision is recorded
in [ADR 0001](architecture-decisions/0001-adopt-rv32.md).
