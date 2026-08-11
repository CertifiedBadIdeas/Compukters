# RV32 Decoded Basic-Block Cache — 2026-08-11

## Scope and revision

This is Slice 1 of [#498](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/498).
It adds the bounded two-way BlockCached backend without direct chaining,
register caching, superinstructions, RAM fast paths, host code generation, or
an ABI change. Cached and Predecoded remain available, and the product default
is unchanged.

The fixed BlockCached geometry is 32 two-way sets with at most eight decoded
instructions per block. Each way preallocates its complete slot capacity.
One budget unit remains one attempted guest instruction.

```text
source revision: 50eadc14640ef180b954ab5da0aadbb9b8e1d9e9
Linux 7.1.6-zen1-1-zen x86_64 GNU/Linux
CPU: AMD Ryzen 9 9950X3D 16-Core Processor, 16 cores / 32 threads
rustc 1.94.1 (e408947bf 2026-03-25), LLVM 21.1.8
cargo 1.94.1 (29ea6fb6a 2026-03-24)
clang 22.1.8
LLD 22.1.8
QEMU emulator version 11.0.3
```

## Exact commands

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
bash scripts/tests/rv32-elf-boot-contract.sh
bash scripts/tests/rv32-elf-trap-contract.sh
bash scripts/tests/rv32-elf-atomic-contract.sh
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_machine_benchmarks --locked --offline -- 1000 21 7
bash scripts/tests/rv32-c-qemu-comparison.sh
```

The complete Rust suite passed. The three stock Clang/LLD ELF32 contracts passed
with BlockCached enabled. The C/QEMU gate compiled one shared RV32 kernel object
and all five candidates returned checksum `ee053d58`.

An initial measurement was rejected before documentation because block-specific
range and attempted-budget logic had been placed in the common loop, regressing
the Cached control. Commit `50eadc14` isolates BlockCached behind one
per-`run` dispatch and restores the original Cached/Predecoded
single-instruction loop. Their corrected product geomeans (`125.36x` and
`134.88x native`) match the preserved pre-slice references (`126.70x` and
`134.74x`) within normal run variance.

## Product-machine raw report

```text
RV32 product machine execution report
iterations	1000
warm_samples	21
ram_bytes	16384
debug_limit	0
cached_sets	64
cached_entries	128
block_cached_sets	32
block_max_instructions	8
workload	candidate	iterations	checksum	batch	cold_ns	warm_median_ns	warm_p95_ns	operations_per_second	retired_instructions	lookup_unit	cache_hits	cache_misses	cache_evictions	blocks_built	decoded_slots_built	ram_bytes	executable_bytes	translation_bytes	steady_allocations	steady_allocated_bytes	vs_native
compute32	native-host	1000	1133597426	512	2648.529	2627.652	2658.080	380567.849	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
compute32	rv32-cached	1000	1133597426	1	156220.000	85279.000	156090.000	11726.216	18022	instruction	17983	39	0	0	0	16384	156	5632	0	0	32.454446
compute32	rv32-predecoded	1000	1133597426	1	166430.000	90970.000	173890.000	10992.635	18022	-	-	-	-	-	-	16384	156	1592	0	0	34.620257
compute32	rv32-block-cached	1000	1133597426	1	234059.000	111790.000	161490.000	8945.344	18022	block	3996	8	0	8	40	16384	156	22784	0	0	42.543680
branch-mix	native-host	1000	2000	8192	124.800	124.852	125.177	8009479.970	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
branch-mix	rv32-cached	1000	2000	1	30210.000	29720.000	31710.000	33647.376	6514	instruction	6493	21	0	0	0	16384	84	5632	0	0	238.041745
branch-mix	rv32-predecoded	1000	2000	1	32550.000	32530.000	34770.000	30740.855	6514	-	-	-	-	-	-	16384	84	872	0	0	260.548383
branch-mix	rv32-block-cached	1000	2000	1	40790.000	40650.000	41099.000	24600.246	6514	block	3495	8	0	8	24	16384	84	22784	0	0	325.585361
call-stack	native-host	1000	502500	512	2836.266	2854.859	2873.393	350279.950	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
call-stack	rv32-cached	1000	502500	1	77010.000	75380.000	77040.000	13266.118	14016	instruction	13987	29	0	0	0	16384	116	5632	0	0	26.404103
call-stack	rv32-predecoded	1000	502500	1	87019.000	83780.000	89000.000	11936.023	14016	-	-	-	-	-	-	16384	116	1192	0	0	29.346454
call-stack	rv32-block-cached	1000	502500	1	95930.000	96580.000	102420.000	10354.111	14016	block	5995	9	0	9	29	16384	116	22784	0	0	33.830038
memory-sequential	native-host	1000	500500	4096	456.205	457.179	472.431	2187324.943	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
memory-sequential	rv32-cached	1000	500500	1	58960.000	59520.000	63700.000	16801.075	10474	instruction	10433	41	0	0	0	16384	164	5632	0	0	130.189581
memory-sequential	rv32-predecoded	1000	500500	1	62670.000	63200.000	66120.000	15822.785	10474	-	-	-	-	-	-	16384	164	1672	0	0	138.238936
memory-sequential	rv32-block-cached	1000	500500	1	73030.000	73750.000	75809.000	13559.322	10474	block	3123	11	0	11	43	16384	164	22784	0	0	161.315215
memory-random	native-host	1000	500500	2048	895.721	896.107	900.238	1115937.638	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
memory-random	rv32-cached	1000	500500	1	69150.000	68960.000	70760.000	14501.160	12478	instruction	12431	47	0	0	0	16384	188	5632	0	0	76.955060
memory-random	rv32-predecoded	1000	500500	1	73000.000	73340.000	75950.000	13635.124	12478	-	-	-	-	-	-	16384	188	1912	0	0	81.842866
memory-random	rv32-block-cached	1000	500500	1	84579.000	84860.000	85679.000	11784.115	12478	block	3123	11	0	11	49	16384	188	22784	0	0	94.698468
copy-checksum	native-host	1000	160512000	64	20117.312	20072.922	20142.156	49818.358	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
copy-checksum	rv32-cached	1000	160512000	1	13887957.000	13637727.000	13824787.000	73.326	2311020	instruction	2310986	34	0	0	0	16384	136	5632	0	0	679.409161
copy-checksum	rv32-predecoded	1000	160512000	1	14797745.000	14739765.000	15029155.000	67.844	2311020	-	-	-	-	-	-	16384	136	1392	0	0	734.310884
copy-checksum	rv32-block-cached	1000	160512000	1	16152583.000	15901844.000	16170063.000	62.886	2311020	block	515994	10	0	10	36	16384	136	22784	0	0	792.203751
mmio-control	native-host	1000	499500	4096	287.341	280.422	286.179	3566055.608	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
mmio-control	rv32-cached	1000	499500	1	40250.000	40020.000	41450.000	24987.506	6016	instruction	5995	21	0	0	0	16384	84	5632	0	0	142.713545
mmio-control	rv32-predecoded	1000	499500	1	41330.000	40940.000	42590.000	24425.989	6016	-	-	-	-	-	-	16384	84	872	0	0	145.994317
mmio-control	rv32-block-cached	1000	499500	1	45309.000	44630.000	45460.000	22406.453	6016	block	1999	5	0	5	21	16384	84	22784	0	0	159.153062
packet-ring	native-host	1000	2033536	256	6722.488	6854.637	6934.637	145886.652	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
packet-ring	rv32-cached	1000	2033536	1	1146329.000	1162828.000	1199308.000	859.972	215022	instruction	214982	40	0	0	0	16384	160	5632	0	0	169.641084
packet-ring	rv32-predecoded	1000	2033536	1	1355127.000	1276358.000	1311377.000	783.479	215022	-	-	-	-	-	-	16384	160	1632	0	0	186.203595
packet-ring	rv32-block-cached	1000	2033536	1	1430617.000	1463848.000	1488667.000	683.131	215022	block	51993	11	0	11	42	16384	160	22784	0	0	213.555883
trap-roundtrip	native-host	1000	1000	8192	182.310	181.405	182.273	5512533.747	-	-	-	-	-	-	-	-	-	-	0	0	1.000000
trap-roundtrip	rv32-cached	1000	1000	1	41930.000	41250.000	42290.000	24242.424	8017	instruction	8992	25	0	0	0	16384	100	5632	0	0	227.392017
trap-roundtrip	rv32-predecoded	1000	1000	1	44860.000	44820.000	49479.000	22311.468	8017	-	-	-	-	-	-	16384	100	1032	0	0	247.071763
trap-roundtrip	rv32-block-cached	1000	1000	1	48680.000	48010.000	50940.000	20828.994	8017	block	3996	8	0	8	26	16384	100	22784	0	0	264.656745

RV32 product machine native summary
candidate	host_overhead_geomean
native-host	1.000000
rv32-cached	125.356284
rv32-predecoded	134.876018
rv32-block-cached	154.789247

RV32 resident population report
resident_samples	7
workload	packet-ring
backend	population	construction_median_ns	construction_p95_ns	resident_live_bytes	peak_construction_bytes	live_bytes_per_machine	aggregate_ram_bytes	elf_bytes	executable_bytes	rw_initialized_bytes	ram_bytes	debug_limit	cache_sets	block_cache_sets	block_max_instructions
cached	1	6270	11970	22768	38884	22768.000	16384	8192	160	0	16384	0	64	-	-
predecoded	1	6480	6700	18768	34884	18768.000	16384	8192	160	0	16384	0	-	-	-
block-cached	1	7510	9140	39920	56036	39920.000	16384	8192	160	0	16384	0	-	32	8
cached	32	245090	248840	728576	744692	22768.000	524288	8192	160	0	16384	0	64	-	-
predecoded	32	246710	280360	600576	616692	18768.000	524288	8192	160	0	16384	0	-	-	-
block-cached	32	326049	353789	1277440	1293556	39920.000	524288	8192	160	0	16384	0	-	32	8
cached	256	2041437	2060996	5828608	5844724	22768.000	4194304	8192	160	0	16384	0	64	-	-
predecoded	256	2023567	2046946	4804608	4820724	18768.000	4194304	8192	160	0	16384	0	-	-	-
block-cached	256	2797966	3109355	10219520	10235636	39920.000	4194304	8192	160	0	16384	0	-	32	8
cached	1024	8162636	8996355	23314432	23330548	22768.000	16777216	8192	160	0	16384	0	64	-	-
predecoded	1024	7784747	8611696	19218432	19234548	18768.000	16777216	8192	160	0	16384	0	-	-	-
block-cached	1024	11967430	13414518	40878080	40894196	39920.000	16777216	8192	160	0	16384	0	-	32	8
```

## C/QEMU raw report

```text
RV32 optimized C comparison
iterations	1000
seed	0x12345678
warm_samples	21
qemu_startup_samples	7
qemu_startup_median_ns	11625770
qemu_target_ns	581288500
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
block-cached-calibrated-sha256	08d7b78e23fc3ff16ef3f126c19eddf017e55e2ee2cc90806ec9af99b4808be9
candidate	mode	iterations	seed	batch	checksum	total_median_ns	total_p95_ns	ns_per_kernel	kernels_per_second	vs_native	vs_qemu	text_bytes	qemu_startup_median_ns	retired_instructions	lookup_unit	cache_hits	cache_misses	cache_evictions	blocks_built	decoded_slots_built	translation_bytes	steady_allocations	steady_allocated_bytes
native-clang	clang-O3-native-lto	1000	0x12345678	8192	ee053d58	501304007	515784635	61194.337	16341.381	1.000000	0.113726	8822	-	-	-	-	-	-	-	-	-	-	-
qemu-rv32-tcg	virt-system-tcg	1000	0x12345678	2048	ee053d58	1101998803	1112193002	538085.353	1858.441	8.793058	1.000000	2980	11625770	-	-	-	-	-	-	-	-	-	-
rv32-cached	product-machine-cached	1000	0x12345678	16	ee053d58	424327397	433161892	26520462.312	37.707	433.380991	49.286720	2396	-	62690932	instruction	56796960	5893972	5893844	0	0	5632	0	0
rv32-predecoded	product-machine-predecoded	1000	0x12345678	16	ee053d58	385272273	393242071	24079517.062	41.529	393.492573	44.750367	2396	-	62690932	-	-	-	-	-	-	23992	0	0
rv32-block-cached	product-machine-block-cached	1000	0x12345678	8	ee053d58	249732271	254039623	31216533.875	32.034	510.121288	58.014093	2396	-	31345476	block	5154181	345991	345935	345991	2545742	22784	0	0
```

## Interpretation

- On the nine complete product workloads, BlockCached is
  `23.48%` slower than Cached by host-overhead geomean
  (`154.79x native` versus `125.36x`) and `14.76%`
  slower than Predecoded's `134.88x native`.
- On the shared optimized C workload, BlockCached takes `31.22 ms/kernel`:
  `17.71%` more time than Cached's `26.52 ms/kernel` and
  `29.64%` more than Predecoded's `24.08 ms/kernel`.
- The absolute C ceilings are `510.12x native` and `58.01x QEMU` for
  BlockCached, compared with Cached at `433.38x native` / `49.29x QEMU`
  and Predecoded at `393.49x native` / `44.75x QEMU`.
- BlockCached performs 5,154,181 block hits and 345,991 misses, a
  `6.29%` block-lookup miss rate. Its 345,991 constructed blocks
  contain 2,545,742 decoded slots, averaging `7.36` slots per
  construction. Lookup frequency falls substantially, but the extra block-loop
  checks do not remove the existing per-instruction hart dispatch and semantic
  checks, so no throughput benefit follows.
- Every timed product path reports zero steady-state allocations.

## Memory trade-off

The fixed block cache retains 22,784 translation bytes per machine. On the C
image this is `5.04%` below Predecoded's 23,992 bytes,
but 4.05 times Cached's 5,632-byte instruction cache.

For the PacketRing resident population, total measured live heap is 39,920
bytes per BlockCached machine, versus 22,768 Cached and 18,768 Predecoded.
BlockCached therefore costs `75.33%` more live heap than Cached
and `112.70%` more than Predecoded at this geometry. At 1,024
machines the measured live totals are 40,878,080, 23,314,432, and 19,218,432
bytes respectively.

## Decision

Keep BlockCached only as an experimental structural backend for the next block
execution experiment. Reject this unchained, unfused implementation as the
default or as a performance optimization by itself: it is slower than both
existing product backends and materially heavier than Cached.

The next #498 slice should reuse the decoded-block representation but measure a
fused block executor that keeps frequently used hart state in host locals and
reduces per-slot dispatch/check overhead. Direct chaining, superinstructions,
and a JIT remain later decisions. The fixed 32-set/eight-slot negative result is
preserved unchanged and must remain the comparison baseline for that work.
