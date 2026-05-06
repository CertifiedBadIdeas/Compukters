# Rust VM Prototype Findings

## Verified

- Kotlin runner seam passes existing runtime tests.
- Bytecode ABI encoder is deterministic and versioned.
- Rust ABI decoder accepts the documented CKVM v1 byte stream.
- Rust pure VM prototype executes integer addition and emits host-call signals.
- Native runner facade is disabled by default and Kotlin VM remains the default path.

## Not Implemented In This Prototype

- JNI execution bridge.
- Full CKL instruction coverage in Rust.
- Rust-owned strings, event args, IPC, display, or filesystem.
- Native packaging for Minecraft distributions.

## Next Decision

Proceed to JNI bridge only if the ABI and pure VM prototype remain small enough to maintain and the team accepts Kotlin VM fallback as the default runtime path during development.
