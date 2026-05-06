# Rust VM JNI Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep a Rust VM instance alive across JNI calls so Kotlin can handle host calls and resume native execution.

**Architecture:** Add primitive value decode/encode support, add Rust VM suspension state and `resume_with`, expose an opaque JNI handle lifecycle, then make `NativeVmRunner` loop like `KotlinVmRunner` for `HostCall`, `Yield`, `Sleep`, and `Pause`. Kotlin remains default; Rust remains explicit opt-in.

**Tech Stack:** Kotlin/JVM, Gradle, Rust 2021, `jni = "0.21"`, CKVM bytecode ABI v1.

---

## File Structure

- Modify `native/ckl-vm/src/signal.rs`: expose primitive value `encode_value`/`decode_value`; keep signal encoding compatible.
- Modify `native/ckl-vm/src/vm.rs`: add `VmState`, `VmError`, and `resume_with`.
- Modify `native/ckl-vm/src/runner.rs`: add persistent lifecycle helper `NativeVmHandle` for tests and JNI.
- Modify `native/ckl-vm/src/jni.rs`: add `createNative`, `runUntilSignalForHandleNative`, `resumeWithNative`, and `freeNative` exports while keeping the old one-shot export for compatibility.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`: add primitive value encoding and conversion to/from `VmValue`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: add handle lifecycle wrappers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt`: replace one-shot execution with a signal loop.
- Modify tests in `native/ckl-vm/tests/*.rs` and `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/*.kt`.

---

### Task 1: Rust primitive value codec

**Files:**
- Modify: `native/ckl-vm/src/signal.rs`
- Test: `native/ckl-vm/tests/signal_codec.rs`

- [ ] **Step 1: Write failing codec tests**

Add tests to `native/ckl-vm/tests/signal_codec.rs`:

```rust
use ckl_vm::signal::{decode_value, encode_error, encode_signal, encode_value};

#[test]
fn decodes_primitive_values_for_resume() {
	assert_eq!(decode_value(&[0]).unwrap(), VmValue::Unit);
	assert_eq!(decode_value(&[1]).unwrap(), VmValue::Null);
	assert_eq!(decode_value(&[2, 1]).unwrap(), VmValue::Bool(true));
	assert_eq!(decode_value(&[3, 7, 0, 0, 0]).unwrap(), VmValue::Int(7));
	assert_eq!(decode_value(&[4, 9, 0, 0, 0, 0, 0, 0, 0]).unwrap(), VmValue::Long(9));
	assert_eq!(decode_value(&[5, 2, 0, 0, 0, b'o', b'k']).unwrap(), VmValue::String("ok".to_string()));
}

#[test]
fn encodes_primitive_values_for_resume() {
	assert_eq!(encode_value(&VmValue::Unit), vec![0]);
	assert_eq!(encode_value(&VmValue::Null), vec![1]);
	assert_eq!(encode_value(&VmValue::Bool(false)), vec![2, 0]);
	assert_eq!(encode_value(&VmValue::Int(42)), vec![3, 42, 0, 0, 0]);
	assert_eq!(encode_value(&VmValue::Long(42)), vec![4, 42, 0, 0, 0, 0, 0, 0, 0]);
	assert_eq!(encode_value(&VmValue::String("x".to_string())), vec![5, 1, 0, 0, 0, b'x']);
}

#[test]
fn rejects_invalid_resume_value_bytes() {
	assert!(decode_value(&[]).unwrap_err().contains("unexpected end"));
	assert!(decode_value(&[99]).unwrap_err().contains("unknown native VM value tag"));
}
```

- [ ] **Step 2: Verify RED**

Run: `cd native/ckl-vm && cargo test --test signal_codec -- --nocapture`

Expected: FAIL because `decode_value` and `encode_value` are not public yet.

- [ ] **Step 3: Implement codec functions**

In `native/ckl-vm/src/signal.rs`, make primitive value encoding public and add a small reader:

```rust
pub fn encode_value(value: &VmValue) -> Vec<u8> {
	let mut bytes = Vec::new();
	write_value(&mut bytes, value);
	bytes
}

pub fn decode_value(bytes: &[u8]) -> Result<VmValue, String> {
	let mut reader = ValueReader { bytes, offset: 0 };
	let value = reader.value()?;
	if reader.offset != bytes.len() {
		return Err("trailing bytes after native VM value".to_string());
	}
	Ok(value)
}
```

Keep the existing signal layout unchanged by making `encode_signal` call `write_value`.

- [ ] **Step 4: Verify GREEN**

Run: `cd native/ckl-vm && cargo test --test signal_codec -- --nocapture`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add native/ckl-vm/src/signal.rs native/ckl-vm/tests/signal_codec.rs
git commit -m "feat: decode native VM primitive values"
```

---

### Task 2: Rust VM resume state

**Files:**
- Modify: `native/ckl-vm/src/vm.rs`
- Test: `native/ckl-vm/tests/pure_vm.rs`

- [ ] **Step 1: Write failing resume tests**

Add tests to `native/ckl-vm/tests/pure_vm.rs`:

```rust
#[test]
fn resumes_after_host_call_with_return_value() {
	let module = Module {
		name: "main".to_string(),
		entry_function_index: 0,
		records: vec![],
		classes: vec![],
		functions: vec![Function {
			name: "main".to_string(),
			parameters: vec![],
			locals: vec![],
			return_type: "Int".to_string(),
			instructions: vec![
				Instruction::CallBuiltin { module_name: "display".to_string(), function_name: "primary".to_string(), argument_count: 0 },
				Instruction::PushInt(1),
				Instruction::Binary(0),
				Instruction::Return,
			],
		}],
	};
	let mut vm = VmInstance::new(module, 64);

	assert_eq!(vm.run_until_signal().unwrap(), VmSignal::HostCall { module_name: "display".to_string(), function_name: "primary".to_string(), arguments: vec![] });
	vm.resume_with(VmValue::Int(7)).unwrap();
	assert_eq!(vm.run_until_signal().unwrap(), VmSignal::Halt(VmValue::Int(8)));
}

