# Low VM Basic Blocks And Superinstructions Design

## Goal

Reduce low-level CKIM v5 interpreter overhead by compiling validated instructions into immutable basic blocks and internal superinstructions.

The first implementation slice must keep the public image ABI unchanged. CKIM v5 files still contain the current instruction stream. The Rust loader validates the image, then builds a faster internal execution representation.

## Motivation

The current low VM already removed the old stack/value VM from the benchmark hot path:

- registers are primitive `u64` slots;
- register indexes are predecoded to `usize`;
- static call bindings are precomputed;
- image validation removes repeated runtime bounds checks;
- scheduler control uses hybrid wall-time slices.

The release benchmark still shows the low VM is far behind JVM/Rust native on tight CPU loops. At this point the likely remaining cost is interpreter dispatch and per-instruction frame/IP bookkeeping:

- every instruction performs a top-level `match`;
- every instruction fetch reads the current frame and increments `instruction_pointer`;
- tight loops repeatedly dispatch simple arithmetic operations one by one;
- branches return to the same dispatch machinery even when the target is a known basic block.

The next optimization should attack dispatch frequency without weakening validation or adding Kotlin fallbacks.

## Non-Goals

- Do not add a JIT.
- Do not add unsafe guest memory access.
- Do not change CKIM v5 encoding in the first slice.
- Do not add Kotlin VM fallback paths.
- Do not remove current validation.
- Do not add opcode counters or instruction-level profiling overhead.
- Do not optimize for old stack/register-bank VM versions.

## Considered Approaches

### Approach A: More Single-Instruction Micro-Optimizations

Keep the current instruction loop and continue shaving individual helpers.

Pros:

- lowest implementation risk;
- easy to review;
- keeps current control flow.

Cons:

- diminishing returns;
- still pays one dispatch per bytecode instruction;
- unlikely to close the order-of-magnitude gap.

### Approach B: Public ABI Superinstructions

Add new CKIM opcodes such as `I32AddImm`, `I32DecJumpNonZero`, and workload-specific fused operations.

Pros:

- compact bytecode;
- very fast if the compiler emits good patterns.

Cons:

- changes image ABI immediately;
- pushes optimization complexity into Kotlin compiler/backend;
- makes hand-written binaries learn many more public opcodes too early;
- harder to iterate because every fusion becomes ABI surface.

### Approach C: Internal Basic Blocks And Superinstructions

Keep CKIM v5 unchanged. During Rust load, split each function into basic blocks and compile selected instruction runs into internal operations.

Pros:

- no public ABI change;
- validation remains centralized;
- optimization can evolve freely inside Rust;
- hand-edited binaries still use simple instructions;
- directly targets dispatch overhead.

Cons:

- requires a new internal program representation;
- needs careful IP/block mapping for calls, returns, and errors;
- superinstruction matcher must stay conservative.

## Decision

Use Approach C.

The low VM should compile each validated function into:

```rust
struct LowFunction {
    register_count: usize,
    blocks: Vec<ExecutableBlock>,
    instruction_to_block: Vec<BlockLocation>,
}

struct ExecutableBlock {
    original_start_ip: usize,
    operations: Vec<BlockOperation>,
    terminator: BlockTerminator,
}
```

The VM state stores `block_index` and `operation_index` instead of only raw `instruction_pointer` for the hot loop. Public jump targets still refer to original instruction indexes; validation and lowering translate them into block targets.

## Basic Block Formation

A basic block starts at:

- instruction `0`;
- every jump target;
- the instruction immediately after a conditional jump;
- optionally the instruction after any instruction that can externally suspend in future phases.

A basic block ends with one terminator:

- `Jump`;
- `JumpIfFalse`;
- `Return*`;
- `ReturnUnit`;
- `CallStatic` in the first slice, if keeping call handling outside block fallthrough is simpler;
- future `CallHost`, `Yield`, `Sleep`, or wait instructions.

For the first slice, arithmetic-only blocks can be optimized aggressively while calls and returns remain explicit terminators. This keeps correctness boring, which is exactly what we want around control flow.

## Internal Operations

The first internal operation set should include a direct one-to-one lowering of existing executable operations:

```text
I32Const
I64Const
AddrConst
I32Move
AddrMove
I32Add
I32Sub
I32Mul
I32Div
I32BitXor
I32Shl
I32Shr
I32Lt
Load32
Store32
AddrAdd
```

Then add conservative fused operations that do not change observable behavior:

```text
I32AddImm dst, src, imm
I32SubImm dst, src, imm
I32MulImm dst, src, imm
I32XorImm dst, src, imm
I32ShlImm dst, src, shift
I32ShrImm dst, src, shift
I32LtImm dst, src, imm
I32LinearStep dst, mul_src, mul_imm, add_imm
```

The exact first set should be benchmark-driven. The matcher should prefer small, obvious fusions before larger patterns.

## Superinstruction Matching Rules

Superinstructions are internal only and must be semantics-preserving.

The matcher may fuse an instruction sequence only when:

- all source instructions are inside the same basic block;
- no source instruction can signal `Error`, except where the fused operation preserves the same error condition;
- intermediate registers are not used outside the fused sequence unless the fused operation writes them identically;
- aliasing is safe for `dst == lhs` or `dst == rhs`;
- arithmetic uses the same wrapping behavior as the original instructions;
- division is not fused unless division-by-zero behavior is preserved exactly;
- memory loads/stores are not reordered across other memory operations.

The first slice should avoid memory fusions. Arithmetic and immediate fusions are enough to validate the architecture.

## Constants And Immediates

The current bytecode often loads small constants into registers before loop bodies. The block compiler can track constant registers locally:

```text
I32Const r4, 1
I32Add r3, r3, r4
```

can become:

```text
I32AddImm r3, r3, 1
```

This does not require deleting the original `I32Const` if later code still reads `r4`. If the constant register is only used by the fused instruction and dead afterwards, a later compiler pass can remove it. The first slice should not require full liveness analysis.

## Execution Loop

The hot loop becomes:

```text
while true:
  block = current function/block
  run block.operations sequentially
  execute block.terminator
  check wall-time budget at block boundary or every N operations
```

This reduces:

- current-frame lookup frequency;
- instruction pointer increments;
- branch target decoding;
- dispatch count when operations are fused.

Time slicing remains hybrid:

- checks happen at block boundaries;
- long blocks also check every `TIME_CHECK_INTERVAL` internal operations;
- a maximum block length guard prevents a single giant block from delaying fairness.

## Calls And Returns

The first slice should keep `CallStatic` behavior close to the current implementation:

- a call terminates the current block;
- call setup appends the callee register window;
- callee execution starts at block `0`;
- return restores the caller frame and resumes at the caller continuation block.

Frame state becomes:

```rust
struct LowFrame {
    function_index: usize,
    block_index: usize,
    operation_index: usize,
    return_register: Option<usize>,
    register_base: usize,
}
```

If call-as-terminator is too restrictive later, calls can become in-block operations once continuation handling is proven.

## Safety

Validation remains the trust boundary.

The loader must validate before block compilation:

- register indexes;
- function indexes;
- argument counts;
- jump target ranges;
- explicit function termination;
- memory image sizes.

Block compilation adds structural checks:

- every original instruction belongs to exactly one block;
- every jump target maps to a block start;
- every block has one terminator;
- no block is empty unless it has a valid synthetic terminator;
- every continuation after a conditional branch maps to a block;
- original instruction order is preserved inside blocks.

Runtime memory accesses still use checked `memory_range` helpers. Superinstructions must not bypass memory bounds checks.

## Error Reporting

Errors should reference original instruction locations, not internal block indexes where practical.

Each `BlockOperation` and `BlockTerminator` should keep enough debug metadata:

```rust
original_ip: usize
```

For fused operations, store the first original IP and optionally the source IP range. User-facing errors can say:

```text
function main block starting at instruction 12: division by zero at instruction 17
```

This keeps hand-written binary debugging humane.

## Metrics

Do not reintroduce instruction-level counters by default.

The benchmark already reports elapsed time and run invocations. For this optimization, add optional report metadata only if useful:

- execution engine: `linear` or `basic-block`;
- internal block count;
- internal operation count;
- fused operation count.

These can be image-load statistics, not hot-loop counters.

## Rollout

1. Add block compiler data structures next to the existing `ExecutableInstruction` representation.
2. Add tests for block formation:
   - straight-line function;
   - loop with backward jump;
   - conditional branch with fallthrough;
   - function ending in explicit return;
   - invalid jump still fails before lowering.
3. Add one-to-one block execution with no fusions.
4. Compare benchmark against current release VM to ensure no regression.
5. Add immediate arithmetic fusion:
   - `I32AddImm`;
   - `I32SubImm`;
   - `I32MulImm`;
   - shift immediate.
6. Add block/load metadata to benchmark reports.
7. Add larger benchmark-driven fusions only after immediate fusions are measured.
8. Remove the old linear instruction execution path once block execution passes tests and benchmark parity.

## Success Criteria

- `cargo test` passes.
- `./gradlew :compiler:test` passes.
- `profileComputeVmBenchmarkRelease` produces the same checksums as before.
- Release low VM benchmark improves on at least `integer-mix` and `branch-div`.
- Debug and release reports identify the execution engine and native library profile.
- There is no runtime fallback to the old low VM instruction loop after migration.

## Open Questions

- Should the first implementation keep both internal execution engines temporarily behind tests, or replace the old one immediately after one-to-one block execution works?
- Should block statistics be exposed through JNI metrics or only benchmark metadata?
- Which immediate fusions should be first: generic `*Imm` operations or workload-shaped fusions such as decrement-and-branch?

## Recommendation

Start with one-to-one basic block execution and remove the old instruction loop in the same implementation branch once parity is proven. Then add immediate arithmetic superinstructions as a second commit.

This keeps the architecture honest: no fallback execution path, no public ABI churn, and a clear measurement point between "basic blocks alone" and "basic blocks plus fusions".
