---
layout: default
title: Guest execution and packaged programs
section: contributors
permalink: /ARCHITECTURE/guest-execution/
---

# Guest execution and packaged programs

[← Architecture](../architecture.md)

* On this page
{:toc}

Boot, shell, `kotlinc`, `edit`, and `vmbench` are ordinary no-std Kotlin programs packaged as extensionless executables in `/rom`.
Shell owns line editing, authoritative echo, prompts, and direct built-ins. A non-absolute external command resolves
first to `/home/<name>` and, only when that path is absent, falls back to `/rom/<name>`; an absolute command is used as
given.

`Process.run(path)` and `Process.run(path, args)` execute one verified extensionless artifact as a foreground child and
return `ProcessResult.Exited(code)` or `ProcessResult.Failed(reason, diagnostic)`. `Process.exit(code)` terminates the
current process. Guest code does not select an explicit capability mask when starting a child.

## Function values and tasks

`Tasks.launch` starts a supported `() -> Unit` value in a bounded cooperative task. The compiler keeps direct
top-level references as static spawns; other function values cross the existing spawn boundary as one managed
reference into a private Guest trampoline, which invokes `Function0<Unit>` through normal dynamic dispatch. The child
frame retains that reference after the launching function returns, and shared capture cells remain VM-owned.
Ordinary higher-order Guest calls admit non-null function values whose parameter and result types are supported by
Guest Kotlin. The compiler creates one managed interface per concrete signature and lowers calls through the same
interface-call instruction, preserving unboxed primitive values and ordinary reference ownership. There is no special
arity transition at 22 or 23 arguments in the Guest artifact.

## Inline lowering

The emitter also understands normalized Kotlin IR returnable blocks: a local return writes the typed block result
and jumps to its continuation; a return targeting the enclosing function remains a function return. Nested targets,
Unit/Nothing paths and terminated branches use the existing artifact control-flow instructions. A terminating inline
condition creates no successor branches; earlier alternatives retain their continuation. Unknown return
targets produce located diagnostics. Both compiler entry paths invoke `GuestInlineNormalization` from
`MinimalScriptLowering`, before capture and layout discovery. Only session-admitted project and source-library IR
is expanded; canonical platform/intrinsic calls preserve their symbols and remain atomic. Unsupported shapes and
unavailable or unadmitted inline bodies produce located TARGET diagnostics. Original source ownership is recorded
before copying so diagnostics can retain source-library paths.
The internal `GuestInlineSpecialization` pass makes independent symbol-remapped copies of top-level generic inline
bodies at concrete call-site types before common inlining. This preserves scalar inputs instead of erasing them to
Any?, including nested forwarding and source-library bodies. Copies are cached by declaration and concrete type
arguments. Reified, suspend and non-top-level inline declarations, unowned type parameters, and expansion beyond
256 variants or 64 active specialization levels are rejected. Normalization covers bodies, parameter defaults and
initializers, retaining runtime-referenced definitions while discarding unused templates. Enclosing supported generic
function/class parameters remain scoped through body copying and are resolved by the existing emitter specialization;
normalization does not erase them to Any or replace the emitter's generic ownership.
`GuestInlineExpansionGuard` checks executable roots before specialization or common inlining. It rejects inline
dependency cycles and depth beyond 64, and caps cumulative projected expansion work at 1,000,000 units. Each source
node costs one unit; each call additionally charges expanded callee work multiplied by one plus expanded argument
work. A callee's body and default-expression work are first charged together with a multiplier of one plus default
work, covering callback defaults substituted at multiple body nodes. Defaults are included even for supplied arguments.
Memoization avoids repeated analysis, but each call site still pays its full charge. Arithmetic checks reject overflow
instead of wrapping. Preflight failure leaves source IR unchanged and never falls back to an ordinary call.
This deliberately overcharges callback substitution and retained arguments; it is not an exact final-node estimate
or a calibrated capacity budget. Callback-taking collection extensions use this same source-only normalization,
including non-local returns; stored callbacks keep managed dispatch. `testKotlinInlineBlocksVmConformance`,
function-values, generic-library and transparent-call conformance cover the production path, including host-call
suspension through expanded callbacks.

## Managed closures

Normalized rich lambdas without reflection targets use their invoke bodies with the existing closure layout and
capture-cell analysis. Stored/noinline callbacks and escaping crossinline wrappers retain managed ownership;
source-reference adaptation restrictions still apply. Closure classes inherit runtime Any and implement the concrete
function-shape interface, preserving referential identity comparisons across aliases.
Nested closures capture free values through their enclosing closure objects. The compiler scans assignments across
functions, class initialization and nested closures. Captured locals with no assignments after initialization are
stored directly in the closure; reference captures preserve referent identity. Reassigned captured locals retain a
single VM-owned typed cell shared across nesting levels and sibling closures, including after the enclosing call returns.
An unbound top-level Guest function reference uses the same managed function-value interface and a capture-free
closure whose `invoke` method calls the referenced project function. An inferred `KFunction` value has the same Guest
call behavior; reflection is not part of the Guest function-value contract.

