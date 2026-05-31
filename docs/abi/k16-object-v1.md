# K16 Object v1 Relocatable Object ABI

## Status

Status: experimental.

K16 relocatable objects use ELF32 little-endian `ET_REL` files. This is the
first object format accepted by the LLVM-facing K16 toolchain. LLVM must emit
relocatable objects, not `K16E`; K16 tooling links those objects into final
`K16E` bootloader, kernel, or program images.

This boundary keeps the VM independent from LLVM and from object-file details.
The VM must not parse ELF, read relocation records, resolve symbols, or know
whether code came from LLVM, Rux source, or handwritten assembly. ELF parsing,
symbol resolution, relocation application, and final `K16E` emission belong to
compiler and linker tooling outside the VM.

## Relationship To K16E

The static pipeline is:

```text
LLVM backend or handwritten K16 object fixtures
  -> K16 ELF32 ET_REL object
  -> K16 linker
  -> K16E fixed image
  -> storage media or guest exec service
  -> VM loader
```

`K16E` remains the guest-loadable executable container. A loader must reject an
ELF object presented as `K16E`, and the linker must reject object features it
does not implement. Unsupported relocations are link-time errors.

The current tool entry point is:

```text
k16 link --target <boot|kernel|program> <input.ko>... -o <output.kx>
```

The command accepts K16 ELF32 `ET_REL` inputs, resolves static symbols,
applies supported relocations, and emits a validated single-load-section
`K16E`. It does not emit raw BIOS flash; BIOS images are moving to Rust-built
firmware artifacts rather than the retired public `rux compile` path.

## Freestanding Runtime Boundary

The first freestanding startup object is generated with:

```text
k16 runtime k16-startup -o <startup.ko>
k16 runtime k16-memory-helpers -o <helpers.ko>
```

The startup object defines `_start` and requires an application-defined `main`.
The linker uses `_start` as the final `K16E` entry symbol. At runtime `_start`
initializes `sp` to the program stack top, calls `main`, then terminates through
the first observable proof path.

The startup object writes the low byte of `main`'s `r0` return value to
`debug::WRITE`, then executes `halt`. This deliberately small behavior is the
initial return-42/add proof boundary. It is not a libc, an OS ABI, or a syscall
surface.

The memory and integer helper object is built from the guest Rust `#![no_core]`
runtime source at `rust/guest/k16-rt/src/no_core_helpers.rs`. Building it requires
`K16_RUSTC` to point at the custom rustc that contains the K16 LLVM target and
`K16_LLVM_BIN_DIR` to point at the K16 LLVM tools used to lower the generated
LLVM IR into an ELF object. `K16_RUST_TARGET_JSON` can override the target spec;
otherwise the repo target spec at `tools/k16-unknown-kraftos.json` is used.

Runtime helper symbol names:

```text
`_start`          provided by k16-startup
`main`            required application entry called by _start
`__k16_memcpy`  provided by k16-memory-helpers
`__k16_memset`  provided by k16-memory-helpers
`__k16_memmove` provided by k16-memory-helpers
`__divdi3`       provided by k16-memory-helpers
`__udivdi3`      provided by k16-memory-helpers
`__moddi3`       provided by k16-memory-helpers
`__umoddi3`      provided by k16-memory-helpers
`__ashldi3`      provided by k16-memory-helpers
`__lshrdi3`      provided by k16-memory-helpers
`__ashrdi3`      provided by k16-memory-helpers
```

Missing helper symbols are link-time errors. The linker must not synthesize
helper bodies, fall back to VM hooks, or ask the VM to resolve runtime helpers.
Callers link helper support by passing the Rust-built helper object as an
ordinary `k16 link` input beside startup and application objects. The helper
object is not implicit.

## ELF Identification

K16 object files use these ELF header values:

```text
EI_CLASS      ELFCLASS32
EI_DATA       ELFDATA2LSB
EI_VERSION    EV_CURRENT
e_type        ET_REL
e_machine     0x5258
e_version     EV_CURRENT
```

`e_machine = 0x5258` is the experimental K16 machine value for this ABI
revision. K16 tooling must reject other machine values when linking K16
objects. If K16 later receives an officially assigned ELF machine value, that
change requires a new object ABI revision.

Section headers and symbol tables use ordinary ELF32 little-endian layout.
There is no dynamic section, program header requirement, interpreter, shared
object mode, or loader-side relocation in v1.

## Sections

The first linker consumes these allocatable sections:

```text
name              type         flags                         alignment
.text.k16       SHT_PROGBITS SHF_ALLOC | SHF_EXECINSTR      2
.rodata           SHT_PROGBITS SHF_ALLOC                      4
.data             SHT_PROGBITS SHF_ALLOC | SHF_WRITE          4
.bss              SHT_NOBITS   SHF_ALLOC | SHF_WRITE          4
.k16.attributes SHT_PROGBITS                              1
```

