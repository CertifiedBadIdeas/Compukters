# Rust-Owned IPC and Events Hot Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move native-image `events`, `ipc`, `runtime.poll`, and display metadata operations from generic Kotlin host calls into the Rust-owned device runtime kernel.

**Architecture:** The native `DeviceRuntimeKernel` becomes the shared owner of event queues, captured event arguments, IPC channels, and display metadata. The Rust image runner handles supported imports directly when attached to a native kernel, falling back to existing Kotlin host calls otherwise. Blocking `runtime.poll` is implemented as a new native wait signal that Kotlin resumes after native event or IPC state changes.

**Tech Stack:** Kotlin/JVM, Rust JNI library, CKVM image ABI/signal codec, Gradle test tasks, native `cargo test`.

---

## File Structure

- Modify `native/ckl-vm/src/runtime_kernel.rs`: implement native event capture, filters, IPC buffering, poll results, display metadata helpers, and wake sequence counters.
- Modify `native/ckl-vm/src/image_runner.rs`: route `events`, `ipc`, `runtime.poll`, and display metadata host imports to the attached native kernel.
- Modify `native/ckl-vm/src/signal.rs`: add a wait-poll signal encoded separately from generic host calls.
- Modify `native/ckl-vm/src/jni.rs`: enqueue decoded event arguments into the native kernel and expose native wake state if Kotlin needs it.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`: decode the new wait-poll signal.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`: handle wait-poll by parking via `runtime.poll(channel)` and resuming the image.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: keep `enqueueDeviceEvent` as the public Kotlin bridge, but pass real encoded event arguments.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: enqueue events into the native kernel with real argument payloads.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`: add native fast-path and native wait counters.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`: keep profiling workload compatible and assert visible terminal progress.
- Test `native/ckl-vm/tests/image_runner.rs`: add Rust image-runner fast-path tests.
- Test `native/ckl-vm/tests/signal_codec.rs`: add wait-poll signal encoding test.
- Test `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`: add JNI/native runner integration tests for real events, IPC, display metadata, and poll wait.
- Test `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`: add Kotlin runner wait-poll behavior test with fake bindings.

## Task 1: Native Kernel Event and IPC Semantics

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add failing Rust tests for native event filtering and arguments**

Append these tests to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn native_kernel_pulls_filtered_events_and_reads_arguments() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);
    assert!(kernel.enqueue_event(
        "mouse",
        vec![ckl_vm::value::VmValue::Int(4), ckl_vm::value::VmValue::String("left".to_string())],
    ));
    assert!(kernel.enqueue_event("key", vec![ckl_vm::value::VmValue::Bool(true)]));

    let event = kernel.try_pull_event(Some("mouse")).expect("mouse event");

    assert_eq!(event.name, "mouse");
    assert_eq!(event.arg_count, 2);
    assert_eq!(kernel.event_arg_int(event.id, 0), 4);
    assert_eq!(kernel.event_arg_string(event.id, 1), "left");
    assert_eq!(kernel.event_arg_bool(event.id, 0), false);
    assert!(kernel.try_pull_event(Some("missing")).is_none());
    assert_eq!(kernel.try_pull_event(None).expect("next event").name, "key");
}
```

- [ ] **Step 2: Add failing Rust tests for native IPC buffering**

Append this test to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn native_kernel_ipc_buffers_and_closes_channels() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 5);
    let channel = kernel.open_ipc_channel().expect("channel");

    kernel.write_ipc(channel, "hello").expect("write hello");
    kernel.write_ipc(channel, " world").expect("write world truncated by quota");

    assert_eq!(kernel.try_read_ipc(channel).expect("read"), "hello");
    assert_eq!(kernel.try_read_ipc(channel).expect("empty read"), "");

    kernel.close_ipc(channel).expect("close");
    assert_eq!(kernel.try_read_ipc(channel).expect("closed read"), "");
    assert!(kernel.write_ipc(channel, "x").is_err());
}
```

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
cd native/ckl-vm && cargo test native_kernel_
```

Expected: compile failure because `try_pull_event`, `event_arg_*`, `write_ipc`, `try_read_ipc`, and `close_ipc` do not exist yet.

- [ ] **Step 4: Implement native event capture and IPC methods**

In `native/ckl-vm/src/runtime_kernel.rs`, add public event result and IPC methods:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PulledEvent {
    pub name: String,
    pub id: i32,
    pub arg_count: i32,
}
```

