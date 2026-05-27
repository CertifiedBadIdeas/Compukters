# CkVmImage ABI Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first Rust-native CKL VM image ABI skeleton with a Kotlin encoder, Rust decoder, and cross-language tests.

**Architecture:** This slice does not replace the current `BytecodeModule` or native VM runner. It introduces a separate `CkVmImage` binary format under a new package/module so the future Rust VM can consume an image that is not shaped like the Kotlin VM bytecode. Kotlin owns the initial encoder; Rust owns the decoder and will later execute the decoded image.

**Tech Stack:** Kotlin/JVM, Kotlin test, Gradle, Rust 2021, Cargo, `thiserror`.

---

## File Structure

- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
  - Kotlin data model for the new image artifact.
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
  - Kotlin binary encoder and ABI constants.
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`
  - Kotlin tests for deterministic encoding, magic/version, section content, and malformed model validation.
- Create: `native/ckl-vm/src/image.rs`
  - Rust decoder, structs, constants, and errors for the new image ABI.
- Modify: `native/ckl-vm/src/lib.rs`
  - Export the new `image` module.
- Create: `native/ckl-vm/tests/image_decode.rs`
  - Rust tests for decoding valid images and rejecting malformed bytes.

## ABI v1 Skeleton Format

All integer fields are little-endian. Strings are UTF-8 with signed 32-bit byte length. Lists use signed 32-bit element counts and reject negative counts.

Top-level order:

1. Magic bytes: `CKIM`
2. ABI version: `u8 = 1`
3. Language version: `string`
4. Target ABI version: `u16`
5. Capabilities: `list<string>`
6. Constants: `list<constant>`
7. Host imports: `list<hostImport>`
8. Entry function index: `i32`
9. Functions: `list<function>`

Constant tags:

- `1`: `String(value: string)`
- `2`: `Int(value: i32)`
- `3`: `Long(value: i64)`

Host import fields:

1. Import id: `i32`
2. Module name: `string`
3. Function name: `string`
4. Parameter types: `list<string>`
5. Return type: `string`

Function fields:

1. Name: `string`
2. Frame size: `i32`
3. Code bytes: `byteArray`, encoded as signed 32-bit byte length followed by raw bytes

This is intentionally a skeleton. It establishes stable section mechanics and cross-language decoding before adding type layouts, code opcodes, debug sections, or memory sections.

---

### Task 1: Kotlin Image Model and RED Encoder Tests

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`

- [ ] **Step 1: Create the failing Kotlin ABI test**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CkVmImageAbiTest {
    @Test
    fun encodedImageStartsWithMagicAndVersion() {
        val bytes = CkVmImageAbi.encode(minimalImage())

        assertContentEquals(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt())
    }

    @Test
    fun encodedImageIsDeterministic() {
        val image = representativeImage()

        assertContentEquals(CkVmImageAbi.encode(image), CkVmImageAbi.encode(image))
    }

    @Test
    fun encodedImageContainsSkeletonSections() {
        val bytes = CkVmImageAbi.encode(representativeImage())
        val reader = TestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(1, reader.u8())
        assertEquals("ckl-1", reader.string())
        assertEquals(1, reader.u16())
        assertEquals(listOf("host-import-ids"), reader.stringList())
        assertEquals(3, reader.i32())
        assertEquals(1, reader.u8())
        assertEquals("hello", reader.string())
        assertEquals(2, reader.u8())
        assertEquals(7, reader.i32())
        assertEquals(3, reader.u8())
        assertEquals(9L, reader.i64())
        assertEquals(1, reader.i32())
        assertEquals(42, reader.i32())
        assertEquals("display", reader.string())
        assertEquals("present", reader.string())
        assertEquals(listOf("Int"), reader.stringList())
        assertEquals("Unit", reader.string())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(8, reader.i32())
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), reader.byteArray())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun negativeImportIdIsRejectedBeforeEncoding() {
        val image = minimalImage().copy(hostImports = listOf(CkVmHostImport(-1, "display", "present", listOf("Int"), "Unit")))

        assertFailsWith<IllegalArgumentException> {
            CkVmImageAbi.encode(image)
        }
    }

    private fun minimalImage(): CkVmImage =
        CkVmImage(
            languageVersion = "ckl-1",
            targetAbiVersion = 1,
            entryFunctionIndex = 0,
            functions = listOf(CkVmFunction("main", frameSize = 0, code = emptyList())),
        )

    private fun representativeImage(): CkVmImage =
        CkVmImage(
            languageVersion = "ckl-1",
            targetAbiVersion = 1,
            capabilities = listOf("host-import-ids"),
            constants = listOf(
                CkVmConstant.StringConstant("hello"),
                CkVmConstant.IntConstant(7),
                CkVmConstant.LongConstant(9L),
            ),
            hostImports = listOf(CkVmHostImport(42, "display", "present", listOf("Int"), "Unit")),
            entryFunctionIndex = 0,
            functions = listOf(CkVmFunction("main", frameSize = 8, code = listOf(0x01, 0x02, 0x03))),
        )

    private class TestReader(
        private val bytes: ByteArray,
    ) {
        var offset: Int = 0
            private set

        fun ascii(count: Int): String = bytes.decodeToString(offset, offset + count).also { offset += count }

        fun u8(): Int = bytes[offset++].toInt() and 0xff

        fun u16(): Int = u8() or (u8() shl 8)

        fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

        fun i64(): Long {
            var value = 0L
            repeat(8) { index -> value = value or ((u8().toLong() and 0xffL) shl (index * 8)) }
            return value
        }

        fun string(): String {
            val length = i32()
            val value = bytes.decodeToString(offset, offset + length)
            offset += length
            return value
        }

        fun stringList(): List<String> = List(i32()) { string() }

        fun byteArray(): ByteArray {
            val length = i32()
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return value
        }
    }
}
```

- [ ] **Step 2: Run the Kotlin test and verify RED**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --rerun-tasks
```