`SHF_ALLOC` marks sections that contribute bytes or zero-fill space to the
linked image. `SHT_NOBITS` is allowed only for `.bss`; the linker assigns RAM
space for it but does not copy bytes from the object file.

`.text.k16` contains K16 instruction bytes and must have even size.
`.rodata` and `.data` contain initialized bytes. `.bss` contributes zero-filled
memory. `.k16.attributes` is non-allocatable metadata for toolchain checks.

Unknown non-allocatable sections may be ignored only if they have no relocations
that affect allocated output. Unknown `SHF_ALLOC` sections are unsupported in
v1 and must be rejected instead of guessed into the output image.

## Symbols

Symbols use the normal ELF symbol table.

- Defined function symbols point into `.text.k16` and must be 2-byte aligned.
- Defined object symbols point into `.rodata`, `.data`, or `.bss`.
- Undefined symbols must be resolved by another object or by an explicit runtime
  object supplied to the linker.
- `STT_FILE` and other `SHN_ABS` metadata symbols do not define output payload
  addresses and are ignored by the linker when building the global symbol map.
- Weak symbols, common symbols, thread-local symbols, dynamic symbols, and
  symbol versioning are unsupported in v1.

The final `K16E` entry point is selected by the linker profile. Program images
use `_start` unless the command line explicitly chooses another defined symbol.
Bootloader and kernel profiles may use their profile-specific entry symbol, but
the resolved entry address must still satisfy the `K16E` entry validation rules.

## Relocations

K16 objects use ELF32 RELA relocation sections. The addend lives in the
relocation record, not in the bytes being patched.

Relocation expression terms:

```text
S  resolved symbol address
A  signed relocation addend
P  address of the relocated field
```

Supported relocation kinds:

```text
value  name                field                         calculation
0      R_K16_NONE        none                          no operation
1      R_K16_ABS32       little-endian u32             S + A
2      R_K16_CALL32      imm32 low/high extension      S + A
3      R_K16_BRANCH4     branch low-nibble offset      ((S + A) - (P + 2)) / 2
```

`R_K16_ABS32` is used for absolute data references and for ordinary 32-bit
address constants. The linker must reject the relocation if `S + A` does not
fit in `u32`.

`R_K16_CALL32` applies to the immediate payload of a canonical call
materialization sequence:

```text
imm32 scratch, target
call scratch
```

It writes the same 32-bit value as `R_K16_ABS32`, but the distinct relocation
kind lets the linker diagnose call-specific placement or relaxation rules in a
future ABI. v1 linkers must not invent a direct-call encoding.

`R_K16_BRANCH4` patches the low nibble of `branch_if_zero` or
`branch_if_nonzero`. The computed word offset must be an integer in `-8..=7`;
the linker must reject out-of-range branches. The high twelve bits of the
instruction word must already contain the intended branch opcode and source
register, and the linker must preserve those bits.

Relocations may target allocated sections or undefined symbols that resolve to
allocated sections. Relocations against discarded, debug-only, dynamic, TLS, or
unsupported section kinds are link-time errors.

## Linking Rules

The v1 linker is static only:

1. Validate every input object as K16 ELF32 `ET_REL`.
2. Resolve strong symbols and report duplicate strong definitions.
3. Lay out `.text.k16`, `.rodata`, `.data`, and `.bss` in the selected target
   profile address space.
4. Apply supported RELA relocations.
5. Emit a single-load-section `K16E` image for the selected ABI kind.

The first `K16E` format has no zero-fill section, so a v1 object-to-`K16E`
linker must either include `.bss` in the emitted load payload as zero bytes or
reject `.bss` for profiles that cannot represent it yet. It must not rely on a
loader-side zero-fill relocation step.

## Unsupported Features

The v1 object ABI rejects:

- ELF64 and big-endian ELF;
- `ET_EXEC`, `ET_DYN`, archives as direct linker input, and shared libraries;
- program headers as semantic input;
- dynamic linking and loader-side relocation;
- GOT, PLT, PIC, TLS, exceptions, unwind tables, and debug relocation
  semantics;
- mergeable allocated sections, COMDAT groups, common symbols, and weak
  resolution semantics;
- relocation kinds other than `R_K16_NONE`, `R_K16_ABS32`,
  `R_K16_CALL32`, and `R_K16_BRANCH4`.

Unsupported features must fail before final `K16E` emission. The linker must
not fall back to raw K16 bytes, reinterpret unsupported sections as known
sections, or ask the VM to resolve anything at runtime.
