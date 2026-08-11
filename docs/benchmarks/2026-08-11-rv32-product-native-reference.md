# RV32 Product Machine Native Reference — 2026-08-11

## Scope

This measurement adds a same-process native Rust reference to the complete
`Rv32Machine::run` benchmark. Each native result is calibrated to at least one
millisecond and divided by its batch size; candidate order rotates between
samples. Cached and Predecoded still execute a fresh product machine and the
same byte-identical strict ELF32 image on every sample.

The native row measures the workload computation, not an equivalent machine,
ELF loader, interpreter, trap, or MMIO stack. It is therefore an absolute host
execution reference rather than an attribution of interpreter-only overhead.
The stronger native C compiler ceiling belongs to the separate C/QEMU
benchmark.

## Environment

```text
source revision: 613fd745fe4d1cbe0b5bb4a6a34031ddad2bac95
Linux lazyhat-station 7.1.6-zen1-1-zen #1 ZEN SMP PREEMPT_DYNAMIC Tue, 04 Aug 2026 11:19:14 +0000 x86_64 GNU/Linux
CPU: AMD Ryzen 9 9950X3D 16-Core Processor, 16 cores / 32 threads
rustc 1.94.1 (e408947bf 2026-03-25), LLVM 21.1.8
cargo 1.94.1 (29ea6fb6a 2026-03-24)
clang 22.1.8, x86_64-pc-linux-gnu
```

## Command

```bash
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_machine_benchmarks --locked --offline -- 1000 21 7
```

## Raw active-execution output

The resident-population portion is unchanged from the original product-machine
baseline and is intentionally omitted here; the command above emitted and
validated it in the same run.

```text
RV32 product machine execution report
iterations	1000
warm_samples	21
ram_bytes	16384
debug_limit	0
cached_sets	64
cached_entries	128
workload	candidate	iterations	checksum	batch	cold_ns	warm_median_ns	warm_p95_ns	operations_per_second	retired_instructions	cache_hits	cache_misses	ram_bytes	executable_bytes	translation_bytes	steady_allocations	steady_allocated_bytes	vs_native
compute32	native-host	1000	1133597426	512	3448.352	3419.779	3436.262	292416.531	-	-	-	-	-	-	0	0	1.000000
compute32	rv32-cached	1000	1133597426	1	112520.000	109940.000	114169.000	9095.870	18022	17983	39	16384	156	5632	0	0	32.148273
compute32	rv32-predecoded	1000	1133597426	1	116320.000	115760.000	120300.000	8638.563	18022	-	-	16384	156	1592	0	0	33.850138
branch-mix	native-host	1000	2000	8192	167.684	165.213	167.943	6052778.574	-	-	-	-	-	-	0	0	1.000000
branch-mix	rv32-cached	1000	2000	1	39800.000	37150.000	39550.000	26917.900	6514	6493	21	16384	84	5632	0	0	224.860724
branch-mix	rv32-predecoded	1000	2000	1	42590.000	41880.000	43780.000	23877.746	6514	-	-	16384	84	872	0	0	253.490367
call-stack	native-host	1000	502500	512	2820.152	2829.506	2838.725	353418.600	-	-	-	-	-	-	0	0	1.000000
call-stack	rv32-cached	1000	502500	1	84520.000	83970.000	90120.000	11909.015	14016	13987	29	16384	116	5632	0	0	29.676560
call-stack	rv32-predecoded	1000	502500	1	84789.000	84060.000	88460.000	11896.265	14016	-	-	16384	116	1192	0	0	29.708368
memory-sequential	native-host	1000	500500	4096	456.366	455.873	484.474	2193593.166	-	-	-	-	-	-	0	0	1.000000
memory-sequential	rv32-cached	1000	500500	1	59520.000	59159.000	62570.000	16903.599	10474	10433	41	16384	164	5632	0	0	129.770778
memory-sequential	rv32-predecoded	1000	500500	1	63540.000	63100.000	66100.000	15847.861	10474	-	-	16384	164	1672	0	0	138.415729
memory-random	native-host	1000	500500	2048	892.914	894.027	903.177	1118534.632	-	-	-	-	-	-	0	0	1.000000
memory-random	rv32-cached	1000	500500	1	68620.000	68170.000	69540.000	14669.209	12478	12431	47	16384	188	5632	0	0	76.250506
memory-random	rv32-predecoded	1000	500500	1	76709.000	72920.000	77430.000	13713.659	12478	-	-	16384	188	1912	0	0	81.563545
copy-checksum	native-host	1000	160512000	64	18609.969	18761.203	21993.406	53301.486	-	-	-	-	-	-	0	0	1.000000
copy-checksum	rv32-cached	1000	160512000	1	13591014.000	13848293.000	14456612.000	72.211	2311020	2310986	34	16384	136	5632	0	0	738.134591
copy-checksum	rv32-predecoded	1000	160512000	1	14764791.000	14671522.000	15099471.000	68.159	2311020	-	-	16384	136	1392	0	0	782.013920
mmio-control	native-host	1000	499500	4096	284.946	279.140	334.399	3582433.133	-	-	-	-	-	-	0	0	1.000000
mmio-control	rv32-cached	1000	499500	1	40060.000	39799.000	75760.000	25126.259	6016	5995	21	16384	84	5632	0	0	142.577256
mmio-control	rv32-predecoded	1000	499500	1	42190.000	41250.000	75480.000	24242.424	6016	-	-	16384	84	872	0	0	147.775367
packet-ring	native-host	1000	2033536	256	6816.469	6767.020	6902.176	147775.545	-	-	-	-	-	-	0	0	1.000000
packet-ring	rv32-cached	1000	2033536	1	1153958.000	1197037.000	1362308.000	835.396	215022	214982	40	16384	160	5632	0	0	176.892795
packet-ring	rv32-predecoded	1000	2033536	1	1251078.000	1263777.000	1347258.000	791.279	215022	-	-	16384	160	1632	0	0	186.755335
trap-roundtrip	native-host	1000	1000	8192	181.412	180.540	184.303	5538926.306	-	-	-	-	-	-	0	0	1.000000
trap-roundtrip	rv32-cached	1000	1000	1	38340.000	38450.000	40300.000	26007.802	8017	8992	25	16384	100	5632	0	0	212.971716
trap-roundtrip	rv32-predecoded	1000	1000	1	43420.000	42540.000	45160.000	23507.287	8017	-	-	16384	100	1032	0	0	235.625925

RV32 product machine native summary
candidate	host_overhead_geomean
native-host	1.000000
rv32-cached	126.704305
rv32-predecoded	134.737448
```

## Interpretation

- Cached is 126.70 times and Predecoded is 134.74 times slower than the native
  workload reference by geometric mean across the nine workloads.
- Cached has the lower warm median in all nine workloads. Predecoded's aggregate
  overhead is 6.34% higher than Cached's in this run.
- The range is wide: CallStack is about 29.68 times native for Cached, while
  CopyChecksum is about 738.13 times native. A single conversion from this
  report to a universal guest CPU frequency would therefore be misleading.
- The ratios include product-machine dispatch, address-space access, traps, and
  MMIO where the workload uses them. They do not isolate decoder cost.
- Native timings are normalized calibrated batches; VM timings remain one fresh
  machine execution per sample. All candidates produced matching checksums and
  the two VM candidates retained zero timed allocations.

These values describe this host, revision, and compiler only. They are a stable
comparison artifact for later RV32 optimization, not a server-capacity promise.
