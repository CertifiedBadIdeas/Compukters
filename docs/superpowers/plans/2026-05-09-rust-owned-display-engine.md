# Rust-Owned Display Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the native-runtime display hot path to a Rust-owned pixel display engine while keeping terminal behavior and CKL drawing decisions in userland.

**Architecture:** Add a Rust `DisplayEngine` and `DeviceDisplayRegistry` that own framebuffer memory, raster primitives, dirty tile tracking, and frame-delta construction. Kotlin remains the Minecraft integration layer and keeps the current Kotlin display implementation as fallback while native display parity and profiling mature.

**Tech Stack:** Kotlin/JVM, Rust, JNI, CKVM image host imports, existing `DisplayFrameDelta` wire model, Gradle, Cargo.

---

## File Structure

- Create: `native/ckl-vm/src/display.rs`
  - Rust-owned framebuffer, tile dirty tracker, mono font table, display primitive raster functions, and frame-delta structs.
- Modify: `native/ckl-vm/src/lib.rs`
  - Export the new `display` module.
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
  - Add a `DeviceDisplayRegistry` field so native device-local runtime state can own displays alongside events/IPC.
- Modify: `native/ckl-vm/src/image_runner.rs`
  - Route selected `display::*` host imports to the attached native display registry when a kernel is attached.
- Modify: `native/ckl-vm/src/jni.rs`
  - Expose native display lifecycle, primitive calls for Kotlin fallback routing, and frame draining.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
  - Add JNI bindings for native display lifecycle and frame draining.
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
  - Kotlin adapter that implements the same display operations as `DisplayRegistry` by calling `NativeVmBindings`.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
  - Keep current implementation as fallback; do not make it depend on Rust.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
  - Allow runtime display API to target either Kotlin fallback or native display adapter.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Create and free native display state when native runtime is enabled, then drain native frames through the existing `drainDisplayFrames()` surface.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
  - Add native display timing/copy counters without removing current Kotlin counters.
- Test: `native/ckl-vm/tests/display_engine.rs`
  - Rust parity tests for display primitives and dirty frame construction.
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
  - JNI lifecycle and frame-drain coverage.
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistryTest.kt`
  - Kotlin adapter parity against the current `DisplayState`/`DisplayRegistry` behavior.
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt`
  - Bundled terminal coverage proving native display path does not require terminal-specific host primitives.

## Guardrails

- Do not introduce `display::drawTerminalLine`, `display::renderShell`, or any terminal-semantic host primitive.
- Keep `terminal.ck` userland-owned.
- Keep Kotlin fallback display code until native display has parity tests and profiling evidence.
- Keep the existing `DisplayFrameDelta` payload shape for the first slice.
- Prefer native image-runner fast path for hot `display::*` imports, but allow an intermediate JNI-backed Kotlin adapter to make the migration testable.

### Task 1: Add Rust Display Engine Core

**Files:**

- Create: `native/ckl-vm/src/display.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Test: `native/ckl-vm/tests/display_engine.rs`

- [ ] **Step 1: Write the failing Rust display-engine tests**

Create `native/ckl-vm/tests/display_engine.rs`:

```rust
use ckl_vm::display::{DisplayEngine, PixelFormat};

fn payload_contains_rgb565(payload: &[u8], rgb565: u16) -> bool {
    let hi = (rgb565 >> 8) as u8;
    let lo = rgb565 as u8;
    payload.windows(2).any(|pair| pair == [hi, lo])
}

#[test]
fn present_returns_dirty_tiles_and_increments_sequence() {
    let mut display = DisplayEngine::new(7, 20, 10, PixelFormat::Rgb565).unwrap();

    display.fill_rect(1, 2, 3, 4, 0xF800);
    let first = display.present().expect("dirty frame");

    assert_eq!(first.display_id, 7);
    assert_eq!(first.sequence, 1);
    assert_eq!(first.width, 20);
    assert_eq!(first.height, 10);
    assert_eq!(first.pixel_format, PixelFormat::Rgb565);
    assert!(!first.full_refresh);
    assert!(!first.tiles.is_empty());
    assert!(display.present().is_none());
}

#[test]
fn full_refresh_marks_whole_display() {
    let mut display = DisplayEngine::new(1, 17, 17, PixelFormat::Rgb565).unwrap();

    let frame = display.full_refresh().expect("full refresh frame");

    assert!(frame.full_refresh);
    assert_eq!(frame.sequence, 1);
    assert_eq!(frame.tiles.len(), 4);
}

#[test]
fn copy_rect_copies_pixels_and_marks_destination_dirty() {
    let mut display = DisplayEngine::new(2, 8, 4, PixelFormat::Rgb565).unwrap();
    display.fill_rect(0, 0, 8, 4, 0x0000);
    display.fill_rect(0, 0, 2, 2, 0xF800);
    let _ = display.present();

    display.copy_rect(0, 0, 2, 2, 3, 1);
    let frame = display.present().expect("copy frame");
    let payload = frame.tiles.iter().flat_map(|tile| tile.payload.iter()).copied().collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0xF800));
    assert!(!frame.full_refresh);
}

#[test]
fn blit_mono_draws_foreground_and_background() {
    let mut display = DisplayEngine::new(3, 8, 4, PixelFormat::Rgb565).unwrap();

    display.blit_mono(1, 1, 3, 2, "101010", 0x07E0, Some(0x0000));
    let frame = display.present().expect("mono frame");
    let payload = frame.tiles.iter().flat_map(|tile| tile.payload.iter()).copied().collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0x07E0));
    assert!(payload_contains_rgb565(&payload, 0x0000));
}

