# Native Display Frame Pump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver native display frames through a Rust-woken display pump as opaque byte arrays in Minecraft packets.

**Architecture:** Rust owns display frame construction, pending native frame batches, and a display-specific wake sequence. Kotlin server waits for display frame wakes, drains encoded bytes without reconstructing `DisplayFrameDelta`, and wraps those bytes in a Minecraft packet. Kotlin client decodes the byte batch and applies it to the existing `ClientDisplayBuffer`.

**Tech Stack:** Rust native VM library, JNI, Kotlin coroutines, Minecraft/NeoForge custom payloads, existing `NativeDisplayFrameCodec`, existing `ClientDisplayBuffer`.

---

## File Structure

- Modify `native/ckl-vm/src/display.rs`
  - Return whether `attach` and `present` queued a frame.
- Modify `native/ckl-vm/src/runtime_kernel.rs`
  - Add `display_wake_sequence` to `DeviceRuntimeKernel`.
  - Add `display_wake: Condvar` to `DeviceRuntimeKernelHandle`.
  - Add wait/read helpers for display frame wake sequence.
  - Add wrapper methods for display attach/detach/present/drain so JNI no longer mutates `kernel.displays` directly.
- Modify `native/ckl-vm/src/jni.rs`
  - Add JNI exports for display wake sequence and wait.
  - Route native display attach/detach/present/drain through the new kernel handle helpers.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Add Kotlin methods and native declarations for display wake sequence and wait.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - Add tests for display wait JNI and binding exposure.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
  - Expose `drainFrameBytes()`, `displayWakeSequence()`, and `waitForDisplayWake(...)`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Expose native display byte-drain/wait helpers for `RuntimeDeviceImpl`.
  - Keep existing `drainDisplayFrames()` fallback behavior.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt`
  - Add a default `sendNativeDisplayFrameBytes(...)` method that decodes bytes and sends legacy frames for tests/fallback ports.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
  - Start/stop a native display pump coroutine with the VM lifecycle.
  - Keep tick-based `flushDisplaySessions(...)` for fallback frames.
  - Send native byte batches through the new bridge method.
- Create `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/NativeFrameBatchClientMessage.kt`
  - New clientbound packet carrying `containerId` and encoded native frame bytes.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
  - Add `handleNativeDisplayFrameBytes(containerId: Int, payload: ByteArray)`.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
  - Decode native frame bytes on the client and apply frames to the currently open `ComputerMenu`.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
  - Register `native_frame_batch` clientbound message id.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`
  - Send native frame byte batches with `NativeFrameBatchClientMessage`.
- Add/modify tests under `modules/core/src/test`, `modules/compiler/src/test`, and `modules/v1_21_1/v1_21_1-common/src/test` as listed below.

## Task 1: Rust Display Wake Sequence

**Files:**
- Modify: `native/ckl-vm/src/display.rs`
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/jni.rs`

- [ ] **Step 1: Write Rust unit tests for display wake behavior**

Add tests at the bottom of `native/ckl-vm/src/runtime_kernel.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::display::PixelFormat;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn display_wake_sequence_advances_when_attach_queues_full_refresh() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        let before = handle.display_wake_sequence().unwrap();

        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();

        assert!(handle.display_wake_sequence().unwrap() > before);
        assert!(!handle.drain_display_frames().unwrap().is_empty());
    }

    #[test]
    fn display_wake_sequence_advances_when_present_emits_frame() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let before = handle.display_wake_sequence().unwrap();

        handle.with_kernel_mut(|kernel| {
            kernel.displays.fill_rect(7, 0, 0, 2, 2, 0x07e0);
        }).unwrap();
        handle.present_display(7).unwrap();

        assert!(handle.display_wake_sequence().unwrap() > before);
        assert!(!handle.drain_display_frames().unwrap().is_empty());
    }

    #[test]
    fn display_wake_sequence_does_not_advance_when_present_has_no_dirty_frame() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let before = handle.display_wake_sequence().unwrap();

        handle.present_display(7).unwrap();

        assert_eq!(before, handle.display_wake_sequence().unwrap());
    }

    #[test]
    fn wait_for_display_wake_returns_after_present() {
        let handle = std::sync::Arc::new(DeviceRuntimeKernelHandle::new(16, 1024));
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let observed = handle.display_wake_sequence().unwrap();
        let waiter = handle.clone();

        let join = thread::spawn(move || {
            waiter
                .wait_for_display_wake(observed, Duration::from_millis(500))
                .unwrap()
        });

        thread::sleep(Duration::from_millis(25));
        handle.with_kernel_mut(|kernel| {
            kernel.displays.fill_rect(7, 0, 0, 2, 2, 0xffff);
        }).unwrap();
        handle.present_display(7).unwrap();

        assert!(join.join().unwrap() > observed);
    }

    #[test]
    fn wait_for_display_wake_times_out_without_change() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        let observed = handle.display_wake_sequence().unwrap();

        let after = handle
            .wait_for_display_wake(observed, Duration::from_millis(5))
            .unwrap();

        assert_eq!(observed, after);
    }
}
```

- [ ] **Step 2: Run Rust tests and confirm they fail**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml display_wake -- --nocapture
```

Expected: FAIL because `display_wake_sequence`, `attach_display`, `present_display`, `drain_display_frames`, and `wait_for_display_wake` do not exist.

- [ ] **Step 3: Update `DeviceDisplayRegistry` to report emitted frames**

Change the display registry methods in `native/ckl-vm/src/display.rs`:

```rust
    pub fn attach(
        &mut self,
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<bool, String> {
        let mut display = DisplayEngine::new(display_id, width, height, pixel_format)?;
        let emitted = if let Some(frame) = display.full_refresh() {
            self.pending_frames.push(frame);
            true
        } else {
            false
        };
        self.displays.insert(display_id, display);
        Ok(emitted)
    }

    pub fn detach(&mut self, display_id: i32) -> bool {
        self.displays.remove(&display_id).is_some()
    }

    pub fn present(&mut self, display_id: i32) -> bool {
        if let Some(display) = self.displays.get_mut(&display_id) {
            if let Some(frame) = display.present() {
                self.pending_frames.push(frame);
                return true;
            }
        }
        false
    }
```

- [ ] **Step 4: Add display wake state to the native kernel handle**

In `native/ckl-vm/src/runtime_kernel.rs`, import the pixel format and frame type:

```rust
use crate::display::{DeviceDisplayRegistry, DisplayFrameDelta, PixelFormat};
```

Add a new field to `DeviceRuntimeKernel`:

```rust
    display_wake_sequence: i64,
```

Initialize it in `DeviceRuntimeKernel::new`:

```rust
            display_wake_sequence: 0,
```

Add methods to `DeviceRuntimeKernel`:

```rust
    pub fn display_wake_sequence(&self) -> i64 {
        self.display_wake_sequence
    }

    fn advance_display_wake_sequence(&mut self) {
        self.display_wake_sequence = self.display_wake_sequence.saturating_add(1);
    }
```

Add a second condition variable to `DeviceRuntimeKernelHandle`:

```rust
    display_wake: Condvar,
```

Initialize it:

```rust
            display_wake: Condvar::new(),
```

Add handle methods:

```rust
    pub fn display_wake_sequence(&self) -> Result<i64, String> {
        Ok(self.lock()?.display_wake_sequence())
    }

    pub fn attach_display(
        &self,
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<(), String> {
        let mut kernel = self.lock()?;
        let emitted = kernel.displays.attach(display_id, width, height, pixel_format)?;
        if emitted {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn detach_display(&self, display_id: i32) -> Result<(), String> {
        let mut kernel = self.lock()?;
        if kernel.displays.detach(display_id) {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn present_display(&self, display_id: i32) -> Result<(), String> {
        let mut kernel = self.lock()?;
        if kernel.displays.present(display_id) {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn drain_display_frames(&self) -> Result<Vec<DisplayFrameDelta>, String> {
        let mut kernel = self.lock()?;
        Ok(kernel.displays.drain_frames())
    }

    pub fn wait_for_display_wake(
        &self,
        observed_sequence: i64,
        timeout: Duration,
    ) -> Result<i64, String> {
        let kernel = self.lock()?;
        if kernel.display_wake_sequence() > observed_sequence {
            return Ok(kernel.display_wake_sequence());
        }
        let (kernel, _) = self
            .display_wake
            .wait_timeout_while(kernel, timeout, |kernel| {
                kernel.display_wake_sequence() <= observed_sequence
            })
            .map_err(|_| "native display frame wait lock is poisoned".to_string())?;
        Ok(kernel.display_wake_sequence())
    }
```

- [ ] **Step 5: Route JNI display calls through the handle methods**

In `native/ckl-vm/src/jni.rs`, change display attach/detach/present/drain functions to use the handle helpers:

```rust
    if let Err(error) = kernel_handle.attach_display(display_id, width, height, PixelFormat::Rgb565) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
```

```rust
    if let Err(error) = kernel_handle.detach_display(display_id) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
```

```rust
    if let Err(error) = kernel_handle.present_display(display_id) {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
    }
```

```rust
    match kernel_handle.drain_display_frames() {
        Ok(frames) => {
            let bytes = encode_display_frames(&frames);
            byte_array_or_throw(&mut env, &bytes)
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            null_mut()
        }
    }
```

Add JNI exports:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_displayWakeSequenceNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return 0,
    };
    match kernel.display_wake_sequence() {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_waitForDisplayWakeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    observed_wake_sequence: jlong,
    timeout_millis: jlong,
) -> jlong {
    let kernel = match shared_kernel_handle(&mut env, handle) {
        Some(kernel) => kernel,
        None => return observed_wake_sequence,
    };
    let timeout = Duration::from_millis(timeout_millis.max(0) as u64);
    match kernel.wait_for_display_wake(observed_wake_sequence, timeout) {
        Ok(sequence) => sequence as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            observed_wake_sequence
        }
    }
}
```

- [ ] **Step 6: Run Rust tests and confirm they pass**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml display_wake -- --nocapture
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add native/ckl-vm/src/display.rs native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/src/jni.rs
git commit -m "feat: add native display frame wake sequence"
```

## Task 2: Kotlin JNI Bindings For Display Wake

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing binding tests**

Add to `NativeImageVmBindingsJniTest`:

```kotlin
@Test
fun nativeDisplayBindingsExposeWakeWait() {
    val memberNames =
        NativeVmBindings::class.java.declaredMethods
            .map { it.name }
            .toSet()

    assertTrue("displayWakeSequence" in memberNames)
    assertTrue("waitForDisplayWake" in memberNames)
}

@Test
fun nativeDisplayWaitReturnsAfterPresentWhenLibraryIsConfigured() {
    System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)

    try {
        NativeVmBindings.attachNativeDisplay(kernelHandle, displayId = 3, width = 18, height = 18)
        NativeVmBindings.drainNativeDisplayFrames(kernelHandle)
        val observed = NativeVmBindings.displayWakeSequence(kernelHandle)

        val waiter =
            java.util.concurrent.CompletableFuture.supplyAsync {
                NativeVmBindings.waitForDisplayWake(kernelHandle, observed, timeoutMillis = 500)
            }

        Thread.sleep(25)
        NativeVmBindings.nativeDisplayFillRect(kernelHandle, 3, 0, 0, 2, 2, 0x07E0)
        NativeVmBindings.nativeDisplayPresent(kernelHandle, 3)

        assertTrue(waiter.get(1, java.util.concurrent.TimeUnit.SECONDS) > observed)
    } finally {
        NativeVmBindings.freeDeviceKernel(kernelHandle)
    }
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks
```

Expected: FAIL because Kotlin binding methods do not exist.

- [ ] **Step 3: Add Kotlin binding methods and native declarations**

In `NativeVmBindings.kt`, add public methods near the existing device wake methods:

```kotlin
fun displayWakeSequence(handle: Long): Long {
    require(handle != 0L) { "Native device runtime kernel handle is zero" }
    return displayWakeSequenceNative(handle)
}

fun waitForDisplayWake(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long {
    require(handle != 0L) { "Native device runtime kernel handle is zero" }
    return waitForDisplayWakeNative(handle, observedWakeSequence, timeoutMillis.coerceAtLeast(0))
}
```

Add private native declarations near the existing wake declarations:

```kotlin
private external fun displayWakeSequenceNative(handle: Long): Long

private external fun waitForDisplayWakeNative(
    handle: Long,
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long
```

- [ ] **Step 4: Build native library and run binding tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
```

Expected: PASS.

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests "*NativeImageVmBindingsJniTest" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: expose native display wake bindings"
```

## Task 3: Native Frame Byte Access In Core VM

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Add native frame byte helper methods**

In `NativeDisplayRegistry.kt`, add:

```kotlin
fun drainFrameBytes(): ByteArray = NativeVmBindings.drainNativeDisplayFrames(kernelHandle)

fun displayWakeSequence(): Long = NativeVmBindings.displayWakeSequence(kernelHandle)

fun waitForDisplayWake(
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long = NativeVmBindings.waitForDisplayWake(kernelHandle, observedWakeSequence, timeoutMillis)
```

In `BackgroundDeviceVm.kt`, add public methods near `drainDisplayFrames()`:

```kotlin
fun supportsNativeDisplayFramePump(): Boolean = nativeDisplayRegistry != null && !nativeDeviceKernelFreed

fun nativeDisplayWakeSequence(): Long? =
    if (!nativeDeviceKernelFreed) nativeDisplayRegistry?.displayWakeSequence() else null

fun waitForNativeDisplayWake(
    observedWakeSequence: Long,
    timeoutMillis: Long,
): Long? =
    if (!nativeDeviceKernelFreed) {
        nativeDisplayRegistry?.waitForDisplayWake(observedWakeSequence, timeoutMillis)
    } else {
        null
    }

fun drainNativeDisplayFrameBytes(): ByteArray? =
    if (!nativeDeviceKernelFreed) nativeDisplayRegistry?.drainFrameBytes() else null
```

- [ ] **Step 2: Run core compilation**

Run:

```bash
./gradlew :core:compileKotlin
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt
git commit -m "feat: expose native display frame bytes to runtime"
```

## Task 4: Native Frame Batch Client Packet

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/NativeFrameBatchClientMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Add: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/NativeFrameBatchClientMessageTest.kt`

- [ ] **Step 1: Write packet round-trip test**

Create `NativeFrameBatchClientMessageTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.network.client

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeFrameBatchClientMessageTest {
    @Test
    fun roundTripsContainerIdAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val original = NativeFrameBatchClientMessage(containerId = 42, payload = payload)
        val buffer = FriendlyByteBuf(Unpooled.buffer())

        original.write(buffer)
        val decoded = NativeFrameBatchClientMessage(buffer)

        assertEquals(42, decoded.containerId)
        assertContentEquals(payload, decoded.payload)
    }
}
```

- [ ] **Step 2: Run test and confirm it fails**

Run:

```bash
./gradlew :v1_21_1-common:test --tests "*NativeFrameBatchClientMessageTest" --rerun-tasks
```

Expected: FAIL because `NativeFrameBatchClientMessage` does not exist.

- [ ] **Step 3: Create native frame batch packet**

Create `NativeFrameBatchClientMessage.kt`:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages

class NativeFrameBatchClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val payload: ByteArray

    constructor(containerId: Int, payload: ByteArray) {
        this.containerId = containerId
        this.payload = payload
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        payload = buf.readByteArray()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeByteArray(payload)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleNativeDisplayFrameBytes(containerId, payload)
    }

    override fun type(): MessageType<NativeFrameBatchClientMessage> = NetworkMessages.NATIVE_FRAME_BATCH
}
```

- [ ] **Step 4: Add client context method**

In `ClientNetworkContext.kt`, add:

```kotlin
fun handleNativeDisplayFrameBytes(
    containerId: Int,
    payload: ByteArray,
)
```

In `ClientNetworkContextImpl.kt`, import `NativeDisplayFrameCodec`:

```kotlin
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
```

Add implementation:

```kotlin
override fun handleNativeDisplayFrameBytes(
    containerId: Int,
    payload: ByteArray,
) = withCheckedContainerMenu(containerId) {
    for (frame in NativeDisplayFrameCodec.decodeFrames(payload)) {
        handleDisplayFrame(frame)
    }
}
```

- [ ] **Step 5: Register the message**

In `NetworkMessages.kt`, import `NativeFrameBatchClientMessage` and add after `FRAME_DELTA`:

```kotlin
val NATIVE_FRAME_BATCH: MessageType<NativeFrameBatchClientMessage> =
    registerClientbound(
        26,
        "native_frame_batch",
        { buf -> NativeFrameBatchClientMessage(buf) },
    )
```

The id `26` is the next clientbound id after the existing `frame_delta` id `25`.

- [ ] **Step 6: Run packet tests and common compilation**

Run:

```bash
./gradlew :v1_21_1-common:test --tests "*NativeFrameBatchClientMessageTest" --rerun-tasks
```

Expected: PASS.

Run:

```bash
./gradlew :v1_21_1-common:compileKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/NativeFrameBatchClientMessage.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/NativeFrameBatchClientMessageTest.kt
git commit -m "feat: add native frame batch client packet"
```

## Task 5: Display Network Bridge Byte Path

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`

- [ ] **Step 1: Add bridge default byte method**

In `DisplayNetworkBridge.kt`, import the native codec:

```kotlin
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
```

Add default method to `DisplayNetworkBridge`:

```kotlin
fun sendNativeDisplayFrameBytes(
    playerUuid: UUID,
    containerId: Int,
    payload: ByteArray,
) {
    for (frame in NativeDisplayFrameCodec.decodeFrames(payload)) {
        sendDisplayFrame(playerUuid, containerId, frame)
    }
}
```

This default keeps tests and non-platform bridge implementations compatible.

- [ ] **Step 2: Override bridge method in block entity host**

In `BlockEntityRuntimeDeviceHost.kt`, import the new packet:

```kotlin
import ru.lazyhat.compukterkraft.common.computer.network.client.NativeFrameBatchClientMessage
```

Add override inside the anonymous `DisplayNetworkBridge`:

```kotlin
override fun sendNativeDisplayFrameBytes(
    playerUuid: UUID,
    containerId: Int,
    payload: ByteArray,
) {
    val player = level.server.playerList.getPlayer(playerUuid) ?: return
    ServerNetworking.sendToPlayer(
        NativeFrameBatchClientMessage(containerId, payload),
        player,
    )
}
```

- [ ] **Step 3: Run compilation**

Run:

```bash
./gradlew :core:compileKotlin :v1_21_1-common:compileKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt
git commit -m "feat: send native display frames as packet bytes"
```

## Task 6: Runtime Native Display Pump

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Add display pump profiling counters**

In `RuntimeMetricsCollector`, add:

```kotlin
fun recordNativeDisplayPumpWait(
    nanos: Long,
    woke: Boolean = true,
)

fun recordNativeDisplayFrameBytes(
    bytes: Int,
)
```

Add no-op implementations to `NoOpRuntimeMetricsCollector`.

Add atomic counters in `RecordingRuntimeMetricsCollector`:

```kotlin
private val nativeDisplayPumpWaitCalls = AtomicLong()
private val nativeDisplayPumpWaitNanos = AtomicLong()
private val nativeDisplayPumpWakeups = AtomicLong()
private val nativeDisplayPumpTimeouts = AtomicLong()
private val nativeDisplayFrameByteBatches = AtomicLong()
private val nativeDisplayFrameBytes = AtomicLong()
```

Wire these into `RuntimeVmProfilingSnapshot` or the existing display/runtime snapshot type with fields:

```kotlin
val nativeDisplayPumpWaitCalls: Long = 0
val nativeDisplayPumpWaitNanos: Long = 0
val nativeDisplayPumpWakeups: Long = 0
val nativeDisplayPumpTimeouts: Long = 0
val nativeDisplayFrameByteBatches: Long = 0
val nativeDisplayFrameBytes: Long = 0
```

- [ ] **Step 2: Run profiling tests and update expected assertions**

Run:

```bash
./gradlew :core:test --tests "*RuntimeProfilingTest" --rerun-tasks
```

Expected: FAIL until the snapshot and summary assertions are updated.

Update `RuntimeProfilingTest` with an assertion similar to:

```kotlin
collector.recordNativeDisplayPumpWait(nanos = 100, woke = true)
collector.recordNativeDisplayPumpWait(nanos = 50, woke = false)
collector.recordNativeDisplayFrameBytes(bytes = 128)

val snapshot = collector.snapshot()
assertEquals(2, snapshot.vm.nativeDisplayPumpWaitCalls)
assertEquals(150, snapshot.vm.nativeDisplayPumpWaitNanos)
assertEquals(1, snapshot.vm.nativeDisplayPumpWakeups)
assertEquals(1, snapshot.vm.nativeDisplayPumpTimeouts)
assertEquals(1, snapshot.vm.nativeDisplayFrameByteBatches)
assertEquals(128, snapshot.vm.nativeDisplayFrameBytes)
```

- [ ] **Step 3: Add the display pump to `RuntimeDeviceImpl`**

In `RuntimeDeviceImpl.kt`, import:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
```

