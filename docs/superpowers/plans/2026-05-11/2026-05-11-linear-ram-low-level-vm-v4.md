# Linear RAM Low-Level VM v4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first executable `CKIM v4` low-level VM slice with `u32` linear RAM, primitive registers, no managed heap, no old image fallback, and benchmark visibility.

**Architecture:** Add v4 next to the current v3 files only as a temporary development seam, not as a runtime fallback. The v4 image model has primitive register counts, fixed linear-memory metadata, memory sections, and low-level instructions. The Rust v4 runner executes compute-only programs directly against primitive register banks and `Vec<u8>` linear RAM; later tasks can make v4 the only production image path and delete v3.

**Tech Stack:** Kotlin/JVM compiler tests and image ABI encoder, Rust `native/ckl-vm`, JNI benchmark bindings, Gradle profiling tasks, Kotlin/JUnit tests, Rust unit/integration tests.

---

## Scope Rules

- This plan implements the first v4 executable slice only.
- Do not add GC, managed heap, dynamic records, or `VmValue` execution storage.
- Do not preserve old image ABI compatibility inside the v4 decoder.
- Do not route v4 unsupported behavior to Kotlin execution.
- Keep v3 code untouched except where tests/benchmarks need to compare v3 and v4 during migration.
- Commit after each task that compiles and has passing focused tests.

## Target File Structure

- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImage.kt`
  - Kotlin v4 model: constants, memory layout, functions, primitive registers, low-level instructions.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbi.kt`
  - Kotlin v4 binary encoder with `CKIM` version `4`.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt`
  - ABI layout tests and golden fixture writer.
- Create `native/ckl-vm/src/low_image.rs`
  - Rust v4 decoder. Rejects all versions except `4`.
- Create `native/ckl-vm/src/low_image_runner.rs`
  - Rust v4 compute runner with primitive registers and fixed `Vec<u8>` RAM.
- Modify `native/ckl-vm/src/lib.rs`
  - Export `low_image` and `low_image_runner`.
- Create `native/ckl-vm/tests/low_image_decode.rs`
  - Rust decoder tests against hand-written bytes and Kotlin-generated fixture.
- Create `native/ckl-vm/tests/low_image_runner.rs`
  - Rust runner tests for arithmetic, memory load/store, bounds errors, and simple loops.
- Create `native/ckl-vm/tests/fixtures/low-representative.ckim`
  - Kotlin-generated v4 fixture.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsJniTest.kt`
  - Add v4 JNI tests later only after JNI is exposed. Do not modify in the first decoder-only task.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkRunner.kt`
  - Add v4 benchmark line after the Rust runner has JNI exposure.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/ComputeVmBenchmarkReport.kt`
  - Add v4 report columns after the runner is callable from Kotlin.

## Binary Layout For First Slice

The first `CKIM v4` image layout is:

```text
magic: "CKIM"
version: u8 = 4
language_version: string
memory_size: u32
rodata: bytes
data: bytes
bss_size: u32
entry_function_index: i32
functions: list<Function>
```

Function layout:

```text
name: string
i32_register_count: u16
i64_register_count: u16
addr_register_count: u16
bool_register_count: u16
parameter_registers: list<TypedRegister>
instructions: list<Instruction>
```

Typed register layout:

```text
tag 1 = I32
tag 2 = I64
tag 3 = Addr
tag 4 = Bool
index: u16
```

Instruction tags for the first slice:

```text
1  I32Const dst:i32, value:i32
2  I64Const dst:i64, value:i64
3  AddrConst dst:addr, value:u32
4  I32Move dst:i32, src:i32
5  AddrMove dst:addr, src:addr
6  I32Add dst:i32, lhs:i32, rhs:i32
7  I32Sub dst:i32, lhs:i32, rhs:i32
8  I32Mul dst:i32, lhs:i32, rhs:i32
9  I32Div dst:i32, lhs:i32, rhs:i32
10 I32BitXor dst:i32, lhs:i32, rhs:i32
11 I32Shl dst:i32, lhs:i32, rhs:i32
12 I32Shr dst:i32, lhs:i32, rhs:i32
13 I32Lt dst:bool, lhs:i32, rhs:i32
14 Load32 dst:i32, addr:addr
15 Store32 addr:addr, src:i32
16 AddrAdd dst:addr, base:addr, offset:i32
17 Jump target:i32
18 JumpIfFalse cond:bool, target:i32
19 CallStatic return:optional<TypedRegister>, function_index:i32, args:list<TypedRegister>
20 Return src:TypedRegister
21 ReturnUnit
```

All integer arithmetic uses wrapping semantics except `I32Div`, which returns a VM error on division by zero.

## Task 1: Kotlin v4 Image Model

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImage.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt`

- [ ] **Step 1: Write the failing model test**

Create `CkLowVmImageAbiTest.kt` with this first test:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image.low

import kotlin.test.Test
import kotlin.test.assertEquals

class CkLowVmImageAbiTest {
    @Test
    fun lowImageModelDescribesLinearMemoryAndPrimitiveRegisters() {
        val image =
            CkLowVmImage(
                languageVersion = "ckl-low-1",
                memorySize = 4096u,
                rodata = byteArrayOf(1, 2, 3),
                data = byteArrayOf(4, 5),
                bssSize = 16u,
                entryFunctionIndex = 0,
                functions =
                    listOf(
                        CkLowVmFunction(
                            name = "main",
                            i32RegisterCount = 2,
                            i64RegisterCount = 0,
                            addrRegisterCount = 1,
                            boolRegisterCount = 0,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    CkLowVmInstruction.AddrConst(dst = 0, value = 128u),
                                    CkLowVmInstruction.I32Const(dst = 0, value = 7),
                                    CkLowVmInstruction.Store32(addr = 0, src = 0),
                                    CkLowVmInstruction.Load32(dst = 1, addr = 0),
                                    CkLowVmInstruction.Return(CkLowVmRegister.I32(1)),
                                ),
                        ),
                    ),
            )

        assertEquals(4096u, image.memorySize)
        assertEquals(1, image.functions.single().addrRegisterCount)
        assertEquals(CkLowVmRegister.I32(1), (image.functions.single().instructions.last() as CkLowVmInstruction.Return).src)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :compiler:test --tests '*CkLowVmImageAbiTest.lowImageModelDescribesLinearMemoryAndPrimitiveRegisters'
```

Expected: FAIL during test compilation because `CkLowVmImage` does not exist.

- [ ] **Step 3: Add the minimal v4 model**

Create `CkLowVmImage.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image.low

data class CkLowVmImage(
    val languageVersion: String,
    val memorySize: UInt,
    val rodata: ByteArray = byteArrayOf(),
    val data: ByteArray = byteArrayOf(),
    val bssSize: UInt = 0u,
    val entryFunctionIndex: Int,
    val functions: List<CkLowVmFunction>,
) {
    override fun equals(other: Any?): Boolean =
        other is CkLowVmImage &&
            languageVersion == other.languageVersion &&
            memorySize == other.memorySize &&
            rodata.contentEquals(other.rodata) &&
            data.contentEquals(other.data) &&
            bssSize == other.bssSize &&
            entryFunctionIndex == other.entryFunctionIndex &&
            functions == other.functions

    override fun hashCode(): Int {
        var result = languageVersion.hashCode()
        result = 31 * result + memorySize.hashCode()
        result = 31 * result + rodata.contentHashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + bssSize.hashCode()
        result = 31 * result + entryFunctionIndex
        result = 31 * result + functions.hashCode()
        return result
    }
}

data class CkLowVmFunction(
    val name: String,
    val i32RegisterCount: Int,
    val i64RegisterCount: Int,
    val addrRegisterCount: Int,
    val boolRegisterCount: Int,
    val parameters: List<CkLowVmRegister>,
    val instructions: List<CkLowVmInstruction>,
)

sealed interface CkLowVmRegister {
    val index: Int

    data class I32(override val index: Int) : CkLowVmRegister
    data class I64(override val index: Int) : CkLowVmRegister
    data class Addr(override val index: Int) : CkLowVmRegister
    data class Bool(override val index: Int) : CkLowVmRegister
}

sealed interface CkLowVmInstruction {
    data class I32Const(val dst: Int, val value: Int) : CkLowVmInstruction
    data class I64Const(val dst: Int, val value: Long) : CkLowVmInstruction
    data class AddrConst(val dst: Int, val value: UInt) : CkLowVmInstruction
    data class I32Move(val dst: Int, val src: Int) : CkLowVmInstruction
    data class AddrMove(val dst: Int, val src: Int) : CkLowVmInstruction
    data class I32Add(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Sub(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Mul(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Div(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32BitXor(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Shl(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Shr(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class I32Lt(val dst: Int, val lhs: Int, val rhs: Int) : CkLowVmInstruction
    data class Load32(val dst: Int, val addr: Int) : CkLowVmInstruction
    data class Store32(val addr: Int, val src: Int) : CkLowVmInstruction
    data class AddrAdd(val dst: Int, val base: Int, val offset: Int) : CkLowVmInstruction
    data class Jump(val target: Int) : CkLowVmInstruction
    data class JumpIfFalse(val cond: Int, val target: Int) : CkLowVmInstruction
    data class CallStatic(
        val returnRegister: CkLowVmRegister?,
        val functionIndex: Int,
        val arguments: List<CkLowVmRegister>,
    ) : CkLowVmInstruction
    data class Return(val src: CkLowVmRegister) : CkLowVmInstruction
    data object ReturnUnit : CkLowVmInstruction
}
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
./gradlew :compiler:test --tests '*CkLowVmImageAbiTest.lowImageModelDescribesLinearMemoryAndPrimitiveRegisters'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImage.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt
git commit -m "Add low-level VM image model"
```