Expected: FAIL during Kotlin compilation with unresolved references such as `CkVmImageAbi`, `CkVmImage`, `CkVmConstant`, `CkVmHostImport`, and `CkVmFunction`.

- [ ] **Step 3: Commit the RED test**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt
git commit -m "test: add ckvm image abi kotlin red test"
```

---

### Task 2: Kotlin Image Model and Encoder Implementation

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`

- [ ] **Step 1: Add the Kotlin image model**

Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image

data class CkVmImage(
    val languageVersion: String,
    val targetAbiVersion: Int,
    val capabilities: List<String> = emptyList(),
    val constants: List<CkVmConstant> = emptyList(),
    val hostImports: List<CkVmHostImport> = emptyList(),
    val entryFunctionIndex: Int,
    val functions: List<CkVmFunction>,
)

sealed interface CkVmConstant {
    data class StringConstant(
        val value: String,
    ) : CkVmConstant

    data class IntConstant(
        val value: Int,
    ) : CkVmConstant

    data class LongConstant(
        val value: Long,
    ) : CkVmConstant
}

data class CkVmHostImport(
    val id: Int,
    val moduleName: String,
    val functionName: String,
    val parameterTypes: List<String>,
    val returnType: String,
)

data class CkVmFunction(
    val name: String,
    val frameSize: Int,
    val code: List<Int>,
)
```

- [ ] **Step 2: Add the Kotlin encoder**

Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image

import java.io.ByteArrayOutputStream

object CkVmImageAbi {
    const val VERSION: Int = 1

    object ConstantTags {
        const val STRING = 1
        const val INT = 2
        const val LONG = 3
    }

    fun encode(image: CkVmImage): ByteArray {
        validate(image)
        val out = Writer()
        out.bytes(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()))
        out.u8(VERSION)
        out.string(image.languageVersion)
        out.u16(image.targetAbiVersion)
        out.list(image.capabilities, out::string)
        out.list(image.constants, out::constant)
        out.list(image.hostImports, out::hostImport)
        out.i32(image.entryFunctionIndex)
        out.list(image.functions, out::function)
        return out.toByteArray()
    }

    private fun validate(image: CkVmImage) {
        require(image.targetAbiVersion in 0..0xffff) { "Target ABI version must fit u16." }
        require(image.functions.isNotEmpty()) { "Image must contain at least one function." }
        require(image.entryFunctionIndex in image.functions.indices) { "Entry function index is outside the function table." }
        image.hostImports.forEach { hostImport ->
            require(hostImport.id >= 0) { "Host import id must be non-negative." }
        }
        image.functions.forEach { function ->
            require(function.frameSize >= 0) { "Function frame size must be non-negative." }
            function.code.forEach { byte ->
                require(byte in 0..0xff) { "Code byte must be in 0..255." }
            }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun bytes(value: ByteArray) = out.write(value)

        fun u8(value: Int) = out.write(value and 0xff)

        fun u16(value: Int) {
            u8(value)
            u8(value ushr 8)
        }

        fun i32(value: Int) {
            u8(value)
            u8(value ushr 8)
            u8(value ushr 16)
            u8(value ushr 24)
        }

        fun i64(value: Long) {
            repeat(8) { index -> u8((value ushr (index * 8)).toInt()) }
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            i32(bytes.size)
            bytes(bytes)
        }

        fun byteArray(value: List<Int>) {
            i32(value.size)
            value.forEach(::u8)
        }

        fun <T> list(
            values: List<T>,
            write: (T) -> Unit,
        ) {
            i32(values.size)
            values.forEach(write)
        }

        fun constant(constant: CkVmConstant) {
            when (constant) {
                is CkVmConstant.StringConstant -> {
                    u8(ConstantTags.STRING)
                    string(constant.value)
                }
                is CkVmConstant.IntConstant -> {
                    u8(ConstantTags.INT)
                    i32(constant.value)
                }
                is CkVmConstant.LongConstant -> {
                    u8(ConstantTags.LONG)
                    i64(constant.value)
                }
            }
        }

        fun hostImport(hostImport: CkVmHostImport) {
            i32(hostImport.id)
            string(hostImport.moduleName)
            string(hostImport.functionName)
            list(hostImport.parameterTypes, ::string)
            string(hostImport.returnType)
        }

        fun function(function: CkVmFunction) {
            string(function.name)
            i32(function.frameSize)
            byteArray(function.code)
        }
    }
}
```