#[test]
fn blit_mono5x7_text_draws_glyph_run() {
    let mut display = DisplayEngine::new(4, 18, 9, PixelFormat::Rgb565).unwrap();

    display.blit_mono5x7_text(0, 1, "AB", 0x07E0, None);
    let frame = display.present().expect("text frame");
    let payload = frame.tiles.iter().flat_map(|tile| tile.payload.iter()).copied().collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0x07E0));
}
```

- [ ] **Step 2: Run the Rust test and verify it fails**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test display_engine
```

Expected: FAIL because `ckl_vm::display` does not exist.

- [ ] **Step 3: Export the display module**

Modify `native/ckl-vm/src/lib.rs`:

```rust
pub mod display;
pub mod image;
pub mod image_runner;
pub mod jni;
pub mod runtime_kernel;
pub mod signal;
pub mod value;
```

- [ ] **Step 4: Implement the minimal Rust display engine**

Create `native/ckl-vm/src/display.rs` with these public types and methods:

```rust
use std::collections::{BTreeMap, BTreeSet};

const TILE_SIZE: i32 = 16;
const BYTES_PER_PIXEL_RGB565: usize = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelFormat {
    Rgb565,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DisplayTile {
    pub tile_x: i32,
    pub tile_y: i32,
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DisplayFrameDelta {
    pub display_id: i32,
    pub sequence: i64,
    pub width: i32,
    pub height: i32,
    pub pixel_format: PixelFormat,
    pub full_refresh: bool,
    pub tiles: Vec<DisplayTile>,
}

pub struct DisplayEngine {
    display_id: i32,
    width: i32,
    height: i32,
    pixel_format: PixelFormat,
    pixels: Vec<u16>,
    dirty_tiles: BTreeSet<(i32, i32)>,
    sequence: i64,
}

impl DisplayEngine {
    pub fn new(display_id: i32, width: i32, height: i32, pixel_format: PixelFormat) -> Result<Self, String> {
        if width <= 0 || height <= 0 {
            return Err(format!("display size must be positive, got {width}x{height}"));
        }
        let len = usize::try_from(width * height).map_err(|_| "display size overflows usize".to_string())?;
        Ok(Self {
            display_id,
            width,
            height,
            pixel_format,
            pixels: vec![0; len],
            dirty_tiles: BTreeSet::new(),
            sequence: 0,
        })
    }

    pub fn clear(&mut self, rgb565: u16) {
        self.pixels.fill(rgb565);
        self.mark_all_dirty();
    }

    pub fn set_pixel(&mut self, x: i32, y: i32, rgb565: u16) {
        if !self.in_bounds(x, y) {
            return;
        }
        let index = self.index(x, y);
        self.pixels[index] = rgb565;
        self.mark_rect_dirty(x, y, 1, 1);
    }

    pub fn fill_rect(&mut self, x: i32, y: i32, width: i32, height: i32, rgb565: u16) {
        for row in y.max(0)..(y + height).min(self.height) {
            for col in x.max(0)..(x + width).min(self.width) {
                let index = self.index(col, row);
                self.pixels[index] = rgb565;
            }
        }
        self.mark_rect_dirty(x, y, width, height);
    }

    pub fn copy_rect(&mut self, src_x: i32, src_y: i32, width: i32, height: i32, dst_x: i32, dst_y: i32) {
        if width <= 0 || height <= 0 {
            return;
        }
        let mut copied = Vec::new();
        for row in 0..height {
            for col in 0..width {
                let sx = src_x + col;
                let sy = src_y + row;
                copied.push(if self.in_bounds(sx, sy) { self.pixels[self.index(sx, sy)] } else { 0 });
            }
        }
        for row in 0..height {
            for col in 0..width {
                let dx = dst_x + col;
                let dy = dst_y + row;
                if self.in_bounds(dx, dy) {
                    let target = self.index(dx, dy);
                    self.pixels[target] = copied[(row * width + col) as usize];
                }
            }
        }
        self.mark_rect_dirty(dst_x, dst_y, width, height);
    }

    pub fn blit_mono(&mut self, x: i32, y: i32, width: i32, height: i32, mask: &str, foreground: u16, background: Option<u16>) {
        let bytes = mask.as_bytes();
        for row in 0..height {
            for col in 0..width {
                let target_x = x + col;
                let target_y = y + row;
                if !self.in_bounds(target_x, target_y) {
                    continue;
                }
                let mask_index = (row * width + col) as usize;
                let bit = bytes.get(mask_index).copied().unwrap_or(b'0');
                if bit == b'1' {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = foreground;
                } else if let Some(background) = background {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = background;
                }
            }
        }
        self.mark_rect_dirty(x, y, width, height);
    }

    pub fn blit_mono5x7_text(&mut self, x: i32, y: i32, text: &str, foreground: u16, background: Option<u16>) {
        if text.is_empty() {
            return;
        }
        for (index, ch) in text.chars().enumerate() {
            self.blit_mono5x7_packed(x + index as i32 * 6, y, mono5x7_glyph(ch), foreground, background);
        }
        let dirty_width = (text.chars().count() as i32 - 1) * 6 + 5;
        self.mark_rect_dirty(x, y, dirty_width, 7);
    }

    pub fn blit_mono5x7_packed(&mut self, x: i32, y: i32, glyph: u64, foreground: u16, background: Option<u16>) {
        for row in 0..7 {
            let bits = ((glyph >> ((6 - row) * 5)) & 0b11111) as i32;
            for col in 0..5 {
                let target_x = x + col;
                let target_y = y + row;
                if !self.in_bounds(target_x, target_y) {
                    continue;
                }
                if bits & (1 << (4 - col)) != 0 {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = foreground;
                } else if let Some(background) = background {
                    let index = self.index(target_x, target_y);
                    self.pixels[index] = background;
                }
            }
        }
        self.mark_rect_dirty(x, y, 5, 7);
    }

    pub fn present(&mut self) -> Option<DisplayFrameDelta> {
        if self.dirty_tiles.is_empty() {
            return None;
        }
        self.sequence += 1;
        let tiles = self.build_tiles();
        self.dirty_tiles.clear();
        Some(DisplayFrameDelta {
            display_id: self.display_id,
            sequence: self.sequence,
            width: self.width,
            height: self.height,
            pixel_format: self.pixel_format,
            full_refresh: false,
            tiles,
        })
    }

    pub fn full_refresh(&mut self) -> Option<DisplayFrameDelta> {
        self.mark_all_dirty();
        let mut frame = self.present()?;
        frame.full_refresh = true;
        Some(frame)
    }

    fn build_tiles(&self) -> Vec<DisplayTile> {
        self.dirty_tiles.iter().map(|&(tile_x, tile_y)| {
            let x = tile_x * TILE_SIZE;
            let y = tile_y * TILE_SIZE;
            let width = TILE_SIZE.min(self.width - x);
            let height = TILE_SIZE.min(self.height - y);
            let mut payload = Vec::with_capacity(width as usize * height as usize * BYTES_PER_PIXEL_RGB565);
            for row in y..y + height {
                for col in x..x + width {
                    let value = self.pixels[self.index(col, row)];
                    payload.push((value >> 8) as u8);
                    payload.push(value as u8);
                }
            }
            DisplayTile { tile_x, tile_y, x, y, width, height, payload }
        }).collect()
    }

    fn mark_all_dirty(&mut self) {
        self.mark_rect_dirty(0, 0, self.width, self.height);
    }

    fn mark_rect_dirty(&mut self, x: i32, y: i32, width: i32, height: i32) {
        if width <= 0 || height <= 0 {
            return;
        }
        let min_x = x.max(0);
        let min_y = y.max(0);
        let max_x = (x + width - 1).min(self.width - 1);
        let max_y = (y + height - 1).min(self.height - 1);
        if min_x > max_x || min_y > max_y {
            return;
        }
        for tile_y in (min_y / TILE_SIZE)..=(max_y / TILE_SIZE) {
            for tile_x in (min_x / TILE_SIZE)..=(max_x / TILE_SIZE) {
                self.dirty_tiles.insert((tile_x, tile_y));
            }
        }
    }

    fn in_bounds(&self, x: i32, y: i32) -> bool {
        x >= 0 && y >= 0 && x < self.width && y < self.height
    }

    fn index(&self, x: i32, y: i32) -> usize {
        (y * self.width + x) as usize
    }
}

pub struct DeviceDisplayRegistry {
    displays: BTreeMap<i32, DisplayEngine>,
    pending_frames: Vec<DisplayFrameDelta>,
}

impl DeviceDisplayRegistry {
    pub fn new() -> Self {
        Self { displays: BTreeMap::new(), pending_frames: Vec::new() }
    }
}

fn mono5x7_glyph(ch: char) -> u64 {
    match ch {
        'A' => 0b01110100011000111111100011000110001,
        'B' => 0b11110100011000111110100011000111110,
        _ => 0b11111100011000110001100011000111111,
    }
}
```

The minimal glyph function intentionally covers only the first parity tests. Later tasks replace it with the full table.

- [ ] **Step 5: Run the Rust display test and verify it passes**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test display_engine
```

Expected: PASS.

- [ ] **Step 6: Commit the Rust display core**

Run:

```bash
git add native/ckl-vm/src/display.rs native/ckl-vm/src/lib.rs native/ckl-vm/tests/display_engine.rs
git commit -m "feat: add native display engine core"
```

### Task 2: Add Device Display Registry Behavior in Rust

**Files:**

- Modify: `native/ckl-vm/src/display.rs`
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Test: `native/ckl-vm/tests/display_engine.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`

- [ ] **Step 1: Add failing registry tests**

Append to `native/ckl-vm/tests/display_engine.rs`:

```rust
use ckl_vm::display::DeviceDisplayRegistry;

#[test]
fn registry_attach_queues_full_refresh_and_drain_frames() {
    let mut registry = DeviceDisplayRegistry::new();

    registry.attach(9, 18, 18, PixelFormat::Rgb565).unwrap();
    let frames = registry.drain_frames();

    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0].display_id, 9);
    assert!(frames[0].full_refresh);
    assert_eq!(registry.first_display_id(), Some(9));
}

#[test]
fn registry_present_queues_dirty_frame() {
    let mut registry = DeviceDisplayRegistry::new();
    registry.attach(9, 18, 18, PixelFormat::Rgb565).unwrap();
    let _ = registry.drain_frames();

    registry.fill_rect(9, 0, 0, 2, 2, 0x07E0);
    registry.present(9);
    let frames = registry.drain_frames();

    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0].sequence, 2);
    assert!(!frames[0].full_refresh);
}
```

- [ ] **Step 2: Run the registry tests and verify they fail**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test display_engine registry_
```

Expected: FAIL because registry methods do not exist.

- [ ] **Step 3: Implement `DeviceDisplayRegistry` methods**

Replace the `DeviceDisplayRegistry` impl in `native/ckl-vm/src/display.rs` with:

```rust
impl DeviceDisplayRegistry {
    pub fn new() -> Self {
        Self { displays: BTreeMap::new(), pending_frames: Vec::new() }
    }

    pub fn attach(&mut self, display_id: i32, width: i32, height: i32, pixel_format: PixelFormat) -> Result<(), String> {
        let mut display = DisplayEngine::new(display_id, width, height, pixel_format)?;
        if let Some(frame) = display.full_refresh() {
            self.pending_frames.push(frame);
        }
        self.displays.insert(display_id, display);
        Ok(())
    }

    pub fn detach(&mut self, display_id: i32) {
        self.displays.remove(&display_id);
    }

    pub fn first_display_id(&self) -> Option<i32> {
        self.displays.keys().next().copied()
    }

    pub fn clear(&mut self, display_id: i32, rgb565: u16) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.clear(rgb565);
        }
    }

    pub fn fill_rect(&mut self, display_id: i32, x: i32, y: i32, width: i32, height: i32, rgb565: u16) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.fill_rect(x, y, width, height, rgb565);
        }
    }

    pub fn copy_rect(&mut self, display_id: i32, src_x: i32, src_y: i32, width: i32, height: i32, dst_x: i32, dst_y: i32) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.copy_rect(src_x, src_y, width, height, dst_x, dst_y);
        }
    }

    pub fn blit_mono5x7_text(&mut self, display_id: i32, x: i32, y: i32, text: &str, foreground: u16, background: Option<u16>) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            display.blit_mono5x7_text(x, y, text, foreground, background);
        }
    }

    pub fn present(&mut self, display_id: i32) {
        if let Some(display) = self.displays.get_mut(&display_id) {
            if let Some(frame) = display.present() {
                self.pending_frames.push(frame);
            }
        }
    }

    pub fn drain_frames(&mut self) -> Vec<DisplayFrameDelta> {
        std::mem::take(&mut self.pending_frames)
    }
}
```

- [ ] **Step 4: Attach the display registry to the native device kernel**

Modify `native/ckl-vm/src/runtime_kernel.rs`:

```rust
use crate::display::DeviceDisplayRegistry;
```

Add a field:

```rust
pub displays: DeviceDisplayRegistry,
```

Initialize it in `DeviceRuntimeKernel::new`:

```rust
displays: DeviceDisplayRegistry::new(),
```

- [ ] **Step 5: Add a kernel-level test**

Append to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn device_kernel_owns_display_registry() {
    let mut kernel = DeviceRuntimeKernel::new(64, 4096);

    kernel.displays.attach(12, 18, 18, ckl_vm::display::PixelFormat::Rgb565).unwrap();

    assert_eq!(kernel.displays.first_display_id(), Some(12));
    assert_eq!(kernel.displays.drain_frames().len(), 1);
}
```

- [ ] **Step 6: Run Rust tests and verify they pass**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test display_engine
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner device_kernel_owns_display_registry
```

Expected: PASS.

- [ ] **Step 7: Commit the registry**

Run:

```bash
git add native/ckl-vm/src/display.rs native/ckl-vm/src/runtime_kernel.rs native/ckl-vm/tests/display_engine.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: add native device display registry"
```

### Task 3: Expose Native Display Lifecycle and Frame Drain Through JNI

**Files:**

- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] **Step 1: Add failing Kotlin JNI binding test**

Append to `NativeImageVmBindingsJniTest`:

```kotlin
@Test
fun nativeDisplayBindingsExposeLifecycleAndFrameDrain() {
    val memberNames = NativeVmBindings::class.java.declaredMethods.map { it.name }.toSet()

    assertTrue("attachNativeDisplay" in memberNames)
    assertTrue("detachNativeDisplay" in memberNames)
    assertTrue("nativeDisplayFillRect" in memberNames)
    assertTrue("nativeDisplayPresent" in memberNames)
    assertTrue("drainNativeDisplayFrames" in memberNames)
}

@Test
fun nativeDisplayAttachPresentAndDrainWhenLibraryIsConfigured() {
    val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val kernelHandle = NativeVmBindings.createDeviceKernel(maxEventQueueSize = 64, maxBufferedBytesPerChannel = 4096)

    try {
        NativeVmBindings.attachNativeDisplay(kernelHandle, displayId = 3, width = 18, height = 18)
        val initial = NativeVmBindings.drainNativeDisplayFrames(kernelHandle)
        assertTrue(initial.isNotEmpty(), "attach should queue a full refresh frame")

        NativeVmBindings.nativeDisplayFillRect(kernelHandle, displayId = 3, x = 0, y = 0, width = 2, height = 2, rgb565 = 0x07E0)
        NativeVmBindings.nativeDisplayPresent(kernelHandle, displayId = 3)
        val dirty = NativeVmBindings.drainNativeDisplayFrames(kernelHandle)

        assertTrue(dirty.isNotEmpty(), "present should queue a dirty frame")
    } finally {
        NativeVmBindings.freeDeviceKernel(kernelHandle)
    }
}
```

- [ ] **Step 2: Run the JNI test and verify it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDisplay*' --rerun-tasks
```

Expected: FAIL because Kotlin bindings and JNI functions do not exist.

- [ ] **Step 3: Add Kotlin binding methods**

Modify `NativeVmBindings.kt`:

```kotlin
fun attachNativeDisplay(
    kernelHandle: Long,
    displayId: Int,
    width: Int,
    height: Int,
) {
    require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
    attachNativeDisplayNative(kernelHandle, displayId, width, height)
}

fun detachNativeDisplay(
    kernelHandle: Long,
    displayId: Int,
) {
    require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
    detachNativeDisplayNative(kernelHandle, displayId)
}

fun nativeDisplayFillRect(
    kernelHandle: Long,
    displayId: Int,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    rgb565: Int,
) {
    require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
    nativeDisplayFillRectNative(kernelHandle, displayId, x, y, width, height, rgb565)
}

fun nativeDisplayPresent(
    kernelHandle: Long,
    displayId: Int,
) {
    require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
    nativeDisplayPresentNative(kernelHandle, displayId)
}