#[test]
fn rejects_invalid_resume_order() {
	let module = Module {
		name: "main".to_string(),
		entry_function_index: 0,
		records: vec![],
		classes: vec![],
		functions: vec![Function {
			name: "main".to_string(),
			parameters: vec![],
			locals: vec![],
			return_type: "Int".to_string(),
			instructions: vec![Instruction::PushInt(1), Instruction::Return],
		}],
	};
	let mut vm = VmInstance::new(module, 64);

	assert!(vm.resume_with(VmValue::Unit).unwrap_err().to_string().contains("not waiting for resume"));
	assert_eq!(vm.run_until_signal().unwrap(), VmSignal::Halt(VmValue::Int(1)));
	assert!(vm.run_until_signal().unwrap_err().to_string().contains("halted"));
}
```

- [ ] **Step 2: Verify RED**

Run: `cd native/ckl-vm && cargo test --test pure_vm -- --nocapture`

Expected: FAIL because `run_until_signal` returns `VmSignal`, not `Result`, and `resume_with` does not exist.

- [ ] **Step 3: Implement state and resume**

In `native/ckl-vm/src/vm.rs`:

```rust
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum VmError {
	#[error("native VM is waiting for resume")]
	WaitingForResume,
	#[error("native VM is not waiting for resume")]
	NotWaitingForResume,
	#[error("native VM is halted")]
	Halted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum VmState {
	Ready,
	WaitingForResume,
	Halted,
}
```

Add `state: VmState` to `VmInstance`, return `Result<VmSignal, VmError>` from `run_until_signal`, set `WaitingForResume` for `HostCall`, `Yield`, and `Sleep`, set `Halted` on final `Halt`, and add:

```rust
pub fn resume_with(&mut self, value: VmValue) -> Result<(), VmError> {
	if self.state != VmState::WaitingForResume {
		return Err(VmError::NotWaitingForResume);
	}
	self.current_frame_mut().stack.push(value);
	self.state = VmState::Ready;
	Ok(())
}
```

Update existing tests to call `.unwrap()` on `run_until_signal()`.

- [ ] **Step 4: Verify GREEN**

Run: `cd native/ckl-vm && cargo test --test pure_vm -- --nocapture`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add native/ckl-vm/src/vm.rs native/ckl-vm/tests/pure_vm.rs
git commit -m "feat: resume Rust VM after host calls"
```

---

### Task 3: Rust persistent lifecycle helper

**Files:**
- Modify: `native/ckl-vm/src/runner.rs`
- Test: `native/ckl-vm/tests/runner.rs`

- [ ] **Step 1: Write failing lifecycle test**

Add to `native/ckl-vm/tests/runner.rs`:

```rust
use ckl_vm::runner::NativeVmHandle;

#[test]
fn lifecycle_runs_host_call_resume_and_halt() {
	let bytes = host_call_then_add_module_bytes();
	let mut vm = NativeVmHandle::create(&bytes, 64).unwrap();

	let signal = vm.run_until_signal();
	assert_eq!(signal[0], 4);
	vm.resume_with_value_bytes(&[3, 7, 0, 0, 0]).unwrap();
	assert_eq!(vm.run_until_signal(), vec![0, 3, 8, 0, 0, 0]);
}

fn host_call_then_add_module_bytes() -> Vec<u8> {
	let mut bytes = Vec::new();
	bytes.extend_from_slice(b"CKVM");
	bytes.push(1);
	write_string(&mut bytes, "test");
	write_i32(&mut bytes, 0);
	write_i32(&mut bytes, 0); // records
	write_i32(&mut bytes, 0); // classes
	write_i32(&mut bytes, 1); // functions
	write_string(&mut bytes, "main");
	write_i32(&mut bytes, 0); // parameters
	write_i32(&mut bytes, 0); // locals
	write_string(&mut bytes, "Int");
	write_i32(&mut bytes, 4); // instructions
	bytes.push(14); // CallBuiltin
	write_string(&mut bytes, "display");
	write_string(&mut bytes, "primary");
	write_i32(&mut bytes, 0);
	bytes.push(1); // PushInt
	write_i32(&mut bytes, 1);
	bytes.push(27); // Binary
	bytes.push(0); // ADD
	bytes.push(29); // Return
	bytes
}
```

- [ ] **Step 2: Verify RED**

Run: `cd native/ckl-vm && cargo test --test runner -- --nocapture`

Expected: FAIL because `NativeVmHandle` does not exist.

- [ ] **Step 3: Implement helper**

In `native/ckl-vm/src/runner.rs`, add:

```rust
pub struct NativeVmHandle {
	vm: VmInstance,
}

impl NativeVmHandle {
	pub fn create(bytecode: &[u8], instruction_budget: usize) -> Result<Self, String> {
		let module = decode_module(bytecode).map_err(|error| error.to_string())?;
		Ok(Self { vm: VmInstance::new(module, instruction_budget.max(1)) })
	}

	pub fn run_until_signal(&mut self) -> Vec<u8> {
		match catch_unwind(AssertUnwindSafe(|| self.vm.run_until_signal())) {
			Ok(Ok(signal)) => encode_signal(&signal),
			Ok(Err(error)) => encode_error(error.to_string()),
			Err(payload) => encode_error(panic_message(payload)),
		}
	}

	pub fn resume_with_value_bytes(&mut self, value: &[u8]) -> Result<(), String> {
		let value = crate::signal::decode_value(value)?;
		self.vm.resume_with(value).map_err(|error| error.to_string())
	}
}
```

Update `run_bytecode_until_signal` to unwrap the new `Result` by encoding VM errors as error signals.

- [ ] **Step 4: Verify GREEN**

Run: `cd native/ckl-vm && cargo test --test runner -- --nocapture`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add native/ckl-vm/src/runner.rs native/ckl-vm/tests/runner.rs
git commit -m "feat: add Rust VM lifecycle helper"
```

---

### Task 4: JNI handle lifecycle

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Test command validates by building native library; Kotlin JNI smoke follows in Task 6.

- [ ] **Step 1: Build current library first**

Run: `./gradlew buildRustVmNativeLibrary`

Expected: PASS before changes.

- [ ] **Step 2: Add JNI exports**

In `native/ckl-vm/src/jni.rs`, keep the old `runUntilSignalNative` export and add exports matching Kotlin methods:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_createNative(
	mut env: JNIEnv<'_>,
	_class: JClass<'_>,
	bytecode: JByteArray<'_>,
	instruction_budget: jint,
) -> jlong {
	let bytecode = match env.convert_byte_array(&bytecode) {
		Ok(bytecode) => bytecode,
		Err(error) => {
			let _ = env.throw_new("java/lang/IllegalArgumentException", format!("Cannot read CKVM bytecode: {error}"));
			return 0;
		}
	};
	match NativeVmHandle::create(&bytecode, instruction_budget.max(1) as usize) {
		Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
		Err(error) => {
			let _ = env.throw_new("java/lang/IllegalArgumentException", error);
			0
		}
	}
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_runUntilSignalForHandleNative(
	mut env: JNIEnv<'_>,
	_class: JClass<'_>,
	handle: jlong,
) -> jbyteArray {
	let handle = match handle_mut(&mut env, handle) {
		Some(handle) => handle,
		None => return null_mut(),
	};
	let signal = handle.run_until_signal();
	byte_array_or_throw(&mut env, &signal)
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_resumeWithNative(
	mut env: JNIEnv<'_>,
	_class: JClass<'_>,
	handle: jlong,
	value: JByteArray<'_>,
) {
	let handle = match handle_mut(&mut env, handle) {
		Some(handle) => handle,
		None => return,
	};
	let value = match env.convert_byte_array(&value) {
		Ok(value) => value,
		Err(error) => {
			let _ = env.throw_new("java/lang/IllegalArgumentException", format!("Cannot read native VM resume value: {error}"));
			return;
		}
	};
	if let Err(error) = handle.resume_with_value_bytes(&value) {
		let _ = env.throw_new("java/lang/IllegalStateException", error);
	}
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_freeNative(
	_env: JNIEnv<'_>,
	_class: JClass<'_>,
	handle: jlong,
) {
	if handle != 0 {
		unsafe { drop(Box::from_raw(handle as *mut NativeVmHandle)) };
	}
}
```

Use helper functions for Java exception throwing and handle validation to keep the file small.

- [ ] **Step 3: Verify native build**

Run: `./gradlew buildRustVmNativeLibrary`

Expected: PASS and `native/ckl-vm/target/debug/libckl_vm.so` exists.

- [ ] **Step 4: Verify exported symbols**

Run: `nm -D native/ckl-vm/target/debug/libckl_vm.so | grep 'NativeVmBindings_.*Native'`

Expected: output includes `createNative`, `runUntilSignalForHandleNative`, `resumeWithNative`, `freeNative`, and `runUntilSignalNative`.

- [ ] **Step 5: Commit**

Run:

```bash
git add native/ckl-vm/src/jni.rs
git commit -m "feat: expose Rust VM JNI lifecycle"
```

---

### Task 5: Kotlin lifecycle bindings and primitive value conversion

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignalTest.kt`

- [ ] **Step 1: Write failing Kotlin codec tests**

Add tests to `NativeVmSignalTest.kt`:

```kotlin
@Test
fun primitiveNativeValuesConvertToRuntimeValues() {
    assertEquals(VmValue.UnitValue, NativeVmValue.UnitValue.toVmValue("system", "log"))
    assertEquals(VmValue.NullValue, NativeVmValue.NullValue.toVmValue("system", "log"))
    assertEquals(VmValue.BoolValue(true), NativeVmValue.BoolValue(true).toVmValue("display", "isAttached"))
    assertEquals(VmValue.IntValue(7), NativeVmValue.IntValue(7).toVmValue("display", "primary"))
    assertEquals(VmValue.LongValue(9), NativeVmValue.LongValue(9).toVmValue("system", "currentTick"))
    assertEquals(VmValue.StringValue("ok"), NativeVmValue.StringValue("ok").toVmValue("system", "label"))
}

@Test
fun primitiveRuntimeValuesEncodeForResume() {
    assertContentEquals(byteArrayOf(0), VmValue.UnitValue.toNativeBytes("yield", "yield"))
    assertContentEquals(byteArrayOf(1), VmValue.NullValue.toNativeBytes("test", "null"))
    assertContentEquals(byteArrayOf(2, 1), VmValue.BoolValue(true).toNativeBytes("display", "isAttached"))
    assertContentEquals(byteArrayOf(3, 7, 0, 0, 0), VmValue.IntValue(7).toNativeBytes("display", "primary"))
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :modules:compiler:test --tests '*NativeVmSignalTest'`

Expected: FAIL because conversion/encoding methods do not exist.

- [ ] **Step 3: Implement conversion and lifecycle bindings**

In `NativeVmSignal.kt`, add internal extension functions:

```kotlin
internal fun NativeVmValue.toVmValue(moduleName: String, functionName: String): VmValue =
    when (this) {
        NativeVmValue.UnitValue -> VmValue.UnitValue
        NativeVmValue.NullValue -> VmValue.NullValue
        is NativeVmValue.BoolValue -> VmValue.BoolValue(value)
        is NativeVmValue.IntValue -> VmValue.IntValue(value)
        is NativeVmValue.LongValue -> VmValue.LongValue(value)
        is NativeVmValue.StringValue -> VmValue.StringValue(value)
    }

internal fun VmValue.toNativeBytes(moduleName: String, functionName: String): ByteArray =
    when (this) {
        VmValue.UnitValue -> byteArrayOf(0)
        VmValue.NullValue -> byteArrayOf(1)
        is VmValue.BoolValue -> byteArrayOf(2, if (value) 1 else 0)
        is VmValue.IntValue -> byteArrayOf(3) + value.toLittleEndianBytes()
        is VmValue.LongValue -> byteArrayOf(4) + value.toLittleEndianBytes()
        is VmValue.StringValue -> byteArrayOf(5) + value.encodeToByteArray().withLengthPrefix()
        is VmValue.RecordValue -> unsupportedNativeValue("RecordValue", moduleName, functionName)
        is VmValue.ObjectRef -> unsupportedNativeValue("ObjectRef", moduleName, functionName)
    }

private fun Int.toLittleEndianBytes(): ByteArray =
	byteArrayOf(
		this.toByte(),
		(this ushr 8).toByte(),
		(this ushr 16).toByte(),
		(this ushr 24).toByte(),
	)

private fun Long.toLittleEndianBytes(): ByteArray =
	ByteArray(8) { index -> (this ushr (index * 8)).toByte() }

private fun ByteArray.withLengthPrefix(): ByteArray =
	size.toLittleEndianBytes() + this

private fun unsupportedNativeValue(kind: String, moduleName: String, functionName: String): Nothing =
	throw UnsupportedOperationException("Native VM cannot resume with $kind returned by $moduleName::$functionName")
```

In `NativeVmBindings.kt`, add:

```kotlin
fun create(libraryPath: String, bytecode: ByteArray, instructionBudget: Int): Long
fun runUntilSignal(handle: Long): ByteArray
fun resumeWith(handle: Long, value: ByteArray)
fun free(handle: Long)
```

Each method calls `ensureLoaded(libraryPath)` where needed and validates non-zero handles in Kotlin before native calls.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :modules:compiler:test --tests '*NativeVmSignalTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignalTest.kt
git commit -m "feat: encode native VM resume values on JVM"
```

---

### Task 6: Kotlin native runner resume loop

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt`

- [ ] **Step 1: Write failing JNI host-call smoke test**

Add to `NativeVmRunnerJniTest.kt`:

```kotlin
@Test
fun nativeRunnerResumesAfterDisplayPrimaryHostCallWhenLibraryIsConfigured() {
    val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return

    runBlocking {
        NativeVmRunner.fromLibraryPath(libraryPath).run(displayPrimaryPlusOneModule(), RecordingRuntime())
    }
}
```

Add `displayPrimaryPlusOneModule()` with instructions:

```kotlin
Instruction.CallBuiltin("display", "primary", 0),
Instruction.PushInt(1),
Instruction.Binary(BinaryOperator.ADD),
Instruction.Return,
```

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew buildRustVmNativeLibrary
./gradlew :modules:compiler:test --tests '*NativeVmRunnerJniTest' -Dckl.vm.native.library=native/ckl-vm/target/debug/libckl_vm.so
```

Expected: FAIL because `NativeVmRunner` still uses one-shot `runUntilSignal` and rejects `HostCall`.

- [ ] **Step 3: Implement runner loop**

Replace `NativeVmRunner.run` with handle lifecycle logic:

```kotlin
override suspend fun run(module: BytecodeModule, runtime: DeviceRuntime) {
    val bytecode = BytecodeAbi.encode(module)
    val bridge = RuntimeHostBridge(runtime)
    val handle = NativeVmBindings.create(libraryPath, bytecode, runtime.profile.resources.cpu.instructionsPerSlice)
    try {
        while (true) {
            when (val signal = NativeVmSignal.decode(NativeVmBindings.runUntilSignal(handle))) {
                is NativeVmSignal.Halt -> return
                is NativeVmSignal.Error -> error("Native VM failed for device ${runtime.system.deviceId}: ${signal.message}")
                NativeVmSignal.Pause -> runtime.yield()
                NativeVmSignal.Yield -> {
                    runtime.yield()
                    NativeVmBindings.resumeWith(handle, VmValue.UnitValue.toNativeBytes("", "yield"))
                }
                is NativeVmSignal.Sleep -> {
                    runtime.sleep(signal.ticks)
                    NativeVmBindings.resumeWith(handle, VmValue.UnitValue.toNativeBytes("", "sleep"))
                }
                is NativeVmSignal.HostCall -> {
                    val arguments = signal.arguments.map { it.toVmValue(signal.moduleName, signal.functionName) }
                    val result = invokeHostCall(runtime, bridge, signal.moduleName, signal.functionName, arguments)
                    NativeVmBindings.resumeWith(handle, result.toNativeBytes(signal.moduleName, signal.functionName))
                }
            }
        }
    } finally {
        NativeVmBindings.free(handle)
    }
}
```

Implement `invokeHostCall` with the same metric recording pattern as `KotlinVmRunner`.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
./gradlew buildRustVmNativeLibrary
./gradlew :modules:compiler:test --tests '*NativeVmRunnerJniTest' -Dckl.vm.native.library=native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunner.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmRunnerJniTest.kt
git commit -m "feat: resume native VM host calls from Kotlin"
```

---

### Task 7: Final verification and docs

**Files:**
- Modify: `docs/PROFILING.md` if commands or limitation wording changed.

- [ ] **Step 1: Run Rust tests**

Run: `cd native/ckl-vm && cargo test`

Expected: PASS.

- [ ] **Step 2: Run Kotlin native tests**

Run:

```bash
./gradlew buildRustVmNativeLibrary
./gradlew :modules:compiler:test --tests '*NativeVm*' -Dckl.vm.native.library=native/ckl-vm/target/debug/libckl_vm.so
```

Expected: PASS.

- [ ] **Step 3: Verify run task wiring**

Run: `./gradlew runClientRust --dry-run`

Expected: output includes `buildRustVmNativeLibrary`, `prepareClientDev`, and `runClientRust`.

- [ ] **Step 4: Check diff hygiene**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only intentional files are modified before final commit.

- [ ] **Step 5: Commit docs if changed**

Run:

```bash
git add docs/PROFILING.md
git commit -m "docs: describe Rust VM JNI resume prototype"
```

Skip this commit if `docs/PROFILING.md` did not need changes.