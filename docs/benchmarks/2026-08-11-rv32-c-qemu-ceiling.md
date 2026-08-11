# Optimized C / QEMU / Rv32Machine Ceiling — 2026-08-11

## Scope

This benchmark compiles one committed portable C kernel as maximum optimized
native host code and exactly once as an RV32IM/Zicsr ILP32 object. The shared
RV32 object is linked with thin product and QEMU wrappers, then executed by
QEMU `virt` system-mode TCG, product Cached, and product Predecoded.

Native and QEMU timings include child-process startup. Product timings include
`Rv32Machine::run` but exclude ELF loading and machine construction. Every
candidate receives an independently calibrated power-of-two batch and returns
the same checksum for the same 1,000-iteration kernel call.

## Environment

```text
source revision: e425da3c60085685722b65d3d2e3dcb4c2619c1b
Linux lazyhat-station 7.1.6-zen1-1-zen #1 ZEN SMP PREEMPT_DYNAMIC Tue, 04 Aug 2026 11:19:14 +0000 x86_64 GNU/Linux
CPU: AMD Ryzen 9 9950X3D 16-Core Processor, 16 cores / 32 threads
rustc 1.94.1 (e408947bf 2026-03-25), LLVM 21.1.8
cargo 1.94.1 (29ea6fb6a 2026-03-24)
clang 22.1.8, x86_64-pc-linux-gnu
LLD 22.1.8
QEMU emulator version 11.0.3
```

## Command and generated audit artifacts

```bash
bash scripts/tests/rv32-c-qemu-comparison.sh
```

The focused gate writes ignored artifacts under
`host/compukter-vm/target/rv32-c-comparison/`, including:

- `kernel-rv32.o` and `manifest.tsv`;
- native optimization remarks and native disassembly;
- product/QEMU readobj reports;
- the calibrated QEMU, Cached, and Predecoded disassemblies;
- `report.tsv`, reproduced below.

The native compiler emitted AVX2/AVX-512 instructions. Its saved remarks report
SLP-vectorized horizontal reductions; the link-time remarks also report SLP
vectorized stores. The initialization and data-dependent random-probe loops
were explicitly reported as not loop-vectorized. SIMD therefore participates
in the native ceiling without implying that every phase vectorized.

## Raw report

```text
RV32 optimized C comparison
iterations	1000
seed	0x12345678
warm_samples	21
qemu_startup_samples	7
qemu_startup_median_ns	11628831
qemu_target_ns	581441550
qemu_mode	-M virt -bios none -accel tcg -nographic -monitor none
qemu-version	QEMU emulator version 11.0.3
clang-version	clang version 22.1.8
lld-version	LLD 22.1.8 (compatible with GNU linkers)
native-flags	-O3 -march=native -flto
rv32-flags	-O3 -march=rv32im_zicsr -mabi=ilp32 -ffreestanding -fno-builtin
kernel-object-sha256	fa477fc5a8fae1abfd01ec12a879470df4bcb54d585109b77e9d9d7c5df65b6d
native-sha256	8047ee42b3eeb7ff8b72c13742e2af7462058fff8917e608c8e22c996d6cd1a4
native-text-bytes	8822
product-text-bytes	2400
qemu-text-bytes	2980
qemu-calibrated-sha256	40a3fa66942df70f3c0e2a57f5713b669f315c25f6c66418d7fd8a6b3e3eba1a
cached-calibrated-sha256	e0361011498ec04bfec1f003a5f5bea638ab494ab71afc4719a63212db18ece8
predecoded-calibrated-sha256	e0361011498ec04bfec1f003a5f5bea638ab494ab71afc4719a63212db18ece8
candidate	mode	iterations	seed	batch	checksum	total_median_ns	total_p95_ns	ns_per_kernel	kernels_per_second	vs_native	vs_qemu	text_bytes	qemu_startup_median_ns	retired_instructions	cache_hits	cache_misses	translation_bytes
native-clang	clang-O3-native-lto	1000	0x12345678	4096	ee053d58	254294549	259360411	62083.630	16107.306	1.000000	0.115202	8822	-	-	-	-	-
qemu-rv32-tcg	virt-system-tcg	1000	0x12345678	2048	ee053d58	1103692286	1109881433	538912.249	1855.590	8.680424	1.000000	2980	11628831	-	-	-	-
rv32-cached	product-machine-cached	1000	0x12345678	16	ee053d58	404232418	409578456	25264526.125	39.581	406.943442	46.880594	2396	-	62690932	56796960	5893972	5632
rv32-predecoded	product-machine-predecoded	1000	0x12345678	16	ee053d58	368822636	375569844	23051414.750	43.381	371.296181	42.773967	2396	-	62690932	-	-	23992
```

## Interpretation

- **QEMU/native:** QEMU is 8.68 times slower than maximum native code. This
  includes RV32-to-x86 dynamic translation, guest/host ISA and compiler-codegen
  differences, and about 11.63 ms process startup. Startup is only 1.05% of the
  1.104 s median QEMU sample, below the intended approximately 2% bound.
- **Product/QEMU:** Cached is 46.88 times and Predecoded is 42.77 times slower
  than mature TCG over the same `kernel-rv32.o`. This is the most useful current
  measure of interpreter implementation headroom.
- **Product/native:** Cached is 406.94 times and Predecoded is 371.30 times
  slower than the optimized host ceiling. These totals intentionally combine
  interpreter cost with the benefit native LLVM receives from the host ISA and
  SIMD.
- **Cached versus Predecoded:** Predecoded takes 23.05 ms/kernel versus Cached's
  25.26 ms/kernel: 8.76% less time, or 9.60% more throughput. The larger 2,396
  byte C text reverses the nine tiny synthetic workloads where Cached won.
- Cached records 5,893,972 misses out of 62,690,932 instruction resolutions, a
  9.40% miss rate with 64 two-way sets. Predecoded avoids those lookups but uses
  23,992 translation bytes instead of Cached's fixed 5,632 bytes. This makes
  cache geometry and lookup cost primary next optimization targets; it does not
  justify making Predecoded the only product mode.

One kernel call performs 1,000 internal iterations, so `kernels_per_second` is
not an emulated clock frequency or guest instruction rate. Absolute throughput
is specific to this host, revision, compiler, and QEMU version.