Add fields to `DeviceRuntimeKernel`:

```rust
captured_events: BTreeMap<i32, Vec<VmValue>>,
next_event_id: i32,
wake_sequence: i64,
```

Initialize them in `DeviceRuntimeKernel::new`:

```rust
captured_events: BTreeMap::new(),
next_event_id: 1,
wake_sequence: 0,
```

Add methods:

```rust
pub fn try_pull_event(&mut self, filter: Option<&str>) -> Option<PulledEvent> {
    let index = self.event_queue.iter().position(|event| {
        filter.map_or(true, |expected| event.name == expected)
    })?;
    let event = self.event_queue.remove(index)?;
    let id = self.next_event_id;
    self.next_event_id = self.next_event_id.saturating_add(1).max(1);
    let arg_count = event.arguments.len() as i32;
    self.captured_events.insert(id, event.arguments);
    Some(PulledEvent { name: event.name, id, arg_count })
}

pub fn event_arg_count(&self, event_id: i32) -> i32 {
    self.captured_events.get(&event_id).map_or(0, |args| args.len() as i32)
}

pub fn event_arg_int(&self, event_id: i32, index: i32) -> i32 {
    match self.event_arg(event_id, index) {
        Some(VmValue::Int(value)) => *value,
        _ => 0,
    }
}

pub fn event_arg_bool(&self, event_id: i32, index: i32) -> bool {
    match self.event_arg(event_id, index) {
        Some(VmValue::Bool(value)) => *value,
        _ => false,
    }
}

pub fn event_arg_string(&self, event_id: i32, index: i32) -> String {
    match self.event_arg(event_id, index) {
        Some(VmValue::String(value)) => value.clone(),
        _ => String::new(),
    }
}

fn event_arg(&self, event_id: i32, index: i32) -> Option<&VmValue> {
    if index < 0 {
        return None;
    }
    self.captured_events.get(&event_id)?.get(index as usize)
}

pub fn write_ipc(&mut self, channel: i32, text: &str) -> Result<(), String> {
    self.ipc.write(channel, text)?;
    self.wake_sequence = self.wake_sequence.saturating_add(1);
    Ok(())
}

pub fn try_read_ipc(&mut self, channel: i32) -> Result<String, String> {
    self.ipc.try_read(channel)
}

pub fn close_ipc(&mut self, channel: i32) -> Result<(), String> {
    self.ipc.close(channel)
}
```

Extend `IpcRegistry` and `IpcChannel`:

```rust
fn write(&mut self, channel: i32, text: &str) -> Result<(), String> {
    let channel = self.channels.get_mut(&channel).ok_or_else(|| format!("IPC channel not found: {channel}"))?;
    if channel.closed {
        return Err(format!("IPC channel is closed: {channel}"));
    }
    let remaining = channel.max_buffered_bytes.saturating_sub(channel.buffer.len());
    channel.buffer.push_str(&text.chars().take(remaining).collect::<String>());
    Ok(())
}

fn try_read(&mut self, channel: i32) -> Result<String, String> {
    let channel = self.channels.get_mut(&channel).ok_or_else(|| format!("IPC channel not found: {channel}"))?;
    if channel.closed {
        return Ok(String::new());
    }
    Ok(std::mem::take(&mut channel.buffer))
}

fn close(&mut self, channel: i32) -> Result<(), String> {
    let channel = self.channels.get_mut(&channel).ok_or_else(|| format!("IPC channel not found: {channel}"))?;
    channel.closed = true;
    channel.buffer.clear();
    Ok(())
}
```

Add `closed: bool` to `IpcChannel` and initialize it to `false`.

- [ ] **Step 5: Run Rust tests**

Run:

```bash
cd native/ckl-vm && cargo test native_kernel_
```

Expected: both tests pass.

- [ ] **Step 6: Commit**

```bash
git add native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: add native kernel ipc and event state"
```

## Task 2: Fast-Path Events, IPC, and Display Metadata in Rust Image Runner

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add failing image-runner tests for fast-pathed imports**

Append this test to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn attached_kernel_handles_ipc_events_and_display_metadata_imports() {
    let kernel = std::sync::Arc::new(std::sync::Mutex::new(ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64)));
    {
        let mut kernel = kernel.lock().unwrap();
        kernel.displays.attach(7, 20, 10, ckl_vm::display::PixelFormat::Rgb565).unwrap();
        kernel.enqueue_event("char", vec![ckl_vm::value::VmValue::String("a".to_string())]);
    }

    let mut code = Vec::new();
    call_host(&mut code, 5000, 0);
    code.push(OP_POP);
    call_host(&mut code, 4002, 0);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    push_constant(&mut code, 0);
    call_host(&mut code, 4007, 2);
    code.push(OP_POP);
    call_host(&mut code, 1000, 0);
    call_host(&mut code, 1002, 1);
    code.push(OP_POP);
    push_constant(&mut code, 1);
    call_host(&mut code, 1003, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::Int(0), ConstantFixture::Int(7)],
            vec![
                HostImportFixture {
                    id: 5000,
                    module_name: "ipc".to_string(),
                    function_name: "open".to_string(),
                    parameter_types: vec![],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 4002,
                    module_name: "events".to_string(),
                    function_name: "tryPull".to_string(),
                    parameter_types: vec![],
                    return_type: "Event".to_string(),
                },
                HostImportFixture {
                    id: 4007,
                    module_name: "events".to_string(),
                    function_name: "argString".to_string(),
                    parameter_types: vec!["Event".to_string(), "Int".to_string()],
                    return_type: "String".to_string(),
                },
                HostImportFixture {
                    id: 1000,
                    module_name: "display".to_string(),
                    function_name: "primary".to_string(),
                    parameter_types: vec![],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 1002,
                    module_name: "display".to_string(),
                    function_name: "width".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 1003,
                    module_name: "display".to_string(),
                    function_name: "height".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Int".to_string(),
                },
            ],
            1,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(std::sync::Arc::clone(&kernel)).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 0, "program should halt instead of emitting native-kernel host calls");
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
cd native/ckl-vm && cargo test attached_kernel_handles_ipc_events_and_display_metadata_imports
```

Expected: failure or compile error because the image runner does not fast-path these imports yet.

- [ ] **Step 3: Implement fast-path routing**

In `native/ckl-vm/src/image_runner.rs`, expand `try_attached_kernel_host_import` to accept modules:

```rust
if !matches!(module_name, "display" | "filesystem" | "events" | "ipc" | "runtime") {
    return Ok(NativeHostImportResult::Fallback(arguments));
}
```

Add helpers that produce records:

```rust
fn event_record(name: String, id: i32, arg_count: i32) -> VmValue {
    VmValue::Record {
        type_name: "Event".to_string(),
        fields: vec![
            ("name".to_string(), VmValue::String(name)),
            ("id".to_string(), VmValue::Int(id)),
            ("argCount".to_string(), VmValue::Int(arg_count)),
        ],
    }
}

fn poll_record(kind: &str, text: String, event: VmValue) -> VmValue {
    VmValue::Record {
        type_name: "Poll".to_string(),
        fields: vec![
            ("kind".to_string(), VmValue::String(kind.to_string())),
            ("text".to_string(), VmValue::String(text)),
            ("event".to_string(), event),
        ],
    }
}
```

Handle event functions:

```rust
"events" => match function_name {
    "tryPull" => {
        let filter = arguments.first().map(|_| string_argument(&arguments, 0, "events.tryPull filter")).transpose()?;
        let event = kernel.try_pull_event(filter);
        let value = event
            .map(|event| event_record(event.name, event.id, event.arg_count))
            .unwrap_or_else(|| event_record(String::new(), 0, 0));
        Ok(NativeHostImportResult::Handled(value))
    }
    "argCount" => {
        let event_id = event_id_argument(&arguments, 0, "events.argCount event")?;
        Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.event_arg_count(event_id))))
    }
    "argInt" => {
        let event_id = event_id_argument(&arguments, 0, "events.argInt event")?;
        let index = int_argument(&arguments, 1, "events.argInt index")?;
        Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.event_arg_int(event_id, index))))
    }
    "argBool" => {
        let event_id = event_id_argument(&arguments, 0, "events.argBool event")?;
        let index = int_argument(&arguments, 1, "events.argBool index")?;
        Ok(NativeHostImportResult::Handled(VmValue::Bool(kernel.event_arg_bool(event_id, index))))
    }
    "argString" => {
        let event_id = event_id_argument(&arguments, 0, "events.argString event")?;
        let index = int_argument(&arguments, 1, "events.argString index")?;
        Ok(NativeHostImportResult::Handled(VmValue::String(kernel.event_arg_string(event_id, index))))
    }
    _ => Ok(NativeHostImportResult::Fallback(arguments)),
}
```

Handle IPC functions:

```rust
"ipc" => match function_name {
    "open" => Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.open_ipc_channel()?))),
    "write" => {
        let channel = int_argument(&arguments, 0, "ipc.write channel")?;
        let text = string_argument(&arguments, 1, "ipc.write text")?;
        kernel.write_ipc(channel, text)?;
        Ok(NativeHostImportResult::Handled(VmValue::Unit))
    }
    "tryRead" => {
        let channel = int_argument(&arguments, 0, "ipc.tryRead channel")?;
        Ok(NativeHostImportResult::Handled(VmValue::String(kernel.try_read_ipc(channel)?)))
    }
    "close" => {
        let channel = int_argument(&arguments, 0, "ipc.close channel")?;
        kernel.close_ipc(channel)?;
        Ok(NativeHostImportResult::Handled(VmValue::Unit))
    }
    _ => Ok(NativeHostImportResult::Fallback(arguments)),
}
```

Add display metadata methods to `DeviceDisplayRegistry` and route:

```rust
"display" => match function_name {
    "primary" => Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.displays.first_display_id().unwrap_or(0)))),
    "isAttached" => {
        let display_id = int_argument(&arguments, 0, "display.isAttached displayId")?;
        Ok(NativeHostImportResult::Handled(VmValue::Bool(kernel.displays.is_attached(display_id))))
    }
    "width" => {
        let display_id = int_argument(&arguments, 0, "display.width displayId")?;
        Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.displays.width(display_id).unwrap_or(0))))
    }
    "height" => {
        let display_id = int_argument(&arguments, 0, "display.height displayId")?;
        Ok(NativeHostImportResult::Handled(VmValue::Int(kernel.displays.height(display_id).unwrap_or(0))))
    }
    _ => { existing display raster handlers }
}
```

- [ ] **Step 4: Run Rust image-runner tests**

Run:

```bash
cd native/ckl-vm && cargo test image_runner
```

Expected: all image-runner tests pass.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/display.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: fast-path native ipc events and display metadata"
```

## Task 3: Real Native Event Payloads Through JNI

**Files:**
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing JNI integration test**

Add this test to `NativeImageVmBindingsJniTest`:

```kotlin
@Test
fun nativeEventsCarryArgumentsWhenLibraryIsConfigured() {
    val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val image =
        assertNotNull(
            LanguageFrontend()
                .compileImage(
                    "main.ck",
                    """
                    pub fun main(): String {
                        val event: Event = events::tryPull();
                        return event.name + ":" + events::argString(event, 0);
                    }
                    """.trimIndent(),
                ).image,
        )
    val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)
    val imageHandle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), instructionBudget = 4096)

    try {
        NativeVmBindings.enqueueDeviceEvent(
            kernelHandle,
            "char",
            VmValue.RecordValue(
                typeName = "EventPayload",
                fields = linkedMapOf("arg0" to VmValue.StringValue("x")),
            ).toNativeBytes("events", "enqueue"),
        )
        NativeVmBindings.attachImageToKernel(imageHandle, kernelHandle)

        val halt = assertIs<NativeVmSignal.Halt>(NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(imageHandle)))
        assertEquals(NativeVmValue.StringValue("char:x"), halt.value)
    } finally {
        NativeVmBindings.freeImage(imageHandle)
        NativeVmBindings.freeDeviceKernel(kernelHandle)
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeEventsCarryArgumentsWhenLibraryIsConfigured' --rerun-tasks
```

Expected: FAIL because `enqueueDeviceEventNative` currently ignores payloads.

- [ ] **Step 3: Implement payload decoding in Rust JNI**

In `native/ckl-vm/src/jni.rs`, import `decode_value` and add:

```rust
fn event_arguments_from_payload(payload: &[u8]) -> Result<Vec<VmValue>, String> {
    if payload.is_empty() {
        return Ok(Vec::new());
    }
    match decode_value(payload)? {
        VmValue::Record { fields, .. } => Ok(fields.into_iter().map(|(_, value)| value).collect()),
        other => Ok(vec![other]),
    }
}
```

Update `enqueueDeviceEventNative`:

```rust
let arguments = match event_arguments_from_payload(&payload) {
    Ok(arguments) => arguments,
    Err(error) => {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error);
        return 0;
    }
};
if kernel.enqueue_event(&event_name, arguments) { 1 } else { 0 }
```

- [ ] **Step 4: Encode real event payloads from Kotlin enqueue path**

In `BackgroundDeviceVm.enqueueEvent`, keep enqueuing to `eventManager`, and also enqueue to native:

```kotlin
override fun enqueueEvent(event: VmEvent): Boolean {
    val accepted = eventManager.enqueueEvent(event)
    if (accepted && !nativeDeviceKernelFreed) {
        nativeDeviceKernelHandle?.let { handle ->
            NativeVmBindings.enqueueDeviceEvent(handle, event.name, nativeEventPayload(event.arguments))
        }
    }
    return accepted
}
```

Add helper:

```kotlin
private fun nativeEventPayload(arguments: List<Any?>): ByteArray =
    VmValue.RecordValue(
        typeName = "EventPayload",
        fields =
            arguments.mapIndexedTo(LinkedHashMap()) { index, value ->
                "arg$index" to value.toNativeEventValue()
            },
    ).toNativeBytes("events", "enqueue")

private fun Any?.toNativeEventValue(): VmValue =
    when (this) {
        null -> VmValue.NullValue
        is Int -> VmValue.IntValue(this)
        is Boolean -> VmValue.BoolValue(this)
        is String -> VmValue.StringValue(this)
        is ByteArray -> VmValue.StringValue(decodeToString())
        else -> VmValue.StringValue(toString())
    }
```

- [ ] **Step 5: Run JNI event test**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeEventsCarryArgumentsWhenLibraryIsConfigured' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: pass real events into native kernel"
```

## Task 4: Native Poll Wait Signal

**Files:**
- Modify: `native/ckl-vm/src/signal.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Test: `native/ckl-vm/tests/signal_codec.rs`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`

- [ ] **Step 1: Add failing Rust signal codec test**

Add to `native/ckl-vm/tests/signal_codec.rs`:

```rust
#[test]
fn encodes_wait_poll_signal() {
    let bytes = encode_signal(&VmSignal::WaitPoll { channel: 7 });

    assert_eq!(bytes, vec![6, 7, 0, 0, 0]);
}
```

- [ ] **Step 2: Add failing Kotlin signal decoder test**

Add to `NativeImageVmRunnerTest` or a new `NativeVmSignalTest`:

```kotlin
@Test
fun decodesNativeWaitPollSignal() {
    val signal = NativeVmSignal.decode(byteArrayOf(6, 7, 0, 0, 0))

    assertEquals(NativeVmSignal.WaitPoll(channel = 7), signal)
}
```

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
cd native/ckl-vm && cargo test encodes_wait_poll_signal
./gradlew :compiler:test --tests '*NativeImageVmRunnerTest.decodesNativeWaitPollSignal' --rerun-tasks
```

Expected: both fail because tag `6` is not defined.

- [ ] **Step 4: Implement signal encoding and decoding**

In `native/ckl-vm/src/signal.rs`, add:

```rust
WaitPoll { channel: i32 },
```

Add tag:

```rust
const SIGNAL_WAIT_POLL: u8 = 6;
```

Encode:

```rust
VmSignal::WaitPoll { channel } => {
    writer.u8(SIGNAL_WAIT_POLL);
    writer.i32(*channel);
}
```

In `NativeVmSignal.kt`, add:

```kotlin
data class WaitPoll(
    val channel: Int,
) : NativeVmSignal
```

Decode tag `6`:

```kotlin
6 -> WaitPoll(reader.i32())
```

- [ ] **Step 5: Make `runtime.poll` emit wait-poll only when empty**

In `runtime_kernel.rs`, add:

```rust
pub fn poll_ipc_or_event(&mut self, channel: i32) -> Result<Option<VmValue>, String> {
    let text = self.try_read_ipc(channel)?;
    if !text.is_empty() {
        return Ok(Some(poll_record_value("ipc", text, empty_event_value())));
    }
    if let Some(event) = self.try_pull_event(None) {
        return Ok(Some(poll_record_value(
            "event",
            String::new(),
            event_record_value(event.name, event.id, event.arg_count),
        )));
    }
    Ok(None)
}
```

Place `event_record_value`, `empty_event_value`, and `poll_record_value` either in `runtime_kernel.rs` or keep them in `image_runner.rs`; do not duplicate field names across files without a helper.

In `image_runner.rs`, route `runtime.poll`:

```rust
"runtime" => match function_name {
    "poll" => {
        let channel = int_argument(&arguments, 0, "runtime.poll channel")?;
        match kernel.poll_ipc_or_event(channel)? {
            Some(value) => Ok(NativeHostImportResult::Handled(value)),
            None => Ok(NativeHostImportResult::Signal(VmSignal::WaitPoll { channel })),
        }
    }
    _ => Ok(NativeHostImportResult::Fallback(arguments)),
}
```

If `NativeHostImportResult` has only `Handled` and `Fallback`, add a `Signal(VmSignal)` variant and make the caller return that signal.

- [ ] **Step 6: Handle WaitPoll in Kotlin runner**

In `NativeImageVmRunner.run`, add:

```kotlin
is NativeVmSignal.WaitPoll -> {
    runtime.poll(signal.channel)
    bindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("runtime", "poll"))
}
```

The image runner will re-check native kernel state after resume and produce the actual `Poll` record.

- [ ] **Step 7: Run signal and runner tests**

Run:

```bash
cd native/ckl-vm && cargo test encodes_wait_poll_signal
./gradlew :compiler:test --tests '*NativeImageVmRunnerTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add native/ckl-vm/src/signal.rs native/ckl-vm/src/image_runner.rs native/ckl-vm/tests/signal_codec.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmSignal.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt
git commit -m "feat: add native poll wait signal"
```

## Task 5: Profiling Visibility

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`

- [ ] **Step 1: Add failing report expectation**

In `RuntimeVmProfilingReportTest`, assert that the Markdown contains native wait/fast-path labels:

```kotlin
assertContains(markdown, "Native wait signals")
assertContains(markdown, "Native fast-path calls")
```

- [ ] **Step 2: Run profile test and verify it fails**

Run:

```bash
./gradlew profileRuntimeVmImage
```

Expected: FAIL because report text does not include native counters yet.

- [ ] **Step 3: Add profiling counters**

Add fields to the profiling snapshot model:

```kotlin
val nativeFastPathCalls: Long = 0,
val nativeWaitSignals: Long = 0,
val nativeWaitNanos: Long = 0,
```

Add methods to `DeviceRuntimeMetrics`:

```kotlin
fun recordNativeFastPathCall(moduleName: String, functionName: String, nanos: Long)
fun recordNativeWaitSignal(kind: String, nanos: Long)
```

In `NativeImageVmRunner`, record:

```kotlin
is NativeVmSignal.WaitPoll -> {
    val started = System.nanoTime()
    runtime.poll(signal.channel)
    runtime.metrics.recordNativeWaitSignal("runtime.poll", System.nanoTime() - started)
    bindings.resumeImageWith(handle, VmValue.UnitValue.toNativeBytes("runtime", "poll"))
}
```

Native fast-path call counts may be recorded in aggregate from Rust later; for this task, report wait signals explicitly and leave fast-path count as zero unless JNI exposes it. The report must still include the row so future comparisons have stable columns.

- [ ] **Step 4: Run profile test**

Run:

```bash
./gradlew profileRuntimeVmImage
```

Expected: PASS and Markdown contains `Native wait signals`.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt
git commit -m "feat: report native runtime waits"
```

## Task 6: Terminal Profiling Verification and Host-Call Reduction

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`

- [ ] **Step 1: Run full native verification**

Run:

```bash
cd native/ckl-vm && cargo test
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerTest' --tests '*NativeImageVmRunnerJniTest' --rerun-tasks
./gradlew :core:compileKotlin
./gradlew profileRuntimeVmImage
```

Expected: all commands pass.

- [ ] **Step 2: Inspect generated profile**

Run:

```bash
LATEST_RUN="$(find modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runs -maxdepth 2 -name runtime-vm-image.md -printf '%T@ %p\n' | sort -n | tail -1 | cut -d' ' -f2-)"
printf '%s\n' "$LATEST_RUN"
rg -n 'events\\.|ipc\\.|runtime\\.poll|display\\.width|display\\.height|display\\.primary|Native wait signals' "$LATEST_RUN"
```

Expected:

- generic `events.*`, `ipc.*`, `runtime.poll`, and display metadata host calls are lower than the pre-migration baseline;
- `Native wait signals` is present;
- terminal workload still reports client frames applied for input and Enter phases.

- [ ] **Step 3: Fix only verified regressions**

If terminal input stops producing frames, inspect:

```bash
rg -n 'enqueueDeviceEvent|WaitPoll|runtime.poll|try_pull_event|write_ipc' modules compiler native/ckl-vm/src
```

Fix the first broken boundary shown by logs or tests:

- missing event payload: fix `BackgroundDeviceVm.nativeEventPayload`;
- missing wake: fix `NativeImageVmRunner` WaitPoll handling;
- incorrect record fields: fix `event_record` or `poll_record`;
- channel quota issue: fix `IpcRegistry::write`.

- [ ] **Step 4: Final whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm modules/compiler modules/core modules/v1_21_1
git commit -m "perf: route terminal ipc and events through native kernel"
```

## Self-Review

- Spec coverage: event queue, IPC channels, display metadata, native poll wait, fallback, profiling, and process exclusion are covered by Tasks 1-6.
- Scope check: `process.run/spawn/wait` remains Kotlin-owned; no terminal-specific host primitive is introduced.
- Placeholder scan: the plan contains no TODO/TBD markers. The only conditional step is Task 6 regression triage, with explicit commands and allowed fixes.
- Type consistency: plan uses existing `VmValue`, `NativeVmSignal`, `DeviceRuntimeKernel`, `NativeVmBindings`, and `BackgroundDeviceVm` names.
- Execution consistency: Rust tests are introduced before implementation. Kotlin tests target existing Gradle tasks. Native tests use the configured `ckl.vm.native.library` path already used in this repo.