- [ ] **Step 3: Run the Kotlin test and verify GREEN**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Commit the Kotlin encoder**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImage.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt
git commit -m "feat: add ckvm image kotlin abi encoder"
```

---

### Task 3: Rust Decoder RED Tests

**Files:**
- Modify: `native/ckl-vm/src/lib.rs`
- Create: `native/ckl-vm/src/image.rs`
- Test: `native/ckl-vm/tests/image_decode.rs`

- [ ] **Step 1: Add the failing Rust decoder test**

Create `native/ckl-vm/tests/image_decode.rs`:

```rust
use ckl_vm::image::{decode_image, Constant, ImageError};

#[test]
fn decodes_representative_image() {
    let bytes = representative_image_bytes();
    let image = decode_image(&bytes).expect("image decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.target_abi_version, 1);
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(
        image.constants,
        vec![Constant::String("hello".to_string()), Constant::Int(7), Constant::Long(9)]
    );
    assert_eq!(image.host_imports.len(), 1);
    assert_eq!(image.host_imports[0].id, 42);
    assert_eq!(image.host_imports[0].module_name, "display");
    assert_eq!(image.host_imports[0].function_name, "present");
    assert_eq!(image.host_imports[0].parameter_types, vec!["Int"]);
    assert_eq!(image.host_imports[0].return_type, "Unit");
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.len(), 1);
    assert_eq!(image.functions[0].name, "main");
    assert_eq!(image.functions[0].frame_size, 8);
    assert_eq!(image.functions[0].code, vec![1, 2, 3]);
}

#[test]
fn rejects_invalid_magic() {
    let mut bytes = representative_image_bytes();
    bytes[0] = b'X';

    assert_eq!(decode_image(&bytes), Err(ImageError::InvalidMagic));
}

#[test]
fn rejects_unknown_version() {
    let mut bytes = representative_image_bytes();
    bytes[4] = 99;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnsupportedVersion(99)));
}

#[test]
fn rejects_unknown_constant_tag() {
    let mut bytes = representative_image_bytes();
    let first_constant_tag_offset = 43;
    bytes[first_constant_tag_offset] = 99;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnknownConstantTag(99)));
}

fn representative_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 1);
    string(&mut out, "host-import-ids");
    list_len(&mut out, 3);
    out.push(1);
    string(&mut out, "hello");
    out.push(2);
    i32(&mut out, 7);
    out.push(3);
    i64(&mut out, 9);
    list_len(&mut out, 1);
    i32(&mut out, 42);
    string(&mut out, "display");
    string(&mut out, "present");
    list_len(&mut out, 1);
    string(&mut out, "Int");
    string(&mut out, "Unit");
    i32(&mut out, 0);
    list_len(&mut out, 1);
    string(&mut out, "main");
    i32(&mut out, 8);
    list_len(&mut out, 3);
    out.extend_from_slice(&[1, 2, 3]);
    out
}

fn list_len(out: &mut Vec<u8>, value: i32) {
    i32(out, value);
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i64(out: &mut Vec<u8>, value: i64) {
    out.extend_from_slice(&value.to_le_bytes());
}
```

- [ ] **Step 2: Run Cargo test and verify RED**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: FAIL during Rust compilation with an unresolved import for `ckl_vm::image`.

- [ ] **Step 3: Commit the RED Rust test**

Run:

```bash
git add native/ckl-vm/tests/image_decode.rs
git commit -m "test: add ckvm image rust decoder red test"
```

---

### Task 4: Rust Decoder Implementation

**Files:**
- Modify: `native/ckl-vm/src/lib.rs`
- Create: `native/ckl-vm/src/image.rs`
- Test: `native/ckl-vm/tests/image_decode.rs`

- [ ] **Step 1: Export the Rust image module**

Modify `native/ckl-vm/src/lib.rs` to include the new module:

```rust
pub mod abi;
pub mod image;
pub mod jni;
pub mod runner;
pub mod signal;
pub mod value;
pub mod vm;
```

- [ ] **Step 2: Add the Rust image decoder**

Create `native/ckl-vm/src/image.rs`:

```rust
use thiserror::Error;

pub const VERSION: u8 = 1;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ImageError {
    #[error("invalid image magic")]
    InvalidMagic,
    #[error("unsupported image version {0}")]
    UnsupportedVersion(u8),
    #[error("unexpected end of image")]
    UnexpectedEnd,
    #[error("invalid utf-8 string")]
    InvalidUtf8,
    #[error("negative length {0}")]
    NegativeLength(i32),
    #[error("unknown constant tag {0}")]
    UnknownConstantTag(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub language_version: String,
    pub target_abi_version: u16,
    pub capabilities: Vec<String>,
    pub constants: Vec<Constant>,
    pub host_imports: Vec<HostImport>,
    pub entry_function_index: i32,
    pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Constant {
    String(String),
    Int(i32),
    Long(i64),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostImport {
    pub id: i32,
    pub module_name: String,
    pub function_name: String,
    pub parameter_types: Vec<String>,
    pub return_type: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
    pub name: String,
    pub frame_size: i32,
    pub code: Vec<u8>,
}

pub fn decode_image(bytes: &[u8]) -> Result<Image, ImageError> {
    let mut reader = Reader { bytes, offset: 0 };
    if reader.take(4)? != b"CKIM" {
        return Err(ImageError::InvalidMagic);
    }
    let version = reader.u8()?;
    if version != VERSION {
        return Err(ImageError::UnsupportedVersion(version));
    }
    let language_version = reader.string()?;
    let target_abi_version = reader.u16()?;
    let capabilities = reader.list(read_string)?;
    let constants = reader.list(read_constant)?;
    let host_imports = reader.list(read_host_import)?;
    let entry_function_index = reader.i32()?;
    let functions = reader.list(read_function)?;
    Ok(Image { language_version, target_abi_version, capabilities, constants, host_imports, entry_function_index, functions })
}

fn read_string(reader: &mut Reader<'_>) -> Result<String, ImageError> {
    reader.string()
}

fn read_constant(reader: &mut Reader<'_>) -> Result<Constant, ImageError> {
    match reader.u8()? {
        1 => Ok(Constant::String(reader.string()?)),
        2 => Ok(Constant::Int(reader.i32()?)),
        3 => Ok(Constant::Long(reader.i64()?)),
        other => Err(ImageError::UnknownConstantTag(other)),
    }
}

fn read_host_import(reader: &mut Reader<'_>) -> Result<HostImport, ImageError> {
    Ok(HostImport {
        id: reader.i32()?,
        module_name: reader.string()?,
        function_name: reader.string()?,
        parameter_types: reader.list(read_string)?,
        return_type: reader.string()?,
    })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, ImageError> {
    Ok(Function {
        name: reader.string()?,
        frame_size: reader.i32()?,
        code: reader.byte_vec()?,
    })
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], ImageError> {
        let end = self.offset.checked_add(count).ok_or(ImageError::UnexpectedEnd)?;
        let slice = self.bytes.get(self.offset..end).ok_or(ImageError::UnexpectedEnd)?;
        self.offset = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8, ImageError> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, ImageError> {
        let mut bytes = [0u8; 2];
        bytes.copy_from_slice(self.take(2)?);
        Ok(u16::from_le_bytes(bytes))
    }

    fn i32(&mut self) -> Result<i32, ImageError> {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, ImageError> {
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, ImageError> {
        let length = self.length()?;
        String::from_utf8(self.take(length)?.to_vec()).map_err(|_| ImageError::InvalidUtf8)
    }

    fn byte_vec(&mut self) -> Result<Vec<u8>, ImageError> {
        let length = self.length()?;
        Ok(self.take(length)?.to_vec())
    }

    fn list<T>(&mut self, read: fn(&mut Reader<'a>) -> Result<T, ImageError>) -> Result<Vec<T>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(read(self)?);
        }
        Ok(values)
    }

    fn length(&mut self) -> Result<usize, ImageError> {
        let length = self.i32()?;
        if length < 0 {
            return Err(ImageError::NegativeLength(length));
        }
        Ok(length as usize)
    }
}
```

- [ ] **Step 3: Run Cargo test and verify GREEN**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: PASS.

- [ ] **Step 4: Commit the Rust decoder**

Run:

```bash
git add native/ckl-vm/src/lib.rs native/ckl-vm/src/image.rs native/ckl-vm/tests/image_decode.rs
git commit -m "feat: add ckvm image rust decoder"
```

---

### Task 5: Cross-Language Golden Fixture

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt`
- Create: `native/ckl-vm/tests/fixtures/representative.ckim`
- Modify: `native/ckl-vm/tests/image_decode.rs`

- [ ] **Step 1: Add a Kotlin test that writes a golden fixture when requested**

Modify `CkVmImageAbiTest.kt` by adding this test method inside `CkVmImageAbiTest`:

```kotlin
@Test
fun writesGoldenFixtureWhenPathIsProvided() {
    val path = System.getProperty("ckl.image.golden.path")?.takeIf(String::isNotBlank) ?: return

    java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
    java.nio.file.Files.write(java.nio.file.Path.of(path), CkVmImageAbi.encode(representativeImage()))
}
```

Change `representativeImage()` from `private` to package-visible by removing the `private` modifier if the compiler complains about test method access inside the same class. Keep it inside the test class.

- [ ] **Step 2: Generate the fixture from Kotlin**

Run:

```bash
JAVA_TOOL_OPTIONS="-Dckl.image.golden.path=$PWD/native/ckl-vm/tests/fixtures/representative.ckim" ./gradlew :compiler:test --tests '*CkVmImageAbiTest.writesGoldenFixtureWhenPathIsProvided' --rerun-tasks
```

Expected: PASS and file `native/ckl-vm/tests/fixtures/representative.ckim` exists.

- [ ] **Step 3: Add a Rust test that decodes the Kotlin-generated fixture**

Add this test to `native/ckl-vm/tests/image_decode.rs`:

```rust
#[test]
fn decodes_kotlin_generated_fixture() {
    let bytes = include_bytes!("fixtures/representative.ckim");
    let image = decode_image(bytes).expect("fixture decodes");

    assert_eq!(image.language_version, "ckl-1");
    assert_eq!(image.target_abi_version, 1);
    assert_eq!(image.capabilities, vec!["host-import-ids"]);
    assert_eq!(image.functions[0].code, vec![1, 2, 3]);
}
```

- [ ] **Step 4: Run Kotlin and Rust tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 5: Commit the cross-language fixture**

Run:

```bash
git add modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbiTest.kt native/ckl-vm/tests/fixtures/representative.ckim native/ckl-vm/tests/image_decode.rs
git commit -m "test: add ckvm image cross-language fixture"
```

---

### Task 6: Final Verification

**Files:**
- Verify all files changed by this plan.

- [ ] **Step 1: Run focused verification**

Run:

```bash
./gradlew :compiler:test --tests '*CkVmImageAbiTest' --rerun-tasks
cargo test --manifest-path native/ckl-vm/Cargo.toml --test image_decode -- --nocapture
```

Expected: both commands PASS.

- [ ] **Step 2: Run existing ABI tests to avoid regressions**

Run:

```bash
./gradlew :compiler:test --tests '*BytecodeAbiTest' --tests '*CkVmImageAbiTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Run whitespace verification**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Inspect final status**

Run:

```bash
git status --short
```

Expected: no uncommitted changes if every task commit was made. If the executor intentionally avoids commits, expected output lists only the files changed by this plan.

---

## Self-Review Notes

- This plan implements only the `CkVmImage` ABI skeleton from the Rust-native VM architecture spec.
- It does not implement Rust VM execution, Kotlin frontend lowering, typed IR, host import registry generation, JNI image runner methods, memory allocator changes, or IDE query APIs.
- Those items are intentionally left for later slice-specific plans after this ABI skeleton is validated.