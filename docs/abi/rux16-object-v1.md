# Rux16 Object v1 Relocatable Object ABI

## Status

Status: experimental.

Rux16 relocatable objects use ELF32 little-endian `ET_REL` files. This is the
first object format accepted by the LLVM-facing Rux16 toolchain. LLVM must emit
relocatable objects, not `RUXE`; Rux tooling links those objects into final
`RUXE` bootloader, kernel, or program images.

This boundary keeps the VM independent from LLVM and from object-file details.
The VM must not parse ELF, read relocation records, resolve symbols, or know
whether code came from LLVM, Rux source, or handwritten assembly. ELF parsing,
symbol resolution, relocation application, and final `RUXE` emission belong to
compiler and linker tooling outside the VM.

## Relationship To RUXE

The static pipeline is:

```text
LLVM backend or Rux assembler
  -> Rux16 ELF32 ET_REL object
  -> Rux linker
  -> RUXE fixed image
  -> storage media or guest exec service
  -> VM loader
```

`RUXE` remains the guest-loadable executable container. A loader must reject an
ELF object presented as `RUXE`, and the linker must reject object features it
does not implement. Unsupported relocations are link-time errors.

The current tool entry point is:

```text
rux link --target <boot|kernel|program> <input.o>... -o <output.ruxe>
```

The command accepts Rux16 ELF32 `ET_REL` inputs, resolves static symbols,
applies supported relocations, and emits a validated single-load-section
`RUXE`. It does not emit raw BIOS flash; BIOS images remain a `rux compile`
source-artifact path.

## Freestanding Runtime Boundary

The first freestanding startup object is generated with:

```text
rux runtime rux16-startup -o <startup.o>
```

The startup object defines `_start` and requires an application-defined `main`.
The linker uses `_start` as the final `RUXE` entry symbol. At runtime `_start`
initializes `sp` to the program stack top, calls `main`, then terminates through
the first observable proof path.

The startup object writes the low byte of `main`'s `r0` return value to
`debug::WRITE`, then executes `halt`. This deliberately small behavior is the
initial return-42/add proof boundary. It is not a libc, an OS ABI, or a syscall
surface.

Reserved runtime helper symbol names:

```text
_start          provided by rux16-startup
main            required application entry called by _start
__rux16_memcpy  reserved memory helper, not provided in this slice
__rux16_memset  reserved memory helper, not provided in this slice
__rux16_memmove reserved memory helper, not provided in this slice
```

Missing helper symbols are link-time errors. The linker must not synthesize
helper bodies, fall back to VM hooks, or ask the VM to resolve runtime helpers.
When a backend starts emitting calls to `__rux16_memcpy`, `__rux16_memset`, or
`__rux16_memmove`, those helpers must be added as explicit runtime object code
and covered by linker/runtime tests in the same slice.

## ELF Identification

Rux16 object files use these ELF header values:

```text
EI_CLASS      ELFCLASS32
EI_DATA       ELFDATA2LSB
EI_VERSION    EV_CURRENT
e_type        ET_REL
e_machine     0x5258
e_version     EV_CURRENT
```

`e_machine = 0x5258` is the experimental Rux16 machine value for this ABI
revision. Rux tooling must reject other machine values when linking Rux16
objects. If Rux16 later receives an officially assigned ELF machine value, that
change requires a new object ABI revision.

Section headers and symbol tables use ordinary ELF32 little-endian layout.
There is no dynamic section, program header requirement, interpreter, shared
object mode, or loader-side relocation in v1.

## Sections

The first linker consumes these allocatable sections:

```text
name              type         flags                         alignment
.text.rux16       SHT_PROGBITS SHF_ALLOC | SHF_EXECINSTR      2
.rodata           SHT_PROGBITS SHF_ALLOC                      4
.data             SHT_PROGBITS SHF_ALLOC | SHF_WRITE          4
.bss              SHT_NOBITS   SHF_ALLOC | SHF_WRITE          4
.rux16.attributes SHT_PROGBITS                              1
```

`SHF_ALLOC` marks sections that contribute bytes or zero-fill space to the
linked image. `SHT_NOBITS` is allowed only for `.bss`; the linker assigns RAM
space for it but does not copy bytes from the object file.

`.text.rux16` contains Rux16 instruction bytes and must have even size.
`.rodata` and `.data` contain initialized bytes. `.bss` contributes zero-filled
memory. `.rux16.attributes` is non-allocatable metadata for toolchain checks.

Unknown non-allocatable sections may be ignored only if they have no relocations
that affect allocated output. Unknown `SHF_ALLOC` sections are unsupported in
v1 and must be rejected instead of guessed into the output image.

## Symbols

Symbols use the normal ELF symbol table.

- Defined function symbols point into `.text.rux16` and must be 2-byte aligned.
- Defined object symbols point into `.rodata`, `.data`, or `.bss`.
- Undefined symbols must be resolved by another object or by an explicit runtime
  object supplied to the linker.
- Weak symbols, common symbols, thread-local symbols, dynamic symbols, and
  symbol versioning are unsupported in v1.

The final `RUXE` entry point is selected by the linker profile. Program images
use `_start` unless the command line explicitly chooses another defined symbol.
Bootloader and kernel profiles may use their profile-specific entry symbol, but
the resolved entry address must still satisfy the `RUXE` entry validation rules.

## Relocations

Rux16 objects use ELF32 RELA relocation sections. The addend lives in the
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
0      R_RUX16_NONE        none                          no operation
1      R_RUX16_ABS32       little-endian u32             S + A
2      R_RUX16_CALL32      imm32 low/high extension      S + A
3      R_RUX16_BRANCH4     branch low-nibble offset      ((S + A) - (P + 2)) / 2
```

`R_RUX16_ABS32` is used for absolute data references and for ordinary 32-bit
address constants. The linker must reject the relocation if `S + A` does not
fit in `u32`.

`R_RUX16_CALL32` applies to the immediate payload of a canonical call
materialization sequence:

```text
imm32 scratch, target
call scratch
```

It writes the same 32-bit value as `R_RUX16_ABS32`, but the distinct relocation
kind lets the linker diagnose call-specific placement or relaxation rules in a
future ABI. v1 linkers must not invent a direct-call encoding.

`R_RUX16_BRANCH4` patches the low nibble of `branch_if_zero` or
`branch_if_nonzero`. The computed word offset must be an integer in `-8..=7`;
the linker must reject out-of-range branches. The high twelve bits of the
instruction word must already contain the intended branch opcode and source
register, and the linker must preserve those bits.

Relocations may target allocated sections or undefined symbols that resolve to
allocated sections. Relocations against discarded, debug-only, dynamic, TLS, or
unsupported section kinds are link-time errors.

## Linking Rules

The v1 linker is static only:

1. Validate every input object as Rux16 ELF32 `ET_REL`.
2. Resolve strong symbols and report duplicate strong definitions.
3. Lay out `.text.rux16`, `.rodata`, `.data`, and `.bss` in the selected target
   profile address space.
4. Apply supported RELA relocations.
5. Emit a single-load-section `RUXE` image for the selected ABI kind.

The first `RUXE` format has no zero-fill section, so a v1 object-to-`RUXE`
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
- relocation kinds other than `R_RUX16_NONE`, `R_RUX16_ABS32`,
  `R_RUX16_CALL32`, and `R_RUX16_BRANCH4`.

Unsupported features must fail before final `RUXE` emission. The linker must
not fall back to raw Rux16 bytes, reinterpret unsupported sections as known
sections, or ask the VM to resolve anything at runtime.