Add fields:

```kotlin
private var displayPumpJob: Job? = null

private companion object {
    const val NATIVE_DISPLAY_PUMP_TIMEOUT_MILLIS: Long = 50
}
```

In `turnOn()`, after `observeLifecycle(handle)`, start the pump:

```kotlin
startNativeDisplayPump(handle)
```

In `handleVmStopped(...)` and `close()`, stop the pump:

```kotlin
stopNativeDisplayPump()
```

Add helper methods:

```kotlin
private fun startNativeDisplayPump(handle: BackgroundDeviceVm) {
    stopNativeDisplayPump()
    if (!handle.supportsNativeDisplayFramePump()) return
    displayPumpJob =
        serverScope.launch {
            var observed = handle.nativeDisplayWakeSequence() ?: return@launch
            while (isActive && vmHandle === handle) {
                val started = System.nanoTime()
                val next =
                    handle.waitForNativeDisplayWake(
                        observed,
                        NATIVE_DISPLAY_PUMP_TIMEOUT_MILLIS,
                    ) ?: break
                val woke = next > observed
                runtimeMetricsCollector.recordNativeDisplayPumpWait(System.nanoTime() - started, woke)
                observed = next
                if (!woke) {
                    delay(1)
                    continue
                }
                flushNativeDisplayFrameBytes(handle)
            }
        }
}

private fun stopNativeDisplayPump() {
    displayPumpJob?.cancel()
    displayPumpJob = null
}

private fun flushNativeDisplayFrameBytes(handle: BackgroundDeviceVm): Int {
    if (displaySessions.isEmpty()) return 0
    val payload = handle.drainNativeDisplayFrameBytes() ?: return 0
    if (payload.size <= 4) return 0
    runtimeMetricsCollector.recordNativeDisplayFrameBytes(payload.size)

    val toDetach = mutableListOf<Pair<UUID, Int>>()
    val sessions = displaySessions.values.toList()
    for (session in sessions) {
        if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
            toDetach += session.playerUuid to session.displayId
            continue
        }
        displayNetwork.sendNativeDisplayFrameBytes(session.playerUuid, session.containerId, payload)
    }
    toDetach.forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
    return sessions.size
}
```

Keep `serverTick()` fallback flushing, but skip native display frame drains when the pump is active:

```kotlin
val (flushedFrames, flushNanos) =
    measureNanos {
        if (displayPumpJob?.isActive == true) {
            0
        } else {
            flushDisplaySessions(handle)
        }
    }
```

- [ ] **Step 4: Run core tests**

Run:

```bash
./gradlew :core:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt
git commit -m "feat: pump native display frames on wake"
```

## Task 7: End-To-End Verification And Profiling Report

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Run targeted compilation and tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
```

Expected: PASS.

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so :compiler:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 2: Run runtime profiling**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel profileRuntimeVmImage
```

Expected: PASS and produce a Markdown report under `modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runs/.../runtime-vm-image.md`.

- [ ] **Step 3: Update profiling report format**

Update `RuntimeVmProfilingReport.kt` and formatter tests so the Markdown includes rows:

```text
Native display pump wait calls
Native display pump wait time
Native display pump wakeups
Native display pump timeouts
Native display frame byte batches
Native display frame bytes
```

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests "*RuntimeVmProfilingReportFormatterTest" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Run final full verification**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel :compiler:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test --rerun-tasks
```

Expected: PASS.

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/rust-owned-native-wait-scheduler/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel profileRuntimeVmImage
```

Expected: PASS.

- [ ] **Step 5: Commit final profiling/report updates**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "test: report native display frame pump metrics"
```

## Self-Review

- Spec coverage: the plan covers Rust display wake state, JNI waits, byte-array frame draining, Minecraft byte packet, client decode/apply, fallback legacy frame path, profiling, and verification.
- Scope check: the plan does not introduce TCP, does not move input events to Kotlin, and does not replace `ClientDisplayBuffer`.
- Type consistency: Kotlin method names are `displayWakeSequence`, `waitForDisplayWake`, `drainFrameBytes`, `sendNativeDisplayFrameBytes`, and `handleNativeDisplayFrameBytes` throughout the plan.
- Execution consistency: the native pump is started only for `BackgroundDeviceVm` with a native display registry; the existing tick drain remains available when the pump is inactive.
