# Bounded Kotlin `when` Support

> Issue: [#528](https://github.com/CertifiedBadIdeas/Compukters/issues/528)

## Context

The minimal K2 lowerer contains an initial `IrWhen` branch-chain implementation because Kotlin represents both source `if` and source `when` with related IR control-flow forms. Existing tests exercise `if`, but they do not define which source-level `when` forms belong to the guest Kotlin subset or prove the resulting artifact on the Rust VM.

Player programs and the shell need readable command and state dispatch. The first supported subset should provide ordinary equality-based `when` without expanding the artifact format or silently accepting Kotlin pattern features the lowerer cannot preserve.

## Goal

Define, lower, and execute a deterministic minimal `when` subset for statements and value expressions, with explicit supported types and unsupported pattern boundaries.

## Supported Source Contract

The admitted forms are:

```kotlin
when (value) {
    1 -> action()
    2 -> otherAction()
    else -> fallback()
}

val result = when {
    firstCondition -> "first"
    secondCondition -> "second"
    else -> "fallback"
}
```

The contract includes:

- subject-based and subjectless forms;
- statement and value-expression forms;
- one equality condition per branch over `Int`, `Char`, `Boolean`, or `String`;
- `else` as the final fallback branch;
- Kotlin frontend exhaustiveness rules for value expressions;
- source-order, first-match behavior.

The subject and every reached condition or branch expression are evaluated according to Kotlin semantics. In particular, a subject is evaluated exactly once and later conditions are not evaluated after a match.

## Lowering

The compiler lowers the supported forms to the existing `Branch`, equality, `Move`, and `Jump` instructions:

1. Preserve the K2-generated subject temporary rather than re-evaluating the source expression.
2. Evaluate branch conditions in source order.
3. Branch to the matching body or the next condition.
4. For a statement, route non-terminating bodies to one join block.
5. For an expression, move the selected branch value into one typed destination register and route to one join block.
6. Lower `else` directly as the final body.

Join blocks are ordinary control-flow joins, not loop headers, and therefore do not introduce loop safepoints or loop-specific accounting.

No new `SwitchI32` compiler-artifact instruction is added in this slice. A linear branch chain is predictable for the small dispatches expected in current system programs and supports all four admitted types consistently. Numeric switch-table lowering remains a later profiling-driven optimization.

## Unsupported Boundaries

The following source constructs are outside this issue:

- comma-separated conditions;
- `in` and `!in` range or collection checks;
- `is` and `!is` type checks;
- destructuring or sealed-hierarchy exhaustiveness extensions;
- arbitrary pattern matching;
- switch-table or string-hash dispatch optimization.

If K2 presents one of these constructs through IR that the bounded lowerer cannot identify as an admitted equality or Boolean condition, compilation returns the existing stable target-subset diagnostic. It must not emit a partially correct artifact.

## Type and Control-Flow Safety

Kotlin frontend typing determines the common result type of a `when` expression. The lowerer allocates one destination register of that type and moves exactly one selected result into it before the join. Artifact data-flow validation must see the destination initialized on every reachable path.

Statement branches may terminate with `return` or a suspending terminator. Non-terminating branches alone jump to the join. Subjectless conditions must have Boolean type; subject-based equality uses the existing scalar or exact string-equality lowering.

## Verification

- Compiler tests cover subject-based statement and expression forms for `Int`, `Char`, `Boolean`, and `String`.
- Compiler tests cover subjectless source-order and first-match behavior.
- Side-effect fixtures prove one-time subject evaluation and short-circuiting after a match.
- A value-expression fixture proves the selected result reaches later code.
- Unsupported pattern forms produce a bounded compiler diagnostic rather than an artifact.
- Compiler-produced artifacts execute through Compukter-VM, including a `when` used after a guest suspend call from #525.
- Existing `if`, loop, and shell lowering tests remain green.

## Out of Scope

- New bytecode instructions or artifact-version changes.
- General Kotlin pattern matching.
- Optimizing large dispatch tables before profiling demonstrates a need.
- Expanding the currently supported value-type set beyond `Int`, `Char`, `Boolean`, and `String`.