## Task 2: Kotlin v4 ABI Encoder

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbi.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt`

- [ ] **Step 1: Add the failing ABI layout test**

Append this test to `CkLowVmImageAbiTest`:

```kotlin
@Test
fun lowImageAbiEncodesVersionFourAndLinearMemoryLayout() {
    val bytes =
        CkLowVmImageAbi.encode(
            CkLowVmImage(
                languageVersion = "ckl-low-1",
                memorySize = 4096u,
                rodata = byteArrayOf(1, 2, 3),
                data = byteArrayOf(4, 5),
                bssSize = 16u,
                entryFunctionIndex = 0,
                functions =
                    listOf(
                        CkLowVmFunction(
                            name = "main",
                            i32RegisterCount = 2,
                            i64RegisterCount = 0,
                            addrRegisterCount = 1,
                            boolRegisterCount = 0,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    CkLowVmInstruction.AddrConst(0, 128u),
                                    CkLowVmInstruction.I32Const(0, 7),
                                    CkLowVmInstruction.Store32(0, 0),
                                    CkLowVmInstruction.Load32(1, 0),
                                    CkLowVmInstruction.Return(CkLowVmRegister.I32(1)),
                                ),
                        ),
                    ),
            ),
        )
    val reader = LowTestReader(bytes)

    assertEquals("CKIM", reader.ascii(4))
    assertEquals(4, reader.u8())
    assertEquals("ckl-low-1", reader.string())
    assertEquals(4096u, reader.u32())
    assertEquals(listOf(1, 2, 3), reader.bytes().toList())
    assertEquals(listOf(4, 5), reader.bytes().toList())
    assertEquals(16u, reader.u32())
    assertEquals(0, reader.i32())
    assertEquals(1, reader.i32())
    assertEquals("main", reader.string())
    assertEquals(2, reader.u16())
    assertEquals(0, reader.u16())
    assertEquals(1, reader.u16())
    assertEquals(0, reader.u16())
    assertEquals(emptyList(), reader.registerList())
    assertEquals(5, reader.i32())
    assertEquals(CkLowVmImageAbi.InstructionTags.ADDR_CONST, reader.u8())
    assertEquals(0, reader.u16())
    assertEquals(128u, reader.u32())
    assertEquals(CkLowVmImageAbi.InstructionTags.I32_CONST, reader.u8())
    assertEquals(0, reader.u16())
    assertEquals(7, reader.i32())
    assertEquals(CkLowVmImageAbi.InstructionTags.STORE32, reader.u8())
    assertEquals(0, reader.u16())
    assertEquals(0, reader.u16())
    assertEquals(CkLowVmImageAbi.InstructionTags.LOAD32, reader.u8())
    assertEquals(1, reader.u16())
    assertEquals(0, reader.u16())
    assertEquals(CkLowVmImageAbi.InstructionTags.RETURN, reader.u8())
    assertEquals(CkLowVmRegister.I32(1), reader.register())
    assertEquals(bytes.size, reader.offset)
}
```

Add this helper inside the test class:

```kotlin
private class LowTestReader(
    private val bytes: ByteArray,
) {
    var offset: Int = 0
        private set

    fun ascii(count: Int): String = bytes.decodeToString(offset, offset + count).also { offset += count }

    fun u8(): Int = bytes[offset++].toInt() and 0xff

    fun u16(): Int = u8() or (u8() shl 8)

    fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

    fun u32(): UInt = i32().toUInt()

    fun string(): String {
        val length = i32()
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }

    fun bytes(): ByteArray {
        val length = i32()
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun register(): CkLowVmRegister =
        when (val tag = u8()) {
            CkLowVmImageAbi.RegisterTags.I32 -> CkLowVmRegister.I32(u16())
            CkLowVmImageAbi.RegisterTags.I64 -> CkLowVmRegister.I64(u16())
            CkLowVmImageAbi.RegisterTags.ADDR -> CkLowVmRegister.Addr(u16())
            CkLowVmImageAbi.RegisterTags.BOOL -> CkLowVmRegister.Bool(u16())
            else -> error("Unexpected register tag $tag")
        }

    fun registerList(): List<CkLowVmRegister> = List(i32()) { register() }
}
```

- [ ] **Step 2: Run the failing ABI test**

Run:

```bash
./gradlew :compiler:test --tests '*CkLowVmImageAbiTest.lowImageAbiEncodesVersionFourAndLinearMemoryLayout'
```

Expected: FAIL during test compilation because `CkLowVmImageAbi` does not exist.

- [ ] **Step 3: Implement the encoder**

Create `CkLowVmImageAbi.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.image.low

object CkLowVmImageAbi {
    const val VERSION: Int = 4

    object RegisterTags {
        const val I32 = 1
        const val I64 = 2
        const val ADDR = 3
        const val BOOL = 4
    }

    object InstructionTags {
        const val I32_CONST = 1
        const val I64_CONST = 2
        const val ADDR_CONST = 3
        const val I32_MOVE = 4
        const val ADDR_MOVE = 5
        const val I32_ADD = 6
        const val I32_SUB = 7
        const val I32_MUL = 8
        const val I32_DIV = 9
        const val I32_BIT_XOR = 10
        const val I32_SHL = 11
        const val I32_SHR = 12
        const val I32_LT = 13
        const val LOAD32 = 14
        const val STORE32 = 15
        const val ADDR_ADD = 16
        const val JUMP = 17
        const val JUMP_IF_FALSE = 18
        const val CALL_STATIC = 19
        const val RETURN = 20
        const val RETURN_UNIT = 21
    }

    fun encode(image: CkLowVmImage): ByteArray {
        require(image.memorySize > 0u) { "low VM memorySize must be positive" }
        require(image.rodata.size.toUInt() + image.data.size.toUInt() + image.bssSize <= image.memorySize) {
            "low VM memory sections must fit inside memorySize"
        }
        require(image.entryFunctionIndex in image.functions.indices) {
            "entryFunctionIndex ${image.entryFunctionIndex} is outside function table"
        }
        val writer = Writer()
        writer.ascii("CKIM")
        writer.u8(VERSION)
        writer.string(image.languageVersion)
        writer.u32(image.memorySize)
        writer.bytes(image.rodata)
        writer.bytes(image.data)
        writer.u32(image.bssSize)
        writer.i32(image.entryFunctionIndex)
        writer.list(image.functions) { function(it) }
        return writer.toByteArray()
    }

    private fun Writer.function(function: CkLowVmFunction) {
        require(function.i32RegisterCount in 0..UShort.MAX_VALUE.toInt())
        require(function.i64RegisterCount in 0..UShort.MAX_VALUE.toInt())
        require(function.addrRegisterCount in 0..UShort.MAX_VALUE.toInt())
        require(function.boolRegisterCount in 0..UShort.MAX_VALUE.toInt())
        string(function.name)
        u16(function.i32RegisterCount)
        u16(function.i64RegisterCount)
        u16(function.addrRegisterCount)
        u16(function.boolRegisterCount)
        list(function.parameters) { register(it) }
        list(function.instructions) { instruction(it) }
    }

    private fun Writer.register(register: CkLowVmRegister) {
        when (register) {
            is CkLowVmRegister.I32 -> {
                u8(RegisterTags.I32)
                u16(register.index)
            }
            is CkLowVmRegister.I64 -> {
                u8(RegisterTags.I64)
                u16(register.index)
            }
            is CkLowVmRegister.Addr -> {
                u8(RegisterTags.ADDR)
                u16(register.index)
            }
            is CkLowVmRegister.Bool -> {
                u8(RegisterTags.BOOL)
                u16(register.index)
            }
        }
    }

    private fun Writer.optionalRegister(register: CkLowVmRegister?) {
        if (register == null) {
            u8(0)
        } else {
            u8(1)
            register(register)
        }
    }

    private fun Writer.instruction(instruction: CkLowVmInstruction) {
        when (instruction) {
            is CkLowVmInstruction.I32Const -> {
                u8(InstructionTags.I32_CONST)
                u16(instruction.dst)
                i32(instruction.value)
            }
            is CkLowVmInstruction.I64Const -> {
                u8(InstructionTags.I64_CONST)
                u16(instruction.dst)
                i64(instruction.value)
            }
            is CkLowVmInstruction.AddrConst -> {
                u8(InstructionTags.ADDR_CONST)
                u16(instruction.dst)
                u32(instruction.value)
            }
            is CkLowVmInstruction.I32Move -> typedMove(InstructionTags.I32_MOVE, instruction.dst, instruction.src)
            is CkLowVmInstruction.AddrMove -> typedMove(InstructionTags.ADDR_MOVE, instruction.dst, instruction.src)
            is CkLowVmInstruction.I32Add -> typedBinary(InstructionTags.I32_ADD, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Sub -> typedBinary(InstructionTags.I32_SUB, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Mul -> typedBinary(InstructionTags.I32_MUL, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Div -> typedBinary(InstructionTags.I32_DIV, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32BitXor -> typedBinary(InstructionTags.I32_BIT_XOR, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Shl -> typedBinary(InstructionTags.I32_SHL, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Shr -> typedBinary(InstructionTags.I32_SHR, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.I32Lt -> typedBinary(InstructionTags.I32_LT, instruction.dst, instruction.lhs, instruction.rhs)
            is CkLowVmInstruction.Load32 -> typedMove(InstructionTags.LOAD32, instruction.dst, instruction.addr)
            is CkLowVmInstruction.Store32 -> typedMove(InstructionTags.STORE32, instruction.addr, instruction.src)
            is CkLowVmInstruction.AddrAdd -> typedBinary(InstructionTags.ADDR_ADD, instruction.dst, instruction.base, instruction.offset)
            is CkLowVmInstruction.Jump -> {
                u8(InstructionTags.JUMP)
                i32(instruction.target)
            }
            is CkLowVmInstruction.JumpIfFalse -> {
                u8(InstructionTags.JUMP_IF_FALSE)
                u16(instruction.cond)
                i32(instruction.target)
            }
            is CkLowVmInstruction.CallStatic -> {
                u8(InstructionTags.CALL_STATIC)
                optionalRegister(instruction.returnRegister)
                i32(instruction.functionIndex)
                list(instruction.arguments) { register(it) }
            }
            is CkLowVmInstruction.Return -> {
                u8(InstructionTags.RETURN)
                register(instruction.src)
            }
            CkLowVmInstruction.ReturnUnit -> u8(InstructionTags.RETURN_UNIT)
        }
    }

    private fun Writer.typedMove(tag: Int, dst: Int, src: Int) {
        u8(tag)
        u16(dst)
        u16(src)
    }

    private fun Writer.typedBinary(tag: Int, dst: Int, lhs: Int, rhs: Int) {
        u8(tag)
        u16(dst)
        u16(lhs)
        u16(rhs)
    }

    private class Writer {
        private val bytes = mutableListOf<Byte>()

        fun toByteArray(): ByteArray = bytes.toByteArray()

        fun ascii(value: String) {
            bytes.addAll(value.encodeToByteArray().map { it })
        }

        fun u8(value: Int) {
            require(value in 0..0xff)
            bytes.add(value.toByte())
        }

        fun u16(value: Int) {
            require(value in 0..UShort.MAX_VALUE.toInt())
            bytes.add((value and 0xff).toByte())
            bytes.add(((value ushr 8) and 0xff).toByte())
        }

        fun i32(value: Int) {
            bytes.add((value and 0xff).toByte())
            bytes.add(((value ushr 8) and 0xff).toByte())
            bytes.add(((value ushr 16) and 0xff).toByte())
            bytes.add(((value ushr 24) and 0xff).toByte())
        }

        fun u32(value: UInt) = i32(value.toInt())

        fun i64(value: Long) {
            repeat(8) { index ->
                bytes.add(((value ushr (index * 8)) and 0xff).toByte())
            }
        }

        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            i32(encoded.size)
            bytes.addAll(encoded.map { it })
        }

        fun bytes(value: ByteArray) {
            i32(value.size)
            bytes.addAll(value.map { it })
        }

        fun <T> list(values: List<T>, write: Writer.(T) -> Unit) {
            i32(values.size)
            values.forEach { write(it) }
        }
    }
}
```

- [ ] **Step 4: Run ABI tests**

Run:

```bash
./gradlew :compiler:test --tests '*CkLowVmImageAbiTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbi.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt
git commit -m "Encode low-level VM image ABI"
```

## Task 3: Rust v4 Decoder

**Files:**
- Create: `native/ckl-vm/src/low_image.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Create: `native/ckl-vm/tests/low_image_decode.rs`

- [ ] **Step 1: Add failing Rust decoder test**

Create `native/ckl-vm/tests/low_image_decode.rs`:

```rust
use ckl_vm::low_image::{decode_image, ImageError, Instruction, Register};

#[test]
fn decodes_low_level_image_with_linear_memory_layout() {
    let image = decode_image(&representative_image_bytes()).expect("low image decodes");

    assert_eq!(image.language_version, "ckl-low-1");
    assert_eq!(image.memory_size, 4096);
    assert_eq!(image.rodata, vec![1, 2, 3]);
    assert_eq!(image.data, vec![4, 5]);
    assert_eq!(image.bss_size, 16);
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(image.functions.len(), 1);
    assert_eq!(image.functions[0].addr_register_count, 1);
    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 0, value: 7 },
            Instruction::Store32 { addr: 0, src: 0 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::Return { src: Register::I32(1) },
        ],
    );
}

#[test]
fn rejects_legacy_image_versions() {
    let mut bytes = representative_image_bytes();
    bytes[4] = 3;

    assert_eq!(decode_image(&bytes), Err(ImageError::UnsupportedVersion(3)));
}

fn representative_image_bytes() -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(4);
    string(&mut out, "ckl-low-1");
    u32(&mut out, 4096);
    bytes(&mut out, &[1, 2, 3]);
    bytes(&mut out, &[4, 5]);
    u32(&mut out, 16);
    i32(&mut out, 0);
    i32(&mut out, 1);
    string(&mut out, "main");
    u16(&mut out, 2);
    u16(&mut out, 0);
    u16(&mut out, 1);
    u16(&mut out, 0);
    i32(&mut out, 0);
    i32(&mut out, 5);
    out.push(3);
    u16(&mut out, 0);
    u32(&mut out, 128);
    out.push(1);
    u16(&mut out, 0);
    i32(&mut out, 7);
    out.push(15);
    u16(&mut out, 0);
    u16(&mut out, 0);
    out.push(14);
    u16(&mut out, 1);
    u16(&mut out, 0);
    out.push(20);
    register(&mut out, Register::I32(1));
    out
}

fn register(out: &mut Vec<u8>, register: Register) {
    match register {
        Register::I32(index) => {
            out.push(1);
            u16(out, index);
        }
        Register::I64(index) => {
            out.push(2);
            u16(out, index);
        }
        Register::Addr(index) => {
            out.push(3);
            u16(out, index);
        }
        Register::Bool(index) => {
            out.push(4);
            u16(out, index);
        }
    }
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn bytes(out: &mut Vec<u8>, value: &[u8]) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value);
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}
```

- [ ] **Step 2: Run the failing Rust test**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm --test low_image_decode
```

Expected: FAIL during compilation because `ckl_vm::low_image` is missing.

- [ ] **Step 3: Implement `low_image.rs` and export it**

Add `pub mod low_image;` to `native/ckl-vm/src/lib.rs`.

Create `native/ckl-vm/src/low_image.rs` with:

```rust
use thiserror::Error;

pub const VERSION: u8 = 4;

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
    #[error("negative {name} index {value}")]
    NegativeIndex { name: &'static str, value: i32 },
    #[error("unknown register tag {0}")]
    UnknownRegisterTag(u8),
    #[error("unknown instruction tag {0}")]
    UnknownInstructionTag(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub language_version: String,
    pub memory_size: u32,
    pub rodata: Vec<u8>,
    pub data: Vec<u8>,
    pub bss_size: u32,
    pub entry_function_index: usize,
    pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
    pub name: String,
    pub i32_register_count: usize,
    pub i64_register_count: usize,
    pub addr_register_count: usize,
    pub bool_register_count: usize,
    pub parameters: Vec<Register>,
    pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Register {
    I32(u16),
    I64(u16),
    Addr(u16),
    Bool(u16),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
    I32Const { dst: u16, value: i32 },
    I64Const { dst: u16, value: i64 },
    AddrConst { dst: u16, value: u32 },
    I32Move { dst: u16, src: u16 },
    AddrMove { dst: u16, src: u16 },
    I32Add { dst: u16, lhs: u16, rhs: u16 },
    I32Sub { dst: u16, lhs: u16, rhs: u16 },
    I32Mul { dst: u16, lhs: u16, rhs: u16 },
    I32Div { dst: u16, lhs: u16, rhs: u16 },
    I32BitXor { dst: u16, lhs: u16, rhs: u16 },
    I32Shl { dst: u16, lhs: u16, rhs: u16 },
    I32Shr { dst: u16, lhs: u16, rhs: u16 },
    I32Lt { dst: u16, lhs: u16, rhs: u16 },
    Load32 { dst: u16, addr: u16 },
    Store32 { addr: u16, src: u16 },
    AddrAdd { dst: u16, base: u16, offset: u16 },
    Jump { target: usize },
    JumpIfFalse { cond: u16, target: usize },
    CallStatic { return_register: Option<Register>, function_index: usize, arguments: Vec<Register> },
    Return { src: Register },
    ReturnUnit,
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
    Ok(Image {
        language_version: reader.string()?,
        memory_size: reader.u32()?,
        rodata: reader.bytes()?,
        data: reader.bytes()?,
        bss_size: reader.u32()?,
        entry_function_index: reader.index("entry function")?,
        functions: reader.list(read_function)?,
    })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, ImageError> {
    Ok(Function {
        name: reader.string()?,
        i32_register_count: usize::from(reader.u16()?),
        i64_register_count: usize::from(reader.u16()?),
        addr_register_count: usize::from(reader.u16()?),
        bool_register_count: usize::from(reader.u16()?),
        parameters: reader.register_list()?,
        instructions: reader.list(read_instruction)?,
    })
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, ImageError> {
    match reader.u8()? {
        1 => Ok(Instruction::I32Const { dst: reader.u16()?, value: reader.i32()? }),
        2 => Ok(Instruction::I64Const { dst: reader.u16()?, value: reader.i64()? }),
        3 => Ok(Instruction::AddrConst { dst: reader.u16()?, value: reader.u32()? }),
        4 => read_move(reader, Instruction::I32Move),
        5 => read_move(reader, Instruction::AddrMove),
        6 => read_binary(reader, Instruction::I32Add),
        7 => read_binary(reader, Instruction::I32Sub),
        8 => read_binary(reader, Instruction::I32Mul),
        9 => read_binary(reader, Instruction::I32Div),
        10 => read_binary(reader, Instruction::I32BitXor),
        11 => read_binary(reader, Instruction::I32Shl),
        12 => read_binary(reader, Instruction::I32Shr),
        13 => read_binary(reader, Instruction::I32Lt),
        14 => read_move(reader, Instruction::Load32),
        15 => read_move(reader, Instruction::Store32),
        16 => read_binary(reader, Instruction::AddrAdd),
        17 => Ok(Instruction::Jump { target: reader.index("jump target")? }),
        18 => Ok(Instruction::JumpIfFalse { cond: reader.u16()?, target: reader.index("jump target")? }),
        19 => Ok(Instruction::CallStatic {
            return_register: reader.optional_register()?,
            function_index: reader.index("function")?,
            arguments: reader.register_list()?,
        }),
        20 => Ok(Instruction::Return { src: reader.register()? }),
        21 => Ok(Instruction::ReturnUnit),
        other => Err(ImageError::UnknownInstructionTag(other)),
    }
}

fn read_move(reader: &mut Reader<'_>, create: fn(u16, u16) -> Instruction) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?))
}

fn read_binary(reader: &mut Reader<'_>, create: fn(u16, u16, u16) -> Instruction) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?, reader.u16()?))
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
        let mut bytes = [0_u8; 2];
        bytes.copy_from_slice(self.take(2)?);
        Ok(u16::from_le_bytes(bytes))
    }

    fn i32(&mut self) -> Result<i32, ImageError> {
        let mut bytes = [0_u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn u32(&mut self) -> Result<u32, ImageError> {
        let mut bytes = [0_u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(u32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, ImageError> {
        let mut bytes = [0_u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, ImageError> {
        String::from_utf8(self.bytes()?).map_err(|_| ImageError::InvalidUtf8)
    }

    fn bytes(&mut self) -> Result<Vec<u8>, ImageError> {
        let length = self.length()?;
        Ok(self.take(length)?.to_vec())
    }

    fn register(&mut self) -> Result<Register, ImageError> {
        let tag = self.u8()?;
        let index = self.u16()?;
        match tag {
            1 => Ok(Register::I32(index)),
            2 => Ok(Register::I64(index)),
            3 => Ok(Register::Addr(index)),
            4 => Ok(Register::Bool(index)),
            other => Err(ImageError::UnknownRegisterTag(other)),
        }
    }

    fn optional_register(&mut self) -> Result<Option<Register>, ImageError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.register()?)),
            other => Err(ImageError::UnknownRegisterTag(other)),
        }
    }

    fn register_list(&mut self) -> Result<Vec<Register>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(self.register()?);
        }
        Ok(values)
    }

    fn list<T>(&mut self, read: fn(&mut Reader<'a>) -> Result<T, ImageError>) -> Result<Vec<T>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(read(self)?);
        }
        Ok(values)
    }

    fn index(&mut self, name: &'static str) -> Result<usize, ImageError> {
        let value = self.i32()?;
        if value < 0 {
            return Err(ImageError::NegativeIndex { name, value });
        }
        Ok(value as usize)
    }

    fn length(&mut self) -> Result<usize, ImageError> {
        let value = self.i32()?;
        if value < 0 {
            return Err(ImageError::NegativeLength(value));
        }
        Ok(value as usize)
    }
}
```

- [ ] **Step 4: Run Rust decoder test**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm --test low_image_decode
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/lib.rs native/ckl-vm/src/low_image.rs native/ckl-vm/tests/low_image_decode.rs
git commit -m "Decode low-level VM images"
```

## Task 4: Rust v4 Linear Memory Runner

**Files:**
- Create: `native/ckl-vm/src/low_image_runner.rs`
- Modify: `native/ckl-vm/src/lib.rs`
- Create: `native/ckl-vm/tests/low_image_runner.rs`

- [ ] **Step 1: Write failing runner tests**

Create `native/ckl-vm/tests/low_image_runner.rs`:

```rust
use ckl_vm::low_image::{Function, Image, Instruction, Register};
use ckl_vm::low_image_runner::{LowImageSignal, LowImageVm};

#[test]
fn runner_executes_i32_arithmetic_without_value_objects() {
    let image = image(vec![Instruction::I32Const { dst: 0, value: 2 }, Instruction::I32Const { dst: 1, value: 5 }, Instruction::I32Add { dst: 2, lhs: 0, rhs: 1 }, Instruction::Return { src: Register::I32(2) }], 3, 0, 0, 0);
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(7));
}

#[test]
fn runner_loads_and_stores_i32_in_linear_ram() {
    let image = image(vec![Instruction::AddrConst { dst: 0, value: 128 }, Instruction::I32Const { dst: 0, value: 0x11223344 }, Instruction::Store32 { addr: 0, src: 0 }, Instruction::Load32 { dst: 1, addr: 0 }, Instruction::Return { src: Register::I32(1) }], 2, 0, 1, 0);
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(0x11223344));
    assert_eq!(vm.memory_bytes()[128..132], [0x44, 0x33, 0x22, 0x11]);
}

#[test]
fn runner_rejects_out_of_bounds_memory_access() {
    let image = image(vec![Instruction::AddrConst { dst: 0, value: 1022 }, Instruction::Load32 { dst: 0, addr: 0 }, Instruction::Return { src: Register::I32(0) }], 1, 0, 1, 0);
    let mut vm = LowImageVm::create(image, 128).unwrap();

    let error = vm.run_until_signal().unwrap_err();

    assert!(error.contains("memory access 1022..1026 is outside 1024 bytes"), "{error}");
}

fn image(instructions: Vec<Instruction>, i32_count: usize, i64_count: usize, addr_count: usize, bool_count: usize) -> Image {
    Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            i32_register_count: i32_count,
            i64_register_count: i64_count,
            addr_register_count: addr_count,
            bool_register_count: bool_count,
            parameters: Vec::new(),
            instructions,
        }],
    }
}
```

- [ ] **Step 2: Run the failing runner tests**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm --test low_image_runner
```

Expected: FAIL during compilation because `low_image_runner` is missing.

- [ ] **Step 3: Implement minimal runner**

Add `pub mod low_image_runner;` to `native/ckl-vm/src/lib.rs`.

Create `native/ckl-vm/src/low_image_runner.rs` with:

```rust
use crate::low_image::{Image, Instruction, Register};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LowImageSignal {
    HaltUnit,
    HaltI32(i32),
    HaltI64(i64),
    HaltAddr(u32),
    HaltBool(bool),
    Pause,
}

pub struct LowImageVm {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    i32_registers: Vec<i32>,
    i64_registers: Vec<i64>,
    addr_registers: Vec<u32>,
    bool_registers: Vec<bool>,
    memory: Vec<u8>,
    instruction_budget: usize,
    instructions_since_pause: usize,
}

impl LowImageVm {
    pub fn create(image: Image, instruction_budget: usize) -> Result<Self, String> {
        if image.entry_function_index >= image.functions.len() {
            return Err(format!("entry function index {} is out of bounds", image.entry_function_index));
        }
        let memory_size = usize::try_from(image.memory_size).map_err(|_| "memory size does not fit usize".to_string())?;
        let initialized = image.rodata.len().checked_add(image.data.len()).and_then(|value| value.checked_add(image.bss_size as usize)).ok_or_else(|| "memory sections overflow".to_string())?;
        if initialized > memory_size {
            return Err(format!("memory sections require {initialized} bytes but memory size is {memory_size}"));
        }
        let function = &image.functions[image.entry_function_index];
        let mut memory = vec![0_u8; memory_size];
        memory[..image.rodata.len()].copy_from_slice(&image.rodata);
        let data_start = image.rodata.len();
        memory[data_start..data_start + image.data.len()].copy_from_slice(&image.data);
        Ok(Self {
            i32_registers: vec![0; function.i32_register_count],
            i64_registers: vec![0; function.i64_register_count],
            addr_registers: vec![0; function.addr_register_count],
            bool_registers: vec![false; function.bool_register_count],
            image,
            function_index: 0,
            instruction_pointer: 0,
            memory,
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
        })
    }

    pub fn memory_bytes(&self) -> &[u8] {
        &self.memory
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        loop {
            let instruction = self.current_function()?.instructions.get(self.instruction_pointer).cloned().unwrap_or(Instruction::ReturnUnit);
            self.instruction_pointer += 1;
            self.instructions_since_pause += 1;
            match instruction {
                Instruction::I32Const { dst, value } => self.write_i32(dst, value)?,
                Instruction::I64Const { dst, value } => self.write_i64(dst, value)?,
                Instruction::AddrConst { dst, value } => self.write_addr(dst, value)?,
                Instruction::I32Move { dst, src } => {
                    let value = self.read_i32(src)?;
                    self.write_i32(dst, value)?;
                }
                Instruction::AddrMove { dst, src } => {
                    let value = self.read_addr(src)?;
                    self.write_addr(dst, value)?;
                }
                Instruction::I32Add { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)?.wrapping_add(self.read_i32(rhs)?))?,
                Instruction::I32Sub { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)?.wrapping_sub(self.read_i32(rhs)?))?,
                Instruction::I32Mul { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)?.wrapping_mul(self.read_i32(rhs)?))?,
                Instruction::I32Div { dst, lhs, rhs } => {
                    let rhs = self.read_i32(rhs)?;
                    if rhs == 0 {
                        return Err("division by zero".to_string());
                    }
                    self.write_i32(dst, self.read_i32(lhs)?.wrapping_div(rhs))?;
                }
                Instruction::I32BitXor { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)? ^ self.read_i32(rhs)?)?,
                Instruction::I32Shl { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)?.wrapping_shl(self.read_i32(rhs)? as u32))?,
                Instruction::I32Shr { dst, lhs, rhs } => self.write_i32(dst, self.read_i32(lhs)?.wrapping_shr(self.read_i32(rhs)? as u32))?,
                Instruction::I32Lt { dst, lhs, rhs } => self.write_bool(dst, self.read_i32(lhs)? < self.read_i32(rhs)?)?,
                Instruction::Load32 { dst, addr } => {
                    let address = self.read_addr(addr)?;
                    let bytes = self.memory_range(address, 4)?;
                    let mut raw = [0_u8; 4];
                    raw.copy_from_slice(bytes);
                    self.write_i32(dst, i32::from_le_bytes(raw))?;
                }
                Instruction::Store32 { addr, src } => {
                    let address = self.read_addr(addr)?;
                    let value = self.read_i32(src)?.to_le_bytes();
                    self.memory_range_mut(address, 4)?.copy_from_slice(&value);
                }
                Instruction::AddrAdd { dst, base, offset } => {
                    let base = self.read_addr(base)?;
                    let offset = self.read_i32(offset)?;
                    let value = if offset >= 0 {
                        base.wrapping_add(offset as u32)
                    } else {
                        base.wrapping_sub(offset.wrapping_abs() as u32)
                    };
                    self.write_addr(dst, value)?;
                }
                Instruction::Jump { target } => self.jump(target)?,
                Instruction::JumpIfFalse { cond, target } => {
                    if !self.read_bool(cond)? {
                        self.jump(target)?;
                    }
                }
                Instruction::Return { src } => return self.halt_register(src),
                Instruction::ReturnUnit => return Ok(LowImageSignal::HaltUnit),
                Instruction::CallStatic { .. } => return Err("low VM CallStatic is not implemented in the first runner slice".to_string()),
            }
            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(LowImageSignal::Pause);
            }
        }
    }

    fn current_function(&self) -> Result<&crate::low_image::Function, String> {
        self.image.functions.get(self.function_index).ok_or_else(|| format!("function index {} is out of bounds", self.function_index))
    }

    fn halt_register(&self, register: Register) -> Result<LowImageSignal, String> {
        match register {
            Register::I32(index) => Ok(LowImageSignal::HaltI32(*self.i32_registers.get(index as usize).ok_or_else(|| format!("i32 register {index} is out of bounds"))?)),
            Register::I64(index) => Ok(LowImageSignal::HaltI64(*self.i64_registers.get(index as usize).ok_or_else(|| format!("i64 register {index} is out of bounds"))?)),
            Register::Addr(index) => Ok(LowImageSignal::HaltAddr(*self.addr_registers.get(index as usize).ok_or_else(|| format!("addr register {index} is out of bounds"))?)),
            Register::Bool(index) => Ok(LowImageSignal::HaltBool(*self.bool_registers.get(index as usize).ok_or_else(|| format!("bool register {index} is out of bounds"))?)),
        }
    }

    fn read_i32(&self, register: u16) -> Result<i32, String> {
        self.i32_registers.get(register as usize).copied().ok_or_else(|| format!("i32 register {register} is out of bounds"))
    }

    fn write_i32(&mut self, register: u16, value: i32) -> Result<(), String> {
        *self.i32_registers.get_mut(register as usize).ok_or_else(|| format!("i32 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn write_i64(&mut self, register: u16, value: i64) -> Result<(), String> {
        *self.i64_registers.get_mut(register as usize).ok_or_else(|| format!("i64 register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_addr(&self, register: u16) -> Result<u32, String> {
        self.addr_registers.get(register as usize).copied().ok_or_else(|| format!("addr register {register} is out of bounds"))
    }

    fn write_addr(&mut self, register: u16, value: u32) -> Result<(), String> {
        *self.addr_registers.get_mut(register as usize).ok_or_else(|| format!("addr register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn read_bool(&self, register: u16) -> Result<bool, String> {
        self.bool_registers.get(register as usize).copied().ok_or_else(|| format!("bool register {register} is out of bounds"))
    }

    fn write_bool(&mut self, register: u16, value: bool) -> Result<(), String> {
        *self.bool_registers.get_mut(register as usize).ok_or_else(|| format!("bool register {register} is out of bounds"))? = value;
        Ok(())
    }

    fn jump(&mut self, target: usize) -> Result<(), String> {
        let instruction_count = self.current_function()?.instructions.len();
        if target > instruction_count {
            return Err(format!("jump target {target} is outside function instruction count {instruction_count}"));
        }
        self.instruction_pointer = target;
        Ok(())
    }

    fn memory_range(&self, address: u32, size: usize) -> Result<&[u8], String> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| format!("memory access starts at {address} and overflows usize"))?;
        self.memory.get(start..end).ok_or_else(|| format!("memory access {start}..{end} is outside {} bytes", self.memory.len()))
    }

    fn memory_range_mut(&mut self, address: u32, size: usize) -> Result<&mut [u8], String> {
        let start = address as usize;
        let end = start.checked_add(size).ok_or_else(|| format!("memory access starts at {address} and overflows usize"))?;
        let len = self.memory.len();
        self.memory.get_mut(start..end).ok_or_else(|| format!("memory access {start}..{end} is outside {len} bytes"))
    }
}
```

- [ ] **Step 4: Run runner tests**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm --test low_image_runner
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/lib.rs native/ckl-vm/src/low_image_runner.rs native/ckl-vm/tests/low_image_runner.rs
git commit -m "Run low-level VM arithmetic and memory"
```

## Task 5: Kotlin Golden Fixture For Rust v4 Decoder

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt`
- Create: `native/ckl-vm/tests/fixtures/low-representative.ckim`
- Modify: `native/ckl-vm/tests/low_image_decode.rs`
- Modify: `modules/compiler/build.gradle.kts`

- [ ] **Step 1: Add fixture writer test**

Add this test to `CkLowVmImageAbiTest`:

```kotlin
@Test
fun writesLowGoldenFixtureWhenPathIsProvided() {
    val path = System.getProperty("ckl.low.image.golden.path")?.takeIf(String::isNotBlank) ?: return
    val image =
        CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 4096u,
            rodata = byteArrayOf(1, 2, 3),
            data = byteArrayOf(4, 5),
            bssSize = 16u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        i32RegisterCount = 2,
                        i64RegisterCount = 0,
                        addrRegisterCount = 1,
                        boolRegisterCount = 0,
                        parameters = emptyList(),
                        instructions =
                            listOf(
                                CkLowVmInstruction.AddrConst(0, 128u),
                                CkLowVmInstruction.I32Const(0, 7),
                                CkLowVmInstruction.Store32(0, 0),
                                CkLowVmInstruction.Load32(1, 0),
                                CkLowVmInstruction.Return(CkLowVmRegister.I32(1)),
                            ),
                    ),
                ),
        )

    java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
    java.nio.file.Files.write(java.nio.file.Path.of(path), CkLowVmImageAbi.encode(image))
}
```

- [ ] **Step 2: Pass fixture property through Gradle**

In `modules/compiler/build.gradle.kts`, extend `tasks.test`:

```kotlin
System.getProperty("ckl.low.image.golden.path")?.takeIf { it.isNotBlank() }?.let { path ->
    systemProperty("ckl.low.image.golden.path", path)
}
```

- [ ] **Step 3: Generate the fixture**

Run:

```bash
./gradlew :compiler:test --rerun-tasks --tests 'ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmImageAbiTest.writesLowGoldenFixtureWhenPathIsProvided' -Dckl.low.image.golden.path=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/tests/fixtures/low-representative.ckim
```

Expected: PASS and fixture starts with `43 4b 49 4d 04`.

- [ ] **Step 4: Add Rust fixture decode test**

Add this test to `native/ckl-vm/tests/low_image_decode.rs`:

```rust
#[test]
fn decodes_kotlin_generated_low_fixture() {
    let image = decode_image(include_bytes!("fixtures/low-representative.ckim")).expect("fixture decodes");

    assert_eq!(image.language_version, "ckl-low-1");
    assert_eq!(image.memory_size, 4096);
    assert_eq!(image.functions[0].instructions.len(), 5);
}
```

- [ ] **Step 5: Run cross-language decode tests**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm --test low_image_decode
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/compiler/build.gradle.kts modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/CkLowVmImageAbiTest.kt native/ckl-vm/tests/fixtures/low-representative.ckim native/ckl-vm/tests/low_image_decode.rs
git commit -m "Add low-level VM golden fixture"
```

## Task 6: Full Verification Checkpoint

**Files:**
- No production file changes expected.

- [ ] **Step 1: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: PASS.

- [ ] **Step 2: Run Rust tests**

Run:

```bash
cd native/ckl-vm
cargo test -p ckl-vm
```

Expected: PASS.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Commit if verification required formatting-only edits**

If formatting changed files, commit them:

```bash
git add <changed-files>
git commit -m "Verify low-level VM slice"
```

If no files changed, do not create an empty commit.

## Deferred Work After This Plan

- Add low-level compiler backend for benchmark workloads.
- Add JNI entry points for v4 runner.
- Add v4 benchmark columns and compare v3 vs v4 vs Kotlin/JVM/Python/Rust.
- Add `CallStatic` frame support to the v4 runner.
- Add explicit hostcall ABI and external syscall signal.
- Replace ROM images with v4 and remove v3 runtime paths.

## Self-Review Notes

- Spec coverage: this plan implements the first executable v4 slice: `u32` linear RAM, primitive registers, fixed memory, v4 ABI, Rust decoder, Rust runner, no managed heap, no old decoder fallback.
- Deferred hostcall ABI is explicitly outside this first slice because compute/memory execution can be verified independently.
- Deferred compiler lowering and JNI are explicit follow-up work because adding them before the runner exists would couple too many moving parts.
- There are no `TBD` or placeholder steps.
- All commands are exact and have expected outcomes.
