/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.api.BuiltinFunction
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.ModuleOrigin
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanguageRuntimeTest {
    private val frontend = LanguageFrontend()

    @Test
    fun executesHostCallsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "runtime.ck",
                """
                pub fun main() {
                    system::log("id=" + system::deviceId());
                    val event: Event = events::pull("boot");
                    system::log(event.name);
                    sleep(1L);
                    yield();
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("id=7", "boot"), runtime.lines)
        assertEquals(1, runtime.sleepCalls)
        assertEquals(1, runtime.yieldCalls)
        assertEquals(listOf<String?>("boot"), runtime.eventFilters)
    }

    @Test
    fun packedGlyphDisplayBuiltinDecodesRowsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "packed_display.ck",
                """
                pub fun main() {
                    display::blitMono5x7Packed(7, 2, 3, 0b01110100011000111111100011000110001L, 2016, -1);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(
            listOf(
                CapturedMono5x7Blit(
                    displayId = 7,
                    x = 2,
                    y = 3,
                    rows = listOf(14, 17, 17, 31, 17, 17, 17),
                    foreground = 2016,
                    background = -1,
                ),
            ),
            runtime.mono5x7Blits,
        )
    }

    @Test
    fun compilesIpcSpawnWaitEventPayloadAndStringHelpers() {
        val artifact =
            frontend.compile(
                "surface.ck",
                """
                pub fun main() {
                    val ch: Int = ipc::open();
                    ipc::write(ch, "42");
                    val immediate: String = ipc::tryRead(ch);
                    ipc::write(ch, immediate);
                    val blocking: String = ipc::read(ch);
                    ipc::close(ch);

                    val child: Int = process::spawn("child.ck", "1 2 3 arg");
                    val code: Int = process::wait(child);

                    val event: Event = events::pull("char");
                    val count: Int = events::argCount(event);
                    val text: String = events::argString(event, 0);
                    val key: Int = events::argInt(event, 0);
                    val repeated: Bool = events::argBool(event, 1);

                    val parsed: Int = strings::toInt("123");
                    val length: Int = strings::length("abc");
                    val first: String = strings::charAt("abc", 0);

                    system::log(blocking + text + first + code + count + key + parsed + length + repeated);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun exposesPulledEventPayloadToCkl() {
        val artifact =
            frontend.compile(
                "event-payload.ck",
                """
                pub fun main() {
                    val event: Event = events::pull("char");
                    system::log(event.name + ":" + event.argCount);
                    system::log(events::argString(event, 0));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(queuedEvents = listOf(VmEvent("char", listOf("x".toByteArray()))))
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("char:1", "x"), runtime.lines)
    }

    @Test
    fun exposesNonBlockingEventPullToCkl() {
        val artifact =
            frontend.compile(
                "event-try-pull.ck",
                """
                pub fun main() {
                    val missing: Event = events::tryPull("paste");
                    system::log("missing=" + missing.name);
                    val event: Event = events::tryPull();
                    system::log(event.name + ":" + events::argString(event, 0));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(queuedEvents = listOf(VmEvent("char", listOf("x"))))
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("missing=", "char:x"), runtime.lines)
    }

    @Test
    fun executesIpcBuiltinsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "ipc.ck",
                """
                pub fun main() {
                    val channel: Int = ipc::open();
                    ipc::write(channel, "hello");
                    system::log(ipc::tryRead(channel));
                    ipc::write(channel, "again");
                    system::log(ipc::read(channel));
                    ipc::close(channel);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("hello", "again"), runtime.lines)
    }

    @Test
    fun executesSpawnAndWaitThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "spawn.ck",
                """
                pub fun main() {
                    val pid: Int = process::spawn("child.ck", "arg");
                    system::log("pid=" + pid);
                    system::log("code=" + process::wait(pid));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(spawnPid = 11, waitCode = 7)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("pid=11", "code=7"), runtime.lines)
    }

    @Test
    fun usesInstructionBudgetFromProfileResources() {
        val artifact =
            frontend.compile(
                "budget.ck",
                """
                pub fun main() {
                    val a: Int = 1;
                    val b: Int = 2;
                    val c: Int = a + b;
                    val d: Int = c + 1;
                }
                """.trimIndent(),
            )

        val lowBudgetRuntime = RecordingRuntime(instructionsPerSlice = 2)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(lowBudgetRuntime)
        }

        val highBudgetRuntime = RecordingRuntime(instructionsPerSlice = 128)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(highBudgetRuntime)
        }

        assertTrue(lowBudgetRuntime.yieldCalls > 0)
        assertEquals(0, highBudgetRuntime.yieldCalls)
    }

    @Test
    fun failsWhenVmMemoryExceedsProfileLimit() {
        val artifact =
            frontend.compile(
                "memory.ck",
                """
                pub fun main() {
                    val text: String = "123456789";
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime(vmRamBytes = 4)

        assertFailsWith<IllegalStateException> {
            runBlocking {
                BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
            }
        }
    }

    @Test
    fun whileLoopWithMutationIncrementsCounter() {
        val artifact =
            frontend.compile(
                "loop.ck",
                """
                pub fun main() {
                    var i: Int = 0;
                    while (i < 3) {
                        system::log("i=" + i);
                        i = i + 1;
                    }
                    system::log("done=" + i);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("i=0", "i=1", "i=2", "done=3"), runtime.lines)
    }

    @Test
    fun compoundAssignmentOperatorsMutateLocal() {
        val artifact =
            frontend.compile(
                "compound.ck",
                """
                pub fun main() {
                    var i: Int = 1;
                    i += 4;
                    system::log("plus=" + i);
                    i -= 2;
                    system::log("minus=" + i);
                    i *= 6;
                    system::log("star=" + i);
                    i /= 3;
                    system::log("slash=" + i);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("plus=5", "minus=3", "star=18", "slash=6"), runtime.lines)
    }

    @Test
    fun executesBitwiseOperatorsWithApprovedPrecedence() {
        val artifact =
            frontend.compile(
                "bitwise_runtime.ck",
                """
                pub fun main() {
                    val flags: Int = 0b01100;
                    val mask: Int = 0b00100;
                    system::log("and=" + (flags & 0b01010));
                    system::log("or=" + (flags | 0b00011));
                    system::log("xor=" + (flags ^ 0b00101));
                    system::log("not=" + (~0b00000));
                    system::log("left=" + (1 << 4 - 1));
                    system::log("right=" + (0b10000 >> 2));
                    system::log("compare=" + (flags & mask == mask));
                    system::log("mixed=" + (0b1000L | 0b0011));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(
            listOf(
                "and=8",
                "or=15",
                "xor=9",
                "not=-1",
                "left=8",
                "right=4",
                "compare=true",
                "mixed=11",
            ),
            runtime.lines,
        )
    }

    @Test
    fun bitwiseCompoundAssignmentOperatorsMutateLocalAndMember() {
        val artifact =
            frontend.compile(
                "bitwise_compound.ck",
                """
                class Register(pub var value: Int) {
                    pub fun apply(): Unit {
                        this.value |= 0b0100;
                        this.value &= 0b0110;
                        this.value ^= 0b0010;
                        this.value <<= 2;
                        this.value >>= 1;
                    }
                }

                pub fun main() {
                    var flags: Int = 0b0011;
                    flags |= 0b0100;
                    flags &= 0b0110;
                    flags ^= 0b0010;
                    flags <<= 2;
                    flags >>= 1;
                    system::log("local=" + flags);

                    val register: Register = Register(value = 0b0011);
                    register.apply();
                    system::log("field=" + register.value);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("local=8", "field=8"), runtime.lines)
    }

    @Test
    fun constructsStructsWithNamedCallSyntax() {
        val artifact =
            frontend.compile(
                "struct_runtime.ck",
                """
                struct Point { x: Int, y: Int }
                pub fun main() {
                    val point: Point = Point(x = 4, y = 5);
                    system::log("sum=" + (point.x + point.y));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("sum=9"), runtime.lines)
    }

    @Test
    fun classInstancesHaveReferenceIdentityAndSharedMutation() {
        val artifact =
            frontend.compile(
                "class_identity.ck",
                """
                class Counter(var value: Int) {
                    pub fun inc(): Unit { this.value = this.value + 1; }
                    pub fun current(): Int { return this.value; }
                }
                pub fun main() {
                    val a: Counter = Counter(value = 1);
                    val b: Counter = a;
                    b.inc();
                    system::log("a=" + a.current());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("a=2"), runtime.lines)
    }

    @Test
    fun classInitAndStaticMethodsRun() {
        val artifact =
            frontend.compile(
                "class_init_static.ck",
                """
                class Counter(var value: Int) {
                    init { this.value = this.value + 1; }
                    pub fun current(): Int { return this.value; }
                    pub static fun zero(): Counter { return Counter(value = 0); }
                }
                pub fun main() {
                    val counter: Counter = Counter.zero();
                    system::log("value=" + counter.current());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("value=1"), runtime.lines)
    }

    @Test
    fun executesErasedGenericFunctionAndClass() {
        val artifact =
            frontend.compile(
                "generic_runtime.ck",
                """
                pub class Box<T>(pub var value: T) {
                    pub fun current(): T { return this.value; }
                }
                pub fun identity<T>(value: T): T { return value; }
                pub fun main() {
                    val box: Box<String> = Box(value = identity("ok"));
                    system::log(box.current());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("ok"), runtime.lines)
    }

    @Test
    fun executesListArrayAndMapCollections() {
        val artifact =
            frontend.compile(
                "collections_runtime.ck",
                """
                pub struct Key { name: String }

                pub fun main() {
                    val xs: List<Int> = [1, 2];
                    xs.add(3);
                    xs[1] = 7;
                    system::log("list=" + xs.size() + ":" + xs[0] + ":" + xs[1] + ":" + xs.removeAt(2));

                    val fixed: Array<Int> = Array<Int>(size = 2, default = 5);
                    fixed[1] = 9;
                    system::log("array=" + fixed.size() + ":" + fixed[0] + ":" + fixed[1]);

                    val table: Map<Key, String> = {};
                    val key: Key = Key(name = "a");
                    table[key] = "ok";
                    system::log("map=" + table.size() + ":" + table[key] + ":" + table.containsKey(Key(name = "a")));
                }
                """.trimIndent(),
            )

        assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
        assertEquals(listOf("list=3:1:7:3", "array=2:5:9", "map=1:ok:true"), runtime.lines)
    }

    @Test
    fun preservesMapInsertionOrderForKeysAndValues() {
        val artifact =
            frontend.compile(
                "map_order.ck",
                """
                pub fun main() {
                    val map: Map<String, Int> = {"a": 1, "b": 2};
                    map["a"] = 3;
                    map.remove("b");
                    map["b"] = 4;
                    val keys: List<String> = map.keys();
                    val values: List<Int> = map.values();
                    system::log(keys[0] + keys[1] + ":" + values[0] + ":" + values[1]);
                }
                """.trimIndent(),
            )
        assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
        val runtime = RecordingRuntime()
        runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
        assertEquals(listOf("ab:3:4"), runtime.lines)
    }

    @Test
    fun crashesOnOutOfBoundsListIndex() {
        val artifact =
            frontend.compile(
                "list_oob.ck",
                """
                pub fun main() {
                    val xs: List<Int> = [1];
                    system::log("" + xs[2]);
                }
                """.trimIndent(),
            )
        assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(RecordingRuntime()) }
        }
        assertTrue(failure.message.orEmpty().contains("Index 2 out of bounds"), failure.message.orEmpty())
    }

    @Test
    fun classFieldInitializersCanReadPlainConstructorParameters() {
        val artifact =
            frontend.compile(
                "class_plain_constructor_parameter.ck",
                """
                class SomeClass(constK: Int) {
                    val k: Int = constK;

                    pub fun instancePlus2(): Int {
                        return this.k + 2;
                    }
                }

                pub fun main() {
                    val instance: SomeClass = SomeClass(constK = 22);
                    system::log("INSTANCE " + instance.instancePlus2());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("INSTANCE 24"), runtime.lines)
    }

    @Test
    fun classFieldsCanBeInitializedInInitBlocks() {
        val artifact =
            frontend.compile(
                "class_init_field.ck",
                """
                class SomeClass(constK: Int) {
                    val k: Int;

                    init {
                        this.k = constK;
                    }

                    pub fun current(): Int {
                        return this.k;
                    }
                }

                pub fun main() {
                    val instance: SomeClass = SomeClass(constK = 22);
                    system::log("k=" + instance.current());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("k=22"), runtime.lines)
    }

    @Test
    fun snapshotRoundTripRestoresExecutionState() {
        val artifact =
            frontend.compile(
                "snapshot.ck",
                """
                pub fun main() {
                    yield();
                }
                """.trimIndent(),
            )
        val module = requireNotNull(artifact.module)
        val vm = BytecodeVirtualMachine(module)

        val signal = vm.runUntilSignal()
        assertTrue(signal is VmSignal.Yield)

        val restored = BytecodeVirtualMachine(module, vm.snapshot())
        restored.resumeWith(VmValue.UnitValue)
        val endSignal = restored.runUntilSignal()
        assertTrue(endSignal is VmSignal.Halt)
    }

    @Test
    fun stdoutBuiltinIsRemovedFromDefaultRuntimeRegistry() {
        val artifact =
            frontend.compile(
                "stdout.ck",
                """
                pub fun main() {
                    stdout::write("Hi");
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR && it.message.contains("stdout") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun exposesShellBuiltinsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "shell.ck",
                """
                pub fun main() {
                    system::log("typed");
                    system::log(filesystem::list());
                    system::log(process::currentDirectory());
                    system::log(process::argument());
                    system::log(strings::beforeSpace("mkdir test"));
                    system::log(strings::afterSpace("mkdir test"));
                    if (filesystem::makeDir("tmp")) {
                        system::log("mk");
                    } else {
                        system::log("no");
                    }
                    if (process::changeDirectory("tmp")) {
                        system::log("cd");
                    } else {
                        system::log("stay");
                    }
                    system::log(process::currentDirectory());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(argument = "boot")
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(
            listOf("typed", "docs/ readme.txt", "", "boot", "mkdir", "test", "mk", "cd", "tmp"),
            runtime.lines,
        )
        assertEquals(listOf("tmp"), runtime.createdDirectories)
    }

    @Test
    fun executesElseIfChains() {
        val artifact =
            frontend.compile(
                "elseif.ck",
                """
                pub fun main() {
                    val x: Int = 2;
                    if (x == 1) {
                        system::log("one");
                    } else if (x == 2) {
                        system::log("two");
                    } else if (x == 3) {
                        system::log("three");
                    } else {
                        system::log("other");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("two"), runtime.lines)
    }

    @Test
    fun executesElseIfFallsToElse() {
        val artifact =
            frontend.compile(
                "elseif_else.ck",
                """
                pub fun main() {
                    val x: Int = 99;
                    if (x == 1) {
                        system::log("one");
                    } else if (x == 2) {
                        system::log("two");
                    } else {
                        system::log("other");
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("other"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubject() {
        val artifact =
            frontend.compile(
                "when_subject.ck",
                """
                pub fun main() {
                    val x: Int = 2;
                    when(x) {
                        1 -> {
                            system::log("one");
                        }
                        2 -> {
                            system::log("two");
                        }
                        3 -> {
                            system::log("three");
                        }
                        else -> {
                            system::log("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("two"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubjectMultipleValues() {
        val artifact =
            frontend.compile(
                "when_multi.ck",
                """
                pub fun main() {
                    val x: Int = 3;
                    when(x) {
                        1 -> {
                            system::log("one");
                        }
                        2, 3 -> {
                            system::log("two or three");
                        }
                        else -> {
                            system::log("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("two or three"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubjectElseBranch() {
        val artifact =
            frontend.compile(
                "when_else.ck",
                """
                pub fun main() {
                    val x: Int = 99;
                    when(x) {
                        1 -> {
                            system::log("one");
                        }
                        2 -> {
                            system::log("two");
                        }
                        else -> {
                            system::log("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("other"), runtime.lines)
    }

    @Test
    fun executesWhenWithoutSubject() {
        val artifact =
            frontend.compile(
                "when_no_subject.ck",
                """
                pub fun main() {
                    val x: Int = 5;
                    when {
                        x > 10 -> {
                            system::log("big");
                        }
                        x > 0 -> {
                            system::log("positive");
                        }
                        else -> {
                            system::log("non-positive");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("positive"), runtime.lines)
    }

    @Test
    fun executesWhenWithoutSubjectElse() {
        val artifact =
            frontend.compile(
                "when_no_subject_else.ck",
                """
                pub fun main() {
                    val x: Int = 0;
                    when {
                        x > 10 -> {
                            system::log("big");
                        }
                        x > 0 -> {
                            system::log("positive");
                        }
                        else -> {
                            system::log("zero or negative");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("zero or negative"), runtime.lines)
    }

    @Test
    fun routesMonitorExistsThroughRuntimeBridgeWhenDeviceIsMissing() {
        val frontend =
            LanguageFrontend(
                BuiltinRegistry(
                    modules =
                        LanguageBuiltins.defaultRuntimeRegistry.modules +
                            BuiltinModule(
                                name = "monitor",
                                documentation = "Connected monitor registry.",
                                functions =
                                    listOf(
                                        BuiltinFunction(
                                            "exists",
                                            emptyList(),
                                            "Bool",
                                            "Returns true when any monitor is connected.",
                                        ),
                                    ),
                                origin = ModuleOrigin.OPTIONAL_VM,
                            ),
                    globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                    builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
                ),
            )

        val artifact =
            frontend.compile(
                "monitor.ck",
                """
                pub fun main() {
                    if (monitor::exists()) {
                        system::log("connected");
                    } else {
                        system::log("missing");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("missing"), runtime.lines)
    }

    @Test
    fun routesMonitorExistsThroughRuntimeBridgeWhenDeviceIsConnected() {
        val frontend =
            LanguageFrontend(
                BuiltinRegistry(
                    modules =
                        LanguageBuiltins.defaultRuntimeRegistry.modules +
                            BuiltinModule(
                                name = "monitor",
                                documentation = "Connected monitor registry.",
                                functions =
                                    listOf(
                                        BuiltinFunction(
                                            "exists",
                                            emptyList(),
                                            "Bool",
                                            "Returns true when any monitor is connected.",
                                        ),
                                    ),
                                origin = ModuleOrigin.OPTIONAL_VM,
                            ),
                    globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                    builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
                ),
            )

        val artifact =
            frontend.compile(
                "monitor_connected.ck",
                """
                pub fun main() {
                    if (monitor::exists()) {
                        system::log("connected");
                    } else {
                        system::log("missing");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(monitorConnected = true)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("connected"), runtime.lines)
    }
}

internal data class CapturedMono5x7Blit(
    val displayId: Int,
    val x: Int,
    val y: Int,
    val rows: List<Int>,
    val foreground: Int,
    val background: Int,
)

internal class RecordingRuntime(
    private val argument: String = "",
    private val instructionsPerSlice: Int = 64,
    private val vmRamBytes: Long = 64 * 1024,
    private val monitorConnected: Boolean = false,
    private val queuedEvents: List<VmEvent> = listOf(VmEvent("boot")),
    private val spawnPid: Int = 1,
    private val waitCode: Int = 0,
) : DeviceRuntime {
    val lines = mutableListOf<String>()
    val eventFilters = mutableListOf<String?>()
    val createdDirectories = mutableListOf<String>()
    val mono5x7Blits = mutableListOf<CapturedMono5x7Blit>()
    var sleepCalls = 0
    var yieldCalls = 0
    private var nextEventIndex = 0
    private val deferredEvents = ArrayDeque<VmEvent>()

    override val profile =
        DeviceProfile(
            id = "test",
            displayName = "Test Computer",
            cpuBudgetNanosPerSlice = 1_000_000,
            maxEventQueueSize = 16,
            allowedCapabilities =
                setOf(
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.IPC,
                    DeviceCapability.DISPLAY,
                    DeviceCapability.PERIPHERALS,
                ),
            resources =
                DeviceResources(
                    cpu =
                        DeviceCpuResources(
                            instructionsPerSlice = instructionsPerSlice,
                            wallTimeGuardNanosPerSlice = 1_000_000,
                        ),
                    memory = DeviceMemoryResources(vmRamBytes = vmRamBytes),
                    storage =
                        DeviceStorageResources(
                            programRomBytes = 64 * 1024,
                            diskBytes = 256 * 1024,
                        ),
                    queues =
                        DeviceQueueResources(
                            eventQueueSlots = 16,
                            hostCallQueueSlots = 16,
                        ),
                ),
        )

    override val display: DeviceDisplayApi =
        object : DeviceDisplayApi by NoopDeviceDisplayApi {
            override fun blitMono5x7(
                displayId: Int,
                x: Int,
                y: Int,
                row0: Int,
                row1: Int,
                row2: Int,
                row3: Int,
                row4: Int,
                row5: Int,
                row6: Int,
                foreground: Int,
                background: Int,
            ) {
                mono5x7Blits +=
                    CapturedMono5x7Blit(
                        displayId = displayId,
                        x = x,
                        y = y,
                        rows = listOf(row0, row1, row2, row3, row4, row5, row6),
                        foreground = foreground,
                        background = background,
                    )
            }
        }

    override val system =
        object : DeviceSystemApi {
            override val deviceId: Int = 7
            override val label: String? = "Test"
            override val currentTick: Long = 42L

            override fun queueEvent(
                name: String,
                arguments: List<Any?>,
            ) = Unit

            override fun shutdown() = Unit

            override fun reboot() = Unit

            override fun log(message: String) {
                lines += message
            }
        }

    override val filesystem: DeviceFileSystemApi =
        object : DeviceFileSystemApi {
            override suspend fun exists(path: String): Boolean = path == "readme.txt" || path == "docs" || path == "tmp"

            override suspend fun isDirectory(path: String): Boolean = path == "docs" || path == "tmp"

            override suspend fun readText(path: String): String? = null

            override suspend fun writeText(
                path: String,
                text: String,
            ) = Unit

            override suspend fun makeDirectory(path: String): Boolean {
                createdDirectories += path
                return true
            }

            override suspend fun remove(path: String): Boolean = true

            override suspend fun list(path: String): List<DeviceWorkspaceEntry> =
                listOf(
                    DeviceWorkspaceEntry("docs", directory = true),
                    DeviceWorkspaceEntry("readme.txt", directory = false),
                )
        }

    override val process =
        object : DeviceProcessApi {
            private var currentDirectory = ""

            override val workingDirectory: String
                get() = currentDirectory

            override val argument: String
                get() = this@RecordingRuntime.argument

            override suspend fun changeDirectory(path: String): Boolean {
                currentDirectory = path
                return true
            }

            override suspend fun run(
                path: String,
                argument: String,
            ): Int = 0

            override suspend fun spawn(
                path: String,
                argument: String,
            ): Int = spawnPid

            override suspend fun wait(pid: Int): Int = waitCode
        }

    override val ipc: DeviceIpcApi =
        object : DeviceIpcApi {
            private var nextId = 1
            private val channels = mutableMapOf<Int, String>()

            override suspend fun open(): Int {
                val id = nextId++
                channels[id] = ""
                return id
            }

            override suspend fun write(
                channelId: Int,
                text: String,
            ) {
                channels[channelId] = channels[channelId].orEmpty() + text
            }

            override suspend fun read(channelId: Int): String = tryRead(channelId)

            override fun tryRead(channelId: Int): String {
                val text = channels[channelId].orEmpty()
                channels[channelId] = ""
                return text
            }

            override fun close(channelId: Int) {
                channels.remove(channelId)
            }
        }

    override val events: DeviceEventApi =
        object : DeviceEventApi {
            private var nextId = 1
            private val captured = mutableMapOf<Int, List<Any?>>()

            override fun capture(arguments: List<Any?>): Pair<Int, Int> {
                val id = nextId++
                captured[id] = arguments
                return id to arguments.size
            }

            override fun argCount(eventId: Int): Int = captured[eventId]?.size ?: 0

            override fun argInt(
                eventId: Int,
                index: Int,
            ): Int =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Boolean -> if (value) 1 else 0
                    is String -> value.toIntOrNull() ?: 0
                    else -> 0
                }

            override fun argBool(
                eventId: Int,
                index: Int,
            ): Boolean =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is Boolean -> value
                    is String -> value.equals("true", ignoreCase = true)
                    else -> false
                }

            override fun argString(
                eventId: Int,
                index: Int,
            ): String =
                when (val value = captured[eventId]?.getOrNull(index)) {
                    is String -> value
                    is ByteArray -> value.toString(Charsets.UTF_8)
                    is Int -> value.toString()
                    is Long -> value.toString()
                    is Boolean -> value.toString()
                    else -> ""
                }
        }

    override val redstone: DeviceRedstoneApi = object : DeviceRedstoneApi {}
    override val peripherals: DevicePeripheralApi =
        object : DevicePeripheralApi {
            override fun monitorExists(): Boolean = monitorConnected
        }

    override suspend fun pullEvent(filter: String?): VmEvent {
        eventFilters += filter
        val deferred = deferredEvents.removeFirstOrNull()
        if (deferred != null && (filter == null || deferred.name == filter)) {
            return deferred
        }
        if (deferred != null) {
            deferredEvents.addLast(deferred)
        }
        while (nextEventIndex < queuedEvents.size) {
            val event = queuedEvents[nextEventIndex++]
            if (filter == null || event.name == filter) {
                return event
            }
        }
        return VmEvent(filter ?: "boot")
    }

    override suspend fun tryPullEvent(filter: String?): VmEvent? {
        eventFilters += filter
        val deferred = deferredEvents.removeFirstOrNull()
        if (deferred != null) {
            if (filter == null || deferred.name == filter) {
                return deferred
            }
            deferredEvents.addLast(deferred)
            return null
        }
        if (nextEventIndex >= queuedEvents.size) return null
        val event = queuedEvents[nextEventIndex++]
        if (filter == null || event.name == filter) {
            return event
        }
        deferredEvents.addLast(event)
        return null
    }

    override suspend fun sleep(ticks: Long) {
        sleepCalls += ticks.toInt()
    }

    override suspend fun yield() {
        yieldCalls += 1
    }
}