## Constructors and accessors

A reference to an admitted Guest primary constructor uses a capture-free managed closure. Its `invoke` method allocates
the object, calls its constructor function with that receiver, and returns the object, without a constructor-specific
VM type or instruction. Direct construction follows the same path. The constructor calls its superclass constructor
on the same receiver before evaluating backed properties and `init` blocks in declaration order.
Constructor-backed Guest `var` properties use the same managed instance fields as `val` properties. Default setter
calls lower to verified `field_set` instructions after evaluating the receiver and assigned value in source order.
For primary constructors, the compiler evaluates explicitly supplied arguments once in call-site order, then omitted
default expressions in parameter order. Earlier constructor parameters are available while evaluating later defaults.
The existing full-arity constructor function receives the resolved values, so field initialization and `init` blocks
observe the same values and object identity. A constructor reference retains its full arity when the expected function
type supplies every parameter. When the expected type omits supported trailing defaults, K2 supplies an adapter
function whose body calls that constructor. The compiler lowers the adapter as a managed closure, so each invocation
evaluates defaults and allocates a new object without another VM instruction or ABI change.
Source-defined class getters and setters lower as ordinary non-suspending instance methods, so overridden accessors
use the existing virtual dispatch table. A computed property has no managed field; `field` inside a backed accessor
lowers to verified `field_get` or `field_set` on its receiver. Non-overriding final default accessors retain direct
field access.
Abstract class and interface properties contribute getter and setter method signatures without managed fields.
Implementing classes provide backed or computed accessors, selected through the existing virtual or interface call
instruction when the property is used through a base reference.

## Interface dispatch

Interface method and computed property bodies use the same function records and bytecode as class methods. During
artifact admission the VM first searches the class inheritance chain, then chooses the most specific matching
interface declaration. An abstract redeclaration suppresses an inherited default; missing or ambiguous concrete
targets reject admission. Dispatch entries are shared by all VMs using the admitted execution image.
An explicit `super<Interface>` call in an override resolves the concrete interface body at compile time and emits a
direct call. If the named interface only inherits the body, K2's fake override resolves to that concrete ancestor.
Abstract or ambiguous targets publish no artifact; the direct call retains the original receiver and skips the
override's dynamic dispatch entry.
A bound Guest instance-method reference evaluates its receiver once and stores that object in the closure. Its `invoke`
method loads the stored receiver and uses the target method's static, virtual, or interface dispatch mode.
An unbound instance-method reference has no receiver field; its first function parameter supplies the receiver for
the same dispatch path.

## In-computer tools

`/rom/kotlinc source.kt [-o output]` accepts exactly one source file today; its default output is the source basename
without `.kt`. This single-file in-computer command is distinct from the IDE and compiler protocol, which support
bounded multi-file project snapshots.

`/rom/edit <path>` is a nano-like 51x19 editor backed by one managed 4096-unit `CharArray` gap buffer. Cursor motion and
deletion preserve UTF-16 surrogate pairs, CRLF input is normalized to LF, Tab inserts four spaces, Enter inherits
leading indentation, and the viewport scrolls in both axes. Ctrl+S writes through Rust-owned `FileSystem.writeText`;
Ctrl+X exits directly when clean or opens a Y/N/Escape save prompt when dirty. The buffer, source, and compiled artifact
belong to the computer filesystem, while terminal state belongs only to the current VM lifetime. The playable
in-computer loop is `edit demo.kt` -> `kotlinc demo.kt` -> `demo`; source and artifact survive machine reload and remain
isolated by `ComputerId`.

## Benchmark programs

`/rom/vmbench cpu <rounds>` runs the documented deterministic, allocation-free integer/branch workload through the
ordinary foreground process and VM quota path. `/rom/vmbench redstone <rounds>` normalizes the local top output to zero
and then emits one acknowledged weak-power `15 -> 0` pulse per round; every transition suspends through the ordinary
Guest redstone API until its physical server-thread commit is confirmed. These workloads exist to measure aggregate
in-world runtime cost and do not own a timing capability, privileged execution budget, or benchmark-only host path.
See the
[in-world VM benchmark guide](https://certifiedbadideas.github.io/Compukters/VM-BENCHMARK/) for the controlled scaling procedure.
An operator-only harness can run up to 4096 internal headless artifacts through the same verified session and actor
scheduler, including a phased capacity run that settles actors at terminal input, observes 100 idle ticks, samples
their existing resource counters once, and wakes them with an ordinary Text event. It can also dispatch either ordinary
`/rom/vmbench` workload to at most 1000 loaded physical computers in a bounded area. The harness owns no persistent
computer identity, never loads chunks, and does not provide a guest-visible fleet protocol.
