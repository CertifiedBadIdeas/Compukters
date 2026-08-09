# KraftOS C SDK

> Issue: [#466](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/466)

Status: active.

The KraftOS C SDK is immutable auxiliary media for compiling C source inside a
running K16 computer. The player-visible C Programming SDK item carries only
the artifact identity `c_sdk_v1`; the server resolves that identity to the
bundled `firmware/c-sdk-v1.kv` bytes and attaches them as read-only `storage1`
before boot. Media bytes are never stored in the item stack.

## Hardware And Lifecycle

The SDK cartridge may be inserted or removed only while the computer is off.
The next cold boot either appends `storage1` to the hardware table and mounts
its ROOT/KFS partition at `/sdk`, or boots without `storage1`. There is no
hotplug, empty placeholder device, automatic identity upgrade, writable SDK
path, or snapshot-owned media copy.

Notebook family and RAM are independent physical product contracts:

| Product | Family | K16 RAM | Native TinyCC contract |
| --- | --- | ---: | --- |
| Notebook | `NORMAL` | 1 MiB | deterministic allocation/loader failure; no object is created |
| Advanced Notebook | `ADVANCED` | 4 MiB | supported source-to-object compile path |

Installing `c_sdk_v1` never changes the family or RAM capacity.

## Fixed SDK Layout

The published volume has a 4 MiB KFS payload and a 16-byte volume header. Its
contents are assembled in sorted guest-path order:

```text
/sdk/bin/tcc.kx
/sdk/include/**
/sdk/lib/tcc/include/**
/sdk/lib/crt0.o
/sdk/lib/libc.a
/sdk/lib/libsoftfloat.a
/sdk/lib/libcompiler_rt.a
```

`/sdk/include` contains the public C SDK headers. TinyCC-private headers,
including `tccdefs.h` and `tcclib.h`, live under its fixed
`/sdk/lib/tcc/include` search root. `crt0.o` enters a hosted C `main` through
`kraft_start`. `libc.a` owns the C library surface, `libsoftfloat.a` implements
the K16 software floating-point ABI, and `libcompiler_rt.a` supplies wide
integer and compiler-generated helper calls.

`c_sdk_v1` names exact bytes, not a moving channel. The build freezes only the
candidate whose SHA-256 equals the checked-in immutable ledger entry:

```text
608c971740392ee5ddc192fe17fc067695285d2885e770ae55bd558f47d19e07
```

Reusing `c_sdk_v1` with different bytes is a hard build/runtime error. A future
SDK revision requires a new identity.

## Supported Compile Command

The accepted in-VM operation is object-only compilation from writable
`storage0` back to writable `storage0`:

```text
/sdk/bin/tcc.kx -c /work/hello.c -o /work/hello.o
```

The compiler reads its executable, headers, and archives from `/sdk`, while
source, intermediates, and output remain under `/work` on `storage0`. Writes to
`/sdk` return `ERROR_READ_ONLY`/`ROFS` and do not mutate the artifact.

The current acceptance test proves real scalar, aggregate, variadic,
wide-integer, `float`, and binary64 helper ABI behavior in the backend corpus.
The native compile proof checks a successful guest exit and a valid K16 ELF32
`ET_REL` output. It then extracts that object and invokes host `k16 link` as an
acceptance oracle with the startup object, SDK archives, and `libkraft.kso`
before running the resulting program in a fresh VM. No host compiler or linker
syscall is exposed to the guest.

## Deliberate Boundaries

The SDK does not provide:

- TinyCC final K16E linking;
- `tcc -run` or another JIT path;
- `dlopen` or dynamic host-library discovery;
- pthreads or a thread runtime;
- a host-side compilation service;
- an in-game editor or project model.

Consequently the supported player loop in this slice ends at a relocatable
object. A future guest linker may extend it to `source -> object -> K16E`, but
must not turn the host acceptance oracle into a runtime dependency.

## Metrics And Verification

Native K16 stats schema v17 adds `lastExitedProgramHeapPages`, `cpuSteps`, and
`gameTicks`. The native compiler proof reports:

```text
k16NativeTinyCcCompile: tccBytes=..., cpuSteps=..., gameTicks=..., hostDuration=... ns, heapPages=...
```

These values measure guest work and resource demand; host duration is a test
observation rather than part of the machine ABI.

Run the focused acceptance slice with:

```bash
./gradlew-sandbox-dev-parallel verifyK16TinyCc
./gradlew-sandbox-dev-parallel verifyK16SdkMount
```
