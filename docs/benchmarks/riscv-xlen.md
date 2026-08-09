# RISC-V XLEN compiled-C benchmark

This gate compares the existing RV32IM interpreter with an isolated RV64IM interpreter under the
same eager-predecode execution model. Both candidates are compiled from the same six C sources by
stock LLVM 22 tools:

- RV32IM uses `riscv32-unknown-elf`, `-march=rv32im`, and `-mabi=ilp32`;
- RV64IM uses `riscv64-unknown-elf`, `-march=rv64im`, and `-mabi=lp64`.

Both pipelines use `-O2`, keep linker relaxation enabled, exclude vectorization, and execute with
the same 128 KiB benchmark memory. Every result is checked against the same native Rust checksum.
The benchmark records warm median and p95 time, retired guest instructions, code and predecode
bytes, CPU state size, data traffic, and the ratio to native execution.

Run the reproducibility and execution contract with:

```text
bash scripts/tests/riscv-xlen-gate-benchmark.sh
```

Record the current host snapshot with:

```text
bash scripts/record-riscv-xlen-gate-benchmark.sh 100000 9
```

The six workloads mostly operate on `u32` and `u8` values. Therefore this gate measures the direct
XLEN cost on the established corpus, but cannot select the production architecture by itself. In
particular, it does not represent KraftOS-scale code, pointer-heavy allocation, many-VM retained
memory, atomics, privilege levels, CSRs, or virtual memory. The report deliberately contains no
automatic architecture decision.

## U64-heavy follow-up

Issue #484 adds three workloads designed to exercise native 64-bit arithmetic and memory:
`u64-mix`, `fixed64-geometry`, and `u64-memory`. They use the same compiler, VM, timing, and native
oracle contract. LLVM constant pools are disabled symmetrically because the current benchmark flat
image intentionally contains immutable instructions only; the shell contract rejects linked data
or rodata payloads instead of silently dropping them.

Run and record this corpus with:

```text
bash scripts/tests/riscv-xlen-u64-gate-benchmark.sh
bash scripts/record-riscv-xlen-u64-gate-benchmark.sh 100000 9
```