fun drainNativeDisplayFrames(kernelHandle: Long): ByteArray {
    require(kernelHandle != 0L) { "Native device runtime kernel handle is zero" }
    return drainNativeDisplayFramesNative(kernelHandle)
}
```

Add matching private extern declarations:

```kotlin
@JvmStatic private external fun attachNativeDisplayNative(kernelHandle: Long, displayId: Int, width: Int, height: Int)
@JvmStatic private external fun detachNativeDisplayNative(kernelHandle: Long, displayId: Int)
@JvmStatic private external fun nativeDisplayFillRectNative(kernelHandle: Long, displayId: Int, x: Int, y: Int, width: Int, height: Int, rgb565: Int)
@JvmStatic private external fun nativeDisplayPresentNative(kernelHandle: Long, displayId: Int)
@JvmStatic private external fun drainNativeDisplayFramesNative(kernelHandle: Long): ByteArray
```

- [ ] **Step 4: Add JNI functions**

Modify `native/ckl-vm/src/jni.rs`. Add imports:

```rust
use crate::display::PixelFormat;
```

Add functions:

```rust
#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_attachNativeDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
    width: jint,
    height: jint,
) {
    let kernel = match kernel_handle_mut(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    if let Err(error) = kernel.displays.attach(display_id, width, height, PixelFormat::Rgb565) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error);
    }
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_detachNativeDisplayNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
) {
    let kernel = match kernel_handle_mut(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel.displays.detach(display_id);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayFillRectNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
    x: jint,
    y: jint,
    width: jint,
    height: jint,
    rgb565: jint,
) {
    let kernel = match kernel_handle_mut(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel.displays.fill_rect(display_id, x, y, width, height, rgb565 as u16);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_nativeDisplayPresentNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
    display_id: jint,
) {
    let kernel = match kernel_handle_mut(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return,
    };
    kernel.displays.present(display_id);
}

#[no_mangle]
pub extern "system" fn Java_ru_lazyhat_compukterkraft_lang_runtime_blazing_NativeVmBindings_drainNativeDisplayFramesNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    kernel_handle: jlong,
) -> jbyteArray {
    let kernel = match kernel_handle_mut(&mut env, kernel_handle) {
        Some(kernel) => kernel,
        None => return null_mut(),
    };
    let frames = kernel.displays.drain_frames();
    let bytes = encode_display_frames(&frames);
    byte_array_or_throw(&mut env, &bytes)
}
```

Add a temporary encoder at the bottom of `jni.rs`:

```rust
fn encode_display_frames(frames: &[crate::display::DisplayFrameDelta]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(frames.len() as i32).to_le_bytes());
    for frame in frames {
        out.extend_from_slice(&frame.display_id.to_le_bytes());
        out.extend_from_slice(&frame.sequence.to_le_bytes());
        out.extend_from_slice(&frame.width.to_le_bytes());
        out.extend_from_slice(&frame.height.to_le_bytes());
        out.push(match frame.pixel_format {
            PixelFormat::Rgb565 => 0,
        });
        out.push(if frame.full_refresh { 1 } else { 0 });
        out.extend_from_slice(&(frame.tiles.len() as i32).to_le_bytes());
        for tile in &frame.tiles {
            out.extend_from_slice(&tile.tile_x.to_le_bytes());
            out.extend_from_slice(&tile.tile_y.to_le_bytes());
            out.extend_from_slice(&tile.x.to_le_bytes());
            out.extend_from_slice(&tile.y.to_le_bytes());
            out.extend_from_slice(&tile.width.to_le_bytes());
            out.extend_from_slice(&tile.height.to_le_bytes());
            out.extend_from_slice(&(tile.payload.len() as i32).to_le_bytes());
            out.extend_from_slice(&tile.payload);
        }
    }
    out
}
```

- [ ] **Step 5: Run JNI tests and verify they pass**

Run:

```bash
cargo build --manifest-path native/ckl-vm/Cargo.toml
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest.nativeDisplay*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit JNI lifecycle and frame drain**

Run:

```bash
git add native/ckl-vm/src/jni.rs modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: expose native display lifecycle"
```

### Task 4: Add Kotlin Native Display Frame Decoder and Adapter

**Files:**

- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayFrameCodec.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistryTest.kt`

- [ ] **Step 1: Write failing codec and adapter tests**

Create `NativeDisplayRegistryTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeDisplayRegistryTest {
    @Test
    fun nativeRegistryAttachQueuesFullRefreshWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(64, 4096)
        val registry = NativeDisplayRegistry(kernelHandle)

        try {
            registry.attach(displayId = 5, width = 18, height = 18)
            val frames = registry.drainFrames()

            assertEquals(1, frames.size)
            assertEquals(5, frames[0].displayId)
            assertTrue(frames[0].fullRefresh)
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }

    @Test
    fun nativeRegistryFillRectPresentDrainsDirtyFrameWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val kernelHandle = NativeVmBindings.createDeviceKernel(64, 4096)
        val registry = NativeDisplayRegistry(kernelHandle)

        try {
            registry.attach(displayId = 5, width = 18, height = 18)
            registry.drainFrames()
            registry.fillRect(displayId = 5, x = 0, y = 0, width = 2, height = 2, rgb565 = 0x07E0)
            registry.present(displayId = 5)
            val frames = registry.drainFrames()

            assertEquals(1, frames.size)
            assertEquals(2L, frames[0].sequence)
            assertTrue(frames[0].tiles.isNotEmpty())
        } finally {
            NativeVmBindings.freeDeviceKernel(kernelHandle)
        }
    }
}
```

- [ ] **Step 2: Run the adapter test and verify it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*NativeDisplayRegistryTest*' --rerun-tasks
```

Expected: FAIL because `NativeDisplayRegistry` does not exist.

- [ ] **Step 3: Implement native frame decoder**

Create `NativeDisplayFrameCodec.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeDisplayFrameCodec {
    fun decodeFrames(bytes: ByteArray): List<DisplayFrameDelta> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = input.int
        return List(count) {
            val displayId = input.int
            val sequence = input.long
            val width = input.int
            val height = input.int
            val pixelFormat = when (input.get().toInt()) {
                0 -> DisplayPixelFormat.RGB565
                else -> error("Unknown native display pixel format")
            }
            val fullRefresh = input.get().toInt() != 0
            val tileCount = input.int
            val tiles = List(tileCount) {
                val tileX = input.int
                val tileY = input.int
                val x = input.int
                val y = input.int
                val tileWidth = input.int
                val tileHeight = input.int
                val payloadLength = input.int
                val payload = ByteArray(payloadLength)
                input.get(payload)
                DisplayTile(tileX, tileY, x, y, tileWidth, tileHeight, payload)
            }
            DisplayFrameDelta(displayId, sequence, width, height, pixelFormat, fullRefresh, tiles)
        }
    }
}
```

- [ ] **Step 4: Implement native display adapter**

Create `NativeDisplayRegistry.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

class NativeDisplayRegistry(
    private val kernelHandle: Long,
) {
    fun attach(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo {
        require(pixelFormat == DisplayPixelFormat.RGB565) { "Native display supports RGB565 only" }
        NativeVmBindings.attachNativeDisplay(kernelHandle, displayId, width, height)
        return DisplayInfo(displayId, width, height, pixelFormat)
    }

    fun detach(displayId: Int) {
        NativeVmBindings.detachNativeDisplay(kernelHandle, displayId)
    }

    fun fillRect(displayId: Int, x: Int, y: Int, width: Int, height: Int, rgb565: Int) {
        NativeVmBindings.nativeDisplayFillRect(kernelHandle, displayId, x, y, width, height, rgb565)
    }

    fun present(displayId: Int) {
        NativeVmBindings.nativeDisplayPresent(kernelHandle, displayId)
    }

    fun drainFrames(): List<DisplayFrameDelta> =
        NativeDisplayFrameCodec.decodeFrames(NativeVmBindings.drainNativeDisplayFrames(kernelHandle))
}
```

- [ ] **Step 5: Run adapter tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*NativeDisplayRegistryTest*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Kotlin adapter**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayFrameCodec.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistryTest.kt
git commit -m "feat: add native display registry adapter"
```

### Task 5: Route Native Image Display Host Imports to Rust

**Files:**

- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/display.rs`
- Test: `native/ckl-vm/tests/image_runner.rs`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerJniTest.kt`

- [ ] **Step 1: Add a failing Rust image-runner fast-path test**

Append to `native/ckl-vm/tests/image_runner.rs`:

```rust
#[test]
fn attached_kernel_handles_display_fill_rect_and_present_imports() {
    let mut kernel = DeviceRuntimeKernel::new(64, 4096);
    kernel.displays.attach(1, 18, 18, ckl_vm::display::PixelFormat::Rgb565).unwrap();
    let _ = kernel.displays.drain_frames();

    let image = image_with_host_imports_and_code(
        vec![
            HostImportFixture::new(1003, "display", "fillRect"),
            HostImportFixture::new(1011, "display", "present"),
        ],
        vec![
            ConstantFixture::Int(1), ConstantFixture::Int(0), ConstantFixture::Int(0),
            ConstantFixture::Int(2), ConstantFixture::Int(2), ConstantFixture::Int(2016),
        ],
        0,
        vec![
            OP_PUSH_CONSTANT, 0, 0, 0, 0,
            OP_PUSH_CONSTANT, 1, 0, 0, 0,
            OP_PUSH_CONSTANT, 2, 0, 0, 0,
            OP_PUSH_CONSTANT, 3, 0, 0, 0,
            OP_PUSH_CONSTANT, 4, 0, 0, 0,
            OP_PUSH_CONSTANT, 5, 0, 0, 0,
            OP_CALL_HOST, 1003_u32 as u8, (1003_u32 >> 8) as u8, 0, 0, 6, 0, 0, 0,
            OP_POP,
            OP_PUSH_CONSTANT, 0, 0, 0, 0,
            OP_CALL_HOST, 1011_u32 as u8, (1011_u32 >> 8) as u8, 0, 0, 1, 0, 0, 0,
            OP_RETURN,
        ],
    );
    let mut vm = ImageVmHandle::create(&image, 512).unwrap();
    vm.attach_device_kernel(&mut kernel as *mut DeviceRuntimeKernel).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 0, "program should halt instead of emitting display host calls");
    assert_eq!(kernel.displays.drain_frames().len(), 1);
}
```

If fixture helpers do not currently support named host imports, add a small local helper near the existing image fixture code in the same test file.

- [ ] **Step 2: Run the focused Rust test and verify it fails**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner attached_kernel_handles_display_fill_rect_and_present_imports
```

Expected: FAIL because display host imports still fall back to Kotlin.

- [ ] **Step 3: Add native display host import handling**

Modify `try_native_host_import` flow in `native/ckl-vm/src/image_runner.rs` so it can access `self.attached_kernel` for display imports. Add a method:

```rust
fn try_attached_kernel_host_import(
    &mut self,
    module_name: &str,
    function_name: &str,
    arguments: Vec<VmValue>,
) -> Result<NativeHostImportResult, String> {
    if module_name != "display" {
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let Some(kernel_pointer) = self.attached_kernel else {
        return Ok(NativeHostImportResult::Fallback(arguments));
    };
    let kernel = unsafe { &mut *kernel_pointer };
    match function_name {
        "fillRect" => {
            let display_id = arguments[0].as_int("display.fillRect displayId")?;
            let x = arguments[1].as_int("display.fillRect x")?;
            let y = arguments[2].as_int("display.fillRect y")?;
            let width = arguments[3].as_int("display.fillRect width")?;
            let height = arguments[4].as_int("display.fillRect height")?;
            let rgb565 = arguments[5].as_int("display.fillRect rgb565")? as u16;
            kernel.displays.fill_rect(display_id, x, y, width, height, rgb565);
            Ok(NativeHostImportResult::Handled(VmValue::Unit))
        }
        "present" => {
            let display_id = arguments[0].as_int("display.present displayId")?;
            kernel.displays.present(display_id);
            Ok(NativeHostImportResult::Handled(VmValue::Unit))
        }
        _ => Ok(NativeHostImportResult::Fallback(arguments)),
    }
}
```

Add `as_int` helper on `VmValue` locally if needed:

```rust
trait VmValueIntExt {
    fn as_int(&self, context: &str) -> Result<i32, String>;
}

impl VmValueIntExt for VmValue {
    fn as_int(&self, context: &str) -> Result<i32, String> {
        match self {
            VmValue::Int(value) => Ok(*value),
            other => Err(format!("{context} requires Int but found {other:?}")),
        }
    }
}
```

Call this method before generic fallback in `OP_CALL_HOST`.

- [ ] **Step 4: Extend fast path to text run after fill/present passes**

Add `blitMono5x7Text` support to the same match:

```rust
"blitMono5x7Text" => {
    let display_id = arguments[0].as_int("display.blitMono5x7Text displayId")?;
    let x = arguments[1].as_int("display.blitMono5x7Text x")?;
    let y = arguments[2].as_int("display.blitMono5x7Text y")?;
    let text = match &arguments[3] {
        VmValue::String(value) => value.as_str(),
        other => return Err(format!("display.blitMono5x7Text text requires String but found {other:?}")),
    };
    let foreground = arguments[4].as_int("display.blitMono5x7Text foreground")? as u16;
    let background = match arguments[5].as_int("display.blitMono5x7Text background")? {
        value if value < 0 => None,
        value => Some(value as u16),
    };
    kernel.displays.blit_mono5x7_text(display_id, x, y, text, foreground, background);
    Ok(NativeHostImportResult::Handled(VmValue::Unit))
}
```

- [ ] **Step 5: Run native image-runner tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner attached_kernel_handles_display_fill_rect_and_present_imports
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_runner
```

Expected: PASS.

- [ ] **Step 6: Commit native display import fast path**

Run:

```bash
git add native/ckl-vm/src/image_runner.rs native/ckl-vm/src/display.rs native/ckl-vm/tests/image_runner.rs
git commit -m "feat: route display imports to native display engine"
```

### Task 6: Wire Native Display Into BackgroundDeviceVm Behind an Opt-In

**Files:**

- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add a failing core integration test**

Append to `BackgroundDeviceVmTest`:

```kotlin
@Test
fun nativeDisplayPathDrainsAttachFullRefreshWhenEnabled() {
    val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val vm = newVm(
        nativeLibraryPath = libraryPath,
        nativeDisplayEnabled = true,
    )

    try {
        assertTrue(vm.boot())
        vm.attachDisplay(displayId = 4, width = 18, height = 18)

        val frames = vm.drainDisplayFrames()

        assertTrue(frames.any { it.displayId == 4 && it.fullRefresh })
    } finally {
        vm.shutdown()
    }
}
```

If `newVm` has no `nativeDisplayEnabled` parameter, add it to the test helper only after this test fails.

- [ ] **Step 2: Run the focused core test and verify it fails**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*BackgroundDeviceVmTest.nativeDisplayPathDrainsAttachFullRefreshWhenEnabled' --rerun-tasks
```

Expected: FAIL because native display is not wired into the VM.

- [ ] **Step 3: Add native display selection to VM construction**

Modify `BackgroundDeviceVm` constructor or factory configuration with:

```kotlin
private val nativeDisplayEnabled: Boolean =
    System.getProperty("ckl.vm.native.display") == "true"
```

Use the native display registry only when all are true:

- native runtime library path is configured
- native device kernel handle exists
- `ckl.vm.native.display == true`

- [ ] **Step 4: Route attach/detach/drain through native registry when enabled**

In `BackgroundDeviceVm.attachDisplay`, call:

```kotlin
nativeDisplayRegistry?.attach(displayId, width, height, pixelFormat)
    ?: displayRegistry.attach(displayId, width, height, pixelFormat)
```

In `BackgroundDeviceVm.drainDisplayFrames`, call:

```kotlin
override fun drainDisplayFrames(): List<DisplayFrameDelta> =
    nativeDisplayRegistry?.drainFrames() ?: displayRegistry.drainFrames()
```

Keep fallback behavior unchanged.

- [ ] **Step 5: Run focused core test**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :core:test --tests '*BackgroundDeviceVmTest.nativeDisplayPathDrainsAttachFullRefreshWhenEnabled' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit BackgroundDeviceVm native display opt-in**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/NativeDisplayRegistry.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: wire native display registry into device vm"
```

### Task 7: Native Display Parity for Terminal Text Path

**Files:**

- Modify: `native/ckl-vm/src/display.rs`
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt`
- Test: `native/ckl-vm/tests/display_engine.rs`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add Rust tests for full text glyph coverage used by terminal**

Append to `native/ckl-vm/tests/display_engine.rs`:

```rust
#[test]
fn text_run_supports_digits_lowercase_and_punctuation() {
    let mut display = DisplayEngine::new(6, 120, 9, PixelFormat::Rgb565).unwrap();

    display.blit_mono5x7_text(0, 1, "abc xyz 123 /.-:", 0x07E0, None);
    let frame = display.present().expect("text frame");
    let payload = frame.tiles.iter().flat_map(|tile| tile.payload.iter()).copied().collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0x07E0));
}
```

- [ ] **Step 2: Replace minimal glyph function with the full Mono5x7 table**

Port the glyph table from `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/Mono5x7Font.kt` into `native/ckl-vm/src/display.rs`.

Use a local function:

```rust
fn mono5x7_glyph(ch: char) -> u64 {
    let code = ch as usize;
    if code < MONO5X7_GLYPHS.len() {
        MONO5X7_GLYPHS[code]
    } else {
        UNKNOWN_GLYPH
    }
}
```

- [ ] **Step 3: Add terminal-level native display profiling regression**

Append to v1_21_1 neoforge `BackgroundDeviceVmTest`:

```kotlin
@Test
fun bundledRomTerminalUsesNativeDisplayTextRunsWhenEnabled() {
    val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
    val vm = newBundledRomVm(nativeLibraryPath = libraryPath, nativeDisplayEnabled = true)

    try {
        bootToShellPrompt(vm)
        typeText(vm, "ab")
        runVmTicks(vm, ticks = 8)

        val frames = vm.drainDisplayFrames()

        assertTrue(frames.isNotEmpty(), "native display path should emit client-visible frames")
    } finally {
        vm.shutdown()
    }
}
```

Adapt helper names to the existing test file; do not add terminal-specific production APIs to make this pass.

- [ ] **Step 4: Run parity tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test display_engine text_run_supports_digits_lowercase_and_punctuation
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :v1_21_1-neoforge:test --tests '*BackgroundDeviceVmTest.bundledRomTerminalUsesNativeDisplayTextRunsWhenEnabled' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit terminal text parity**

Run:

```bash
git add native/ckl-vm/src/display.rs native/ckl-vm/tests/display_engine.rs modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt
git commit -m "test: cover native display terminal text path"
```

### Task 8: Add Native Display Profiling Metrics

**Files:**

- Modify: `native/ckl-vm/src/display.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Add failing profiling report assertions**

In `RuntimeVmProfilingReportFormatterTest`, add an assertion that formatted Markdown contains:

```kotlin
assertTrue(markdown.contains("Native display raster"))
assertTrue(markdown.contains("Native display frame copy"))
```

- [ ] **Step 2: Run the formatter test and verify it fails**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest*' --rerun-tasks
```

Expected: FAIL because native display metrics are not formatted.

- [ ] **Step 3: Add Kotlin metric fields**

Extend display profiling snapshots with:

```kotlin
val nativeRasterNanos: Long = 0
val nativeFrameBuildNanos: Long = 0
val nativeFrameCopyNanos: Long = 0
val nativeFramePayloadBytes: Long = 0
```

Add summary labels:

```kotlin
"    native raster: time=${nativeRasterNanos.nanos()}"
"    native frame build: time=${nativeFrameBuildNanos.nanos()}"
"    native frame copy: bytes=${nativeFramePayloadBytes.bytes()}, time=${nativeFrameCopyNanos.nanos()}"
```

- [ ] **Step 4: Include metrics in historical report rows**

Add rows to `RuntimeVmProfilingReport.kt`:

```kotlin
MetricRow("Native display raster", { it.display.nativeRasterNanos })
MetricRow("Native display frame build", { it.display.nativeFrameBuildNanos })
MetricRow("Native display frame copy", { it.display.nativeFrameCopyNanos })
MetricRow("Native display frame bytes", { it.display.nativeFramePayloadBytes })
```

Use the existing human-readable formatter for nanos and bytes.

- [ ] **Step 5: Run profiling tests**

Run:

```bash
./gradlew :core:test --tests '*DisplayProfilingTest*' --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit profiling metrics**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report native display profiling metrics"
```

### Task 9: Final Verification and Historical Profile

**Files:**

- Modify only if prior tests reveal a defect.
- Report: `modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-comparison.md`

- [ ] **Step 1: Run Rust test suite**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run compiler native JNI tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --tests '*NativeImageVmRunnerJniTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Run focused core display tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :core:test --tests '*DisplayStateTest*' --tests '*NativeDisplayRegistryTest*' --tests '*BackgroundDeviceVmTest.nativeDisplay*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Run bundled ROM and terminal tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RomScriptCompileTest*' --rerun-tasks
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true :v1_21_1-neoforge:test --tests '*BackgroundDeviceVmTest.bundledRomTerminal*' --tests '*RuntimeDisplayProfilingTest*' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Run historical profiling comparison**

Run:

```bash
./gradlew -Dckl.vm.native.display=true profileRuntimeVmComparison
```

Expected: PASS and a new timestamped run under `modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runs/`.

- [ ] **Step 6: Inspect the comparison report**

Run:

```bash
rg -n "Native display|blitMono5x7Text|blitMono5x7Packed|sustained terminal no-delay|bundled terminal" modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-comparison.md
```

Expected:

- Native display metric rows exist.
- `bundled terminal` and `sustained terminal no-delay` have client-visible frame counts.
- No terminal-specific host primitive appears in the report.

- [ ] **Step 7: Commit verification note if docs changed**

If verification required documentation edits, run:

```bash
git add docs/PROFILING.md docs/ARCHITECTURE.md docs/MACHINE.md
git commit -m "docs: document native display engine profiling"
```

If no docs changed, do not create an empty commit.

## Self-Review

- Spec coverage: the plan covers Rust-owned framebuffer/raster/dirty/frame building, Kotlin fallback, native display frame drain, profiling, and terminal userland guardrails.
- No terminal semantic host primitive is introduced.
- The first implementation slice keeps `DisplayFrameDelta` compatible with the current Kotlin/client model.
- The plan has one known implementation checkpoint: Task 5 may need small fixture helpers in `native/ckl-vm/tests/image_runner.rs` because the current helper shape is test-local. The task explicitly requires adding those helpers in the test file, not changing production APIs.
- Full removal of Kotlin display hot-path code is intentionally excluded until native parity and profiling are stable.
