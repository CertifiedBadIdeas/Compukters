# RV32 Product Machine Baseline — 2026-08-11

## Scope

This is the first baseline that times the public `Rv32Machine::run` loop and
separately measures live heap retained by complete machine populations. Cached
uses 64 two-way sets (128 decoded entries); Predecoded covers the executable
ELF ranges. Both use 16 KiB RAM and a zero-capacity debug device.

## Environment

```text
source revision: 6ee0f297af82735a9c221aa69d26ba14ebac6468
Linux lazyhat-station 7.1.6-zen1-1-zen #1 ZEN SMP PREEMPT_DYNAMIC Tue, 04 Aug 2026 11:19:14 +0000 x86_64 GNU/Linux
rustc 1.94.1 (e408947bf 2026-03-25)
binary: rustc
commit-hash: e408947bfd200af42db322daf0fadfe7e26d3bd1
commit-date: 2026-03-25
host: x86_64-unknown-linux-gnu
release: 1.94.1
LLVM version: 21.1.8
cargo 1.94.1 (29ea6fb6a 2026-03-24)
clang version 22.1.8
Target: x86_64-pc-linux-gnu
Thread model: posix
InstalledDir: /usr/bin
```

## Command

```bash
cargo run --manifest-path host/compukter-vm/Cargo.toml --release \
  --example rv32_machine_benchmarks --locked --offline -- 1000 21 7
```

## Raw output

```text
    Finished `release` profile [optimized] target(s) in 0.00s
     Running `host/compukter-vm/target/release/examples/rv32_machine_benchmarks 1000 21 7`
RV32 product machine execution report
iterations	1000
warm_samples	21
ram_bytes	16384
debug_limit	0
cached_sets	64
cached_entries	128
workload	backend	iterations	checksum	cold_ns	warm_median_ns	warm_p95_ns	ns_per_iteration	retired_instructions	cache_hits	cache_misses	ram_bytes	executable_bytes	translation_bytes	steady_allocations	steady_allocated_bytes
compute32	cached	1000	1133597426	108490	105440	109160	105.440	18022	17983	39	16384	156	5632	0	0
compute32	predecoded	1000	1133597426	119240	113609	116620	113.609	18022	-	-	16384	156	1592	0	0
branch-mix	cached	1000	2000	37680	37100	40700	37.100	6514	6493	21	16384	84	5632	0	0
branch-mix	predecoded	1000	2000	42669	41760	46240	41.760	6514	-	-	16384	84	872	0	0
call-stack	cached	1000	502500	96420	95590	97500	95.590	14016	13987	29	16384	116	5632	0	0
call-stack	predecoded	1000	502500	106959	107320	111629	107.320	14016	-	-	16384	116	1192	0	0
memory-sequential	cached	1000	500500	74700	74580	76980	74.580	10474	10433	41	16384	164	5632	0	0
memory-sequential	predecoded	1000	500500	81889	81620	84059	81.620	10474	-	-	16384	164	1672	0	0
memory-random	cached	1000	500500	86229	87750	91300	87.750	12478	12431	47	16384	188	5632	0	0
memory-random	predecoded	1000	500500	93640	94830	97249	94.830	12478	-	-	16384	188	1912	0	0
copy-checksum	cached	1000	160512000	13437443	13287883	13403272	13287.883	2311020	2310986	34	16384	136	5632	0	0
copy-checksum	predecoded	1000	160512000	17966574	14447841	14550671	14447.841	2311020	-	-	16384	136	1392	0	0
mmio-control	cached	1000	499500	39920	38969	41460	38.969	6016	5995	21	16384	84	5632	0	0
mmio-control	predecoded	1000	499500	48320	41180	42710	41.180	6016	-	-	16384	84	872	0	0
packet-ring	cached	1000	2033536	1157308	1166158	1192067	1166.158	215022	214982	40	16384	160	5632	0	0
packet-ring	predecoded	1000	2033536	1265138	1267648	1298067	1267.648	215022	-	-	16384	160	1632	0	0
trap-roundtrip	cached	1000	1000	40320	39310	41140	39.310	8017	8992	25	16384	100	5632	0	0
trap-roundtrip	predecoded	1000	1000	43900	43620	46510	43.620	8017	-	-	16384	100	1032	0	0

RV32 resident population report
resident_samples	7
workload	packet-ring
backend	population	construction_median_ns	construction_p95_ns	resident_live_bytes	peak_construction_bytes	live_bytes_per_machine	aggregate_ram_bytes	elf_bytes	executable_bytes	rw_initialized_bytes	ram_bytes	debug_limit	cache_sets
cached	1	6590	9160	22744	38860	22744.000	16384	8192	160	0	16384	0	64
predecoded	1	6840	7110	18744	34860	18744.000	16384	8192	160	0	16384	0	-
cached	32	254230	267360	727808	743924	22744.000	524288	8192	160	0	16384	0	64
predecoded	32	253720	293359	599808	615924	18744.000	524288	8192	160	0	16384	0	-
cached	256	1759896	2346775	5822464	5838580	22744.000	4194304	8192	160	0	16384	0	64
predecoded	256	1754447	1806107	4798464	4814580	18744.000	4194304	8192	160	0	16384	0	-
cached	1024	8909812	9641561	23289856	23305972	22744.000	16777216	8192	160	0	16384	0	64
predecoded	1024	8616663	9312341	19193856	19209972	18744.000	16777216	8192	160	0	16384	0	-
```

## Interpretation

- Cached has the lower warm median in all nine complete-machine workloads. The
  Predecoded/Cached median-time ratio has a 1.09331 geometric mean: Predecoded
  is 9.33% higher on this run, equivalently Cached is 8.53% lower.
- The full product loop therefore reverses the large Predecoded advantage seen
  in the lightweight decoder benchmark. This baseline establishes the reversal
  but does not yet attribute it to one instruction-loop operation.
- Every timed `run` reports zero allocations and zero allocated bytes. All
  workloads halt through the product ControlDevice with matching checksums.
- For PacketRing, Predecoded retains 18,744 bytes per machine versus 22,744 for
  Cached. The exact 4,000-byte difference matches backend translation storage
  (1,632 versus 5,632 bytes), saving 17.59% resident heap or 3.90625 MiB at
  1,024 machines.
- Construction medians are close. At 1,024 machines Predecoded constructs in
  8.617 ms versus 8.910 ms for Cached; active execution, not construction, is
  the performance trade-off exposed by this baseline.

These absolute timings describe this host and revision only. They do not set a
server CPU budget or promise a number of simultaneously active computers.
