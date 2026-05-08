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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeImageVmRunnerJniTest {
    @Test
    fun imageRunnerHaltsForEmptyMainWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { }").image)

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, RecordingRuntime())
        }
    }

    @Test
    fun imageRunnerDispatchesSystemLogHostCallWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image = assertNotNull(LanguageFrontend().compileImage("main.ck", "pub fun main() { system::log(\"hi\"); }").image)
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("hi"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesSchedulerBuiltinsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            yield();
                            sleep(3);
                            system::log("done");
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(1, runtime.yieldCalls)
        assertEquals(3, runtime.sleepCalls)
        assertEquals(listOf("done"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesIfConditionAndLocalThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val enabled: Bool = true;
                            if (enabled) {
                                system::log("yes");
                            }
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("yes"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesOperatorsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val value: Int = 1 + 2 * 3;
                            val ok: Bool = value >= 7 && !false;
                            if (ok) {
                                system::log("value=" + value);
                            }
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("value=7"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesUserFunctionCallsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        fun subtract(a: Int, b: Int): Int {
                            return a - b;
                        }

                        fun label(value: Int): String {
                            return "value=" + value;
                        }

                        pub fun main() {
                            val result: Int = subtract(2, 5);
                            system::log(label(result));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("value=-3"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesRecordConstructionAndFieldAccessThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        struct Point { x: Int, y: Int }

                        pub fun main() {
                            val point: Point = Point(x = 2, y = 5);
                            val delta: Int = point.x - point.y;
                            system::log("value=" + delta);
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking {
            NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime)
        }

        assertEquals(listOf("value=-3"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesArrayCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val values: Array<Int> = Array<Int>(size = 2, default = 0);
                            values[0] = 9;
                            values[1] = 4;
                            system::log("value=" + (values[0] - values[1]));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=5"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesListCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val values: List<Int> = [2];
                            values.add(5);
                            val removed: Int = values.removeAt(0);
                            system::log("value=" + (removed - values[0]));
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=-3"), runtime.lines)
    }

    @Test
    fun imageRunnerExecutesMapCollectionsThroughJniWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val image =
            assertNotNull(
                LanguageFrontend()
                    .compileImage(
                        "main.ck",
                        """
                        pub fun main() {
                            val values: Map<String, Int> = {"x": 3};
                            values["y"] = 4;
                            if (values.containsKey("x")) {
                                system::log("value=" + values.getOrDefault("missing", 9));
                            }
                        }
                        """.trimIndent(),
                    ).image,
            )
        val runtime = RecordingRuntime()

        runBlocking { NativeImageVmRunner.fromLibraryPath(libraryPath).run(image, runtime) }

        assertEquals(listOf("value=9"), runtime.lines)
    }
}
