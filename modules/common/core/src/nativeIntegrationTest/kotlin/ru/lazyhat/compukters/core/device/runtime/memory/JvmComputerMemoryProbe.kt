/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.memory

import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.NativeRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

/** Opt-in fresh-process probe. The controller must acknowledge every phase on stdin. */
object JvmComputerMemoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected: population library system-image disposable-store-directory" }
        val population = args[0].toInt().also { require(it in 1..4096) }
        val backend = Class.forName("ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend").getField("INSTANCE").get(null)
        VmRuntime.install(backend as NativeRuntimeBackend)
        VmRuntime.loadNativeLibrary(Path.of(args[1]))
        val image = VmSystemImage.of(Path.of(args[2]).readBytes())
        val root = Path.of(args[3]).toAbsolutePath().normalize()
        require(!Files.exists(root)) { "The probe requires a fresh disposable store directory" }
        Files.createDirectories(root)
        // Resolve runtime classes and FFM call sites before the population baseline.
        WorldFileSystemStore.open(Files.createDirectory(root.resolve("warmup"))).use { store ->
            ProgramRuntimeHost(store, ComputerId.fromLongs(0, 1), image).use { host ->
                check(host.startBoot(installSystem = true) == ProgramStartResult.Started)
                settle(listOf(host))
                checkNotNull(host.terminalFullState())
            }
        }
        phase("baseline", emptyList())
        WorldFileSystemStore.open(Files.createDirectory(root.resolve("fleet"))).use { store ->
            val hosts = ArrayList<ProgramRuntimeHost>(population)
            try {
                repeat(population) { index ->
                    hosts += ProgramRuntimeHost(store, ComputerId.fromLongs(1, index.toLong()), image)
                }
                phase("hosts", hosts)
                hosts.forEach { check(it.startBoot(installSystem = true) == ProgramStartResult.Started) }
                phase("admitted", hosts)
                settle(hosts)
                phase("prompt", hosts)
                hosts.forEach { host ->
                    val screen = checkNotNull(host.terminalFullState())
                    check(screen.cells.any { it.codePoint == '>'.code }) { "Shell prompt missing" }
                    check(host.terminalChangesSince(screen.revision) == TerminalUpdate.Unchanged(screen.revision))
                }
                phase("observed", hosts)
                hosts.forEach { check(it.submitCanonicalLine("echo memory-probe".toCharArray())) }
                settle(hosts)
                hosts.forEach { host ->
                    val screen = checkNotNull(host.terminalFullState())
                    check(
                        screen.cells
                            .map { it.codePoint.toChar() }
                            .joinToString("")
                            .contains("memory-probe"),
                    )
                }
                phase("resumed", hosts)
            } finally {
                hosts.forEach(ProgramRuntimeHost::close)
            }
            phase("closed", hosts)
            hosts.clear()
            phase("dropped", hosts)
        }
        phase("store_closed", emptyList())
        Reference.reachabilityFence(image)
    }

    private fun settle(hosts: List<ProgramRuntimeHost>) {
        repeat(100_000) {
            var waiting = 0
            hosts.forEach { host ->
                when (val state = host.serverTick()) {
                    ProgramRuntimeState.WaitingForInput -> waiting++
                    ProgramRuntimeState.Running -> Unit
                    else -> error("Unexpected shell state: $state")
                }
            }
            if (waiting == hosts.size) return
        }
        error("Shell population did not settle")
    }

    private fun phase(
        name: String,
        hosts: List<ProgramRuntimeHost>,
    ) {
        println("PHASE\t$name\t${hosts.size}\t${counters(hosts)}")
        System.out.flush()
        check(readlnOrNull() == "continue") { "Controller disconnected before $name was measured" }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        println("HEAP\t$name\t${heap.used}\t${heap.committed}")
        System.out.flush()
    }

    private fun counters(hosts: List<ProgramRuntimeHost>): String {
        val resources = hosts.mapNotNull { it.resourceSnapshot() as? ProgramResourceSnapshot.Available }
        val counters =
            listOf(
                resources.size.toLong(),
                resources.sumOf { it.heapUsedBytes },
                resources.sumOf { it.heapCapacityBytes },
                resources.sumOf { it.mutableExecutionResidentBytes },
                resources.sumOf { it.filesystemLogicalBytes },
                resources.sumOf { it.filesystemNodes },
                resources.sumOf { it.fixedGuestUnits },
                resources.sumOf { it.executedInstructions },
            )
        return counters.joinToString("\t")
    }
}
