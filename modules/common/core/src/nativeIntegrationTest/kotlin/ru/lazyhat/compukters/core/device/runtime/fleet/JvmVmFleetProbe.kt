/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.core.device.runtime.fleet

import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.program.ProgramResourceSnapshot
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.NativeRuntimeBackend
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.readBytes

/** Opt-in production-carrier probe. Batch barriers belong to this standalone observer, never a server tick. */
object JvmVmFleetProbe {
    private const val GRANT = 4096
    private const val WARMUP_FRAMES = 20
    private const val MEASURED_FRAMES = 100
    private const val WAVES = 10
    private val clock = ManagementFactory.getThreadMXBean()
    private val processClock = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 5..8) { "Expected: population workers library image fresh-output [self-gc] [program checksum]" }
        val options = args.drop(5)
        require(options.count { it == "self-gc" } <= 1)
        val compute = options.filterNot { it == "self-gc" }
        require(compute.isEmpty() || compute.size == 2)
        val workload = if (compute.isEmpty()) ComputeWorkload() else ComputeWorkload(compute[0], compute[1].toLong())
        require(workload.program.matches(Regex("/bin/[A-Za-z0-9_-]+")))
        val selfGc = "self-gc" in options
        val population = args[0].toInt().also { require(it in 1..4096) }
        val workers = args[1].toInt().also { require(it in 1..64) }
        val backend = Class.forName("ru.lazyhat.compukters.lang.runtime.vm.FfmRuntimeBackend").getField("INSTANCE").get(null)
        VmRuntime.install(backend as NativeRuntimeBackend)
        VmRuntime.loadNativeLibrary(Path.of(args[2]))
        val image = VmSystemImage.of(Path.of(args[3]).readBytes())
        val output = Path.of(args[4]).toAbsolutePath().normalize()
        check(!Files.exists(output)) { "Output must be a fresh disposable directory" }
        Files.createDirectories(output)
        JvmFleetDiagnostics.initialize(output)
        check(clock.isThreadCpuTimeSupported)
        clock.isThreadCpuTimeEnabled = true
        warmup(image, output.resolve("warmup"), workers, workload)
        JvmFleetDiagnostics.beginPopulation()
        memoryPhase("baseline", null, selfGc)
        runFleet(population, workers, image, output, selfGc, workload)
        memoryPhase("closed", null, selfGc)
        if (JvmFleetDiagnostics.trimAllocator()) memoryPhase("trimmed", null, selfGc)
        println("COMPLETE\t$population\t$workers")
    }

    private fun runFleet(
        population: Int,
        workers: Int,
        image: VmSystemImage,
        output: Path,
        selfGc: Boolean,
        workload: ComputeWorkload,
    ) {
        Fleet(population, workers, image, output.resolve("fleet"), workload).use { fleet ->
            fleet.boot()
            memoryPhase("prompt", fleet, selfGc)
            fleet.measureIdle()
            fleet.measureBurst()
            fleet.measureCompute(population, "compute") { memoryPhase("compute", fleet, selfGc) }
            fleet.measureCompute((population + 9) / 10, "mixed") { memoryPhase("mixed", fleet, selfGc) }
            memoryPhase("resumed", fleet, selfGc)
            fleet.writeReports(output)
        }
    }

    private fun warmup(
        image: VmSystemImage,
        root: Path,
        workers: Int,
        workload: ComputeWorkload,
    ) {
        Fleet(16, workers, image, root, workload).use { fleet ->
            fleet.boot()
            fleet.measureIdle()
            fleet.measureBurst()
            fleet.measureCompute(16, "warmup") {}
        }
    }

    private fun memoryPhase(
        name: String,
        fleet: Fleet?,
        selfGc: Boolean,
    ) {
        println("PHASE\t$name\t${memoryCounters(fleet)}")
        System.out.flush()
        if (selfGc) {
            System.gc()
        } else {
            check(readlnOrNull() == "continue") { "Controller disconnected at $name" }
        }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        println("HEAP\t$name\t${heap.used}\t${heap.committed}")
        JvmFleetDiagnostics.memory(name)
        System.out.flush()
    }

    private fun memoryCounters(fleet: Fleet?): String {
        val snapshots = fleet?.resources().orEmpty()
        return listOf(
            snapshots.size.toLong(),
            snapshots.sumOf { it.heapUsedBytes },
            snapshots.sumOf { it.heapCapacityBytes },
            snapshots.sumOf { it.mutableExecutionResidentBytes },
            snapshots.sumOf { it.filesystemLogicalBytes },
            snapshots.sumOf { it.executedInstructions },
        ).joinToString("\t")
    }

    private data class ComputeWorkload(
        val program: String = "/bin/fleet-pid",
        val checksum: Long = 9999999,
    )

    private class Fleet(
        population: Int,
        workers: Int,
        image: VmSystemImage,
        root: Path,
        private val workload: ComputeWorkload,
    ) : AutoCloseable {
        private val owner = Thread.currentThread()
        private val store = WorldFileSystemStore.open(Files.createDirectories(root))
        private val service =
            ProgramRuntimeActorService(
                config = VmActorSchedulerConfig(workerCount = workers),
                perComputerEntitlement = GRANT,
                safeInstructionCapacity = population.toLong() * GRANT,
                capacityFramesRequired = true,
                onResultsReady = { LockSupport.unpark(owner) },
            )
        private var tick = 0L
        private val submitted = LongArray(population)
        private val wakeLatencies = LongArray(population)
        private val frames = ArrayList<String>()
        private val wakes = ArrayList<String>()
        private val checks = ArrayList<String>()
        private val phases = ArrayList<String>()

        private fun measuredPhase(
            mode: String,
            block: () -> Unit,
        ) {
            val gc = ManagementFactory.getGarbageCollectorMXBeans()
            val beforeCollections = gc.sumOf { it.collectionCount }
            val beforeGcTime = gc.sumOf { it.collectionTime }
            val compile = ManagementFactory.getCompilationMXBean()
            val beforeCompile = compile.totalCompilationTime
            val cpu = processClock.processCpuTime
            val wall = System.nanoTime()
            JvmFleetDiagnostics.measure(mode, workerIds, block)
            phases += "$mode\t${System.nanoTime() - wall}\t${processClock.processCpuTime - cpu}\t" +
                "${gc.sumOf { it.collectionCount } - beforeCollections}\t${gc.sumOf { it.collectionTime } - beforeGcTime}\t" +
                "${compile.totalCompilationTime - beforeCompile}"
        }

        private val carriers =
            List(population) { index ->
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, index + 1L), 1)
                val lease = checkNotNull(service.attachBootable(endpoint, store, image))
                ActorProgramComputer(
                    service,
                    lease,
                    RedstoneHostPort { RedstoneCommitResult.Committed },
                    stateSink = { state ->
                        check(state !is ProgramRuntimeState.Failed) { "Computer $index failed: $state" }
                        if (state == ProgramRuntimeState.WaitingForInput && submitted[index] != 0L && wakeLatencies[index] == 0L) {
                            wakeLatencies[index] = System.nanoTime() - submitted[index]
                        }
                    },
                )
            }
        private val workerIds =
            clock.allThreadIds.filter { clock.getThreadInfo(it)?.threadName?.startsWith("compukters-vm-worker-") == true }

        init {
            check(workerIds.size == workers) { "Unexpected worker population" }
        }

        fun boot() {
            val results = await(carriers.map { it.turnOn() })
            results.forEach { check((it.value as? ProgramRuntimeActorValue.Start)?.result == ProgramStartResult.Started) }
            settle()
            terminalTexts().forEach { check('>' in it) { "Missing shell prompt" } }
            checks += "boot\t${carriers.size}\t${resources().sumOf { it.executedInstructions }}"
        }

        private fun workerCpu(): Long = workerIds.sumOf { clock.getThreadCpuTime(it).also { value -> check(value >= 0) } }

        private fun quiesce(): Pair<Long, Long> {
            val deadline = System.nanoTime() + 60_000_000_000L
            var pumpCpu = 0L
            var pumpWall = 0L
            while (true) {
                val beforeCpu = clock.currentThreadCpuTime
                val before = System.nanoTime()
                val delivered = service.pump(1024, before + 2_000_000)
                pumpWall += System.nanoTime() - before
                pumpCpu += clock.currentThreadCpuTime - beforeCpu
                if (service.isQuiescent()) return pumpCpu to pumpWall
                check(System.nanoTime() < deadline) { "Actor population did not quiesce: ${service.runtimeMetrics()}" }
                if (delivered == 0) LockSupport.parkNanos(100_000)
            }
        }

        private fun <T> await(futures: List<CompletableFuture<T>>): List<T> {
            val deadline = System.nanoTime() + 60_000_000_000L
            do {
                quiesce()
                if (futures.all { it.isDone }) return futures.map { it.join() }
                check(System.nanoTime() < deadline) { "Deferred lifecycle did not complete" }
                // A busy store defers carrier lifecycle work to its next ordinary tick.
                // Waiting on that future without ticking would deadlock only this probe.
                service.beginCapacityFrame(++tick)
                carriers.forEach { it.serverTick(tick) }
                service.flushCapacityFrame()
                println("LIFECYCLE_RETRY\t$tick")
            } while (true)
        }

        private fun sweep(
            mode: String? = null,
            sample: Int = 0,
        ) {
            val before = service.retiredInstructionCount()
            val active = carriers.count { it.state == ProgramRuntimeState.Running }
            val workerStart = workerCpu()
            val ownerStart = clock.currentThreadCpuTime
            val started = System.nanoTime()
            val submitStart = clock.currentThreadCpuTime
            service.beginCapacityFrame(++tick)
            carriers.forEach { it.serverTick(tick) }
            service.flushCapacityFrame()
            val submitCpu = clock.currentThreadCpuTime - submitStart
            val submitWall = System.nanoTime() - started
            val pump = if (active > 0) quiesce() else 0L to 0L
            val wall = System.nanoTime() - started
            val ownerCpu = clock.currentThreadCpuTime - ownerStart
            val workersCpu = workerCpu() - workerStart
            val retired = service.retiredInstructionCount() - before
            if (mode != null) {
                frames +=
                    "$mode\t$sample\t$active\t$wall\t$submitWall\t$submitCpu\t${pump.second}\t${pump.first}\t$ownerCpu\t$workersCpu\t$retired"
            }
        }

        private fun settle() {
            repeat(10_000) {
                check(carriers.all { it.state == ProgramRuntimeState.Running || it.state == ProgramRuntimeState.WaitingForInput })
                if (carriers.all { it.state == ProgramRuntimeState.WaitingForInput }) return
                sweep()
            }
            error("Shell population failed to settle")
        }

        fun measureIdle() {
            repeat(WARMUP_FRAMES) { sweep() }
            val before = service.metrics()
            val instructions = resources().sumOf { it.executedInstructions }
            measuredPhase("idle") { repeat(MEASURED_FRAMES) { sweep("idle", it) } }
            val after = service.metrics()
            check(after.acceptedPermits == before.acceptedPermits)
            check(after.processedPermits == before.processedPermits)
            check(resources().sumOf { it.executedInstructions } == instructions)
            checks += "idle-zero-permits\t${carriers.size}\t0"
        }

        private fun lines(
            indices: List<Int>,
            text: (Int) -> String,
        ) {
            val futures =
                indices.map { index ->
                    carriers[index].request { id -> ProgramRuntimeActorCommand.SubmitCanonicalLine(id, text(index).toCharArray()) }
                }
            await(futures).forEach { check((it.value as ProgramRuntimeActorValue.Accepted).accepted) }
        }

        fun measureBurst() {
            measuredPhase("burst") {
                repeat(WAVES) { wave ->
                    val start = System.nanoTime()
                    val futures =
                        carriers.mapIndexed { index, carrier ->
                            submitted[index] = System.nanoTime()
                            wakeLatencies[index] = 0
                            carrier.request { id ->
                                ProgramRuntimeActorCommand.SubmitCanonicalLine(
                                    id,
                                    "echo w$wave-v${index.toString().padStart(4, '0')}".toCharArray(),
                                )
                            }
                        }
                    await(futures).forEach { check((it.value as ProgramRuntimeActorValue.Accepted).accepted) }
                    var frame = 0
                    while (carriers.any { it.state == ProgramRuntimeState.Running }) {
                        check(frame < 1000) { "Wakeup wave did not settle" }
                        sweep("burst-$wave", frame++)
                    }
                    check(carriers.all { it.state == ProgramRuntimeState.WaitingForInput })
                    check(wakeLatencies.all { it > 0 })
                    val wall = System.nanoTime() - start
                    wakeLatencies.forEachIndexed { index, latency -> wakes += "$wave\t$index\t$latency\t$wall" }
                    submitted.fill(0)
                }
            }
            terminalTexts().forEachIndexed { index, text ->
                check("w${WAVES - 1}-v${index.toString().padStart(4, '0')}" in text) { "Wrong private shell output for $index" }
            }
            checks += "distinct-burst-echo\t${carriers.size}\t$WAVES"
        }

        fun measureCompute(
            count: Int,
            mode: String,
            memory: () -> Unit,
        ) {
            val indices = (0 until count).toList()
            lines(indices) { workload.program }
            repeat(WARMUP_FRAMES) { sweep() }
            check(indices.all { carriers[it].state == ProgramRuntimeState.Running })
            measureComputeFrames(count, mode)
            memory()
            settle()
            terminalTexts(indices).forEach { check(workload.checksum.toString() in it) { "Missing ${workload.program} checksum" } }
            checks += "$mode-checksum\t$count\t${workload.checksum}"
        }

        private fun measureComputeFrames(
            count: Int,
            mode: String,
        ) {
            val before = resources()
            measuredPhase(mode) { repeat(MEASURED_FRAMES) { sweep(mode, it) } }
            val after = resources()
            before.indices.forEach { index ->
                val expected = if (index < count) GRANT.toLong() * MEASURED_FRAMES else 0L
                check(after[index].executedInstructions - before[index].executedInstructions == expected) {
                    "Unequal progress: $mode computer $index"
                }
            }
            checks += "$mode-equal-progress\t$count\t${GRANT.toLong() * MEASURED_FRAMES}"
        }

        fun resources(): List<ProgramResourceSnapshot.Available> =
            await(carriers.map(ActorProgramComputer::resourceSnapshot)).map {
                (it.value as ProgramRuntimeActorValue.ResourceSnapshotValue).snapshot as ProgramResourceSnapshot.Available
            }

        private fun terminalTexts(indices: List<Int> = carriers.indices.toList()): List<String> =
            await(indices.map { carriers[it].request(ProgramRuntimeActorCommand::TerminalFullState) }).map { reply ->
                val state = checkNotNull((reply.value as ProgramRuntimeActorValue.TerminalStateValue).state)
                state.cells.joinToString("") { it.codePoint.toChar().toString() }
            }

        fun writeReports(output: Path) {
            Files.writeString(
                output.resolve("frames.tsv"),
                "mode\tsample\tactive\tcompletion_wall_ns\tsubmit_wall_ns\tsubmit_cpu_ns\tpump_wall_ns\t" +
                    "pump_cpu_ns\towner_cpu_ns\tworker_cpu_ns\tretired\n" +
                    frames.joinToString("\n", postfix = "\n"),
            )
            Files.writeString(
                output.resolve("wakes.tsv"),
                "wave\tcomputer\tlatency_ns\twave_wall_ns\n" + wakes.joinToString("\n", postfix = "\n"),
            )
            Files.writeString(output.resolve("checks.tsv"), "check\tcomputers\tvalue\n" + checks.joinToString("\n", postfix = "\n"))
            Files.writeString(
                output.resolve("phases.tsv"),
                "mode\twall_ns\tprocess_cpu_ns\tgc_count\tgc_time_ms\tcompilation_time_ms\n" + phases.joinToString("\n", postfix = "\n"),
            )
            val metrics = service.metrics()
            check(metrics.mailboxFullRejections == 0L && metrics.permitPendingRejections == 0L && metrics.staleEndpointRejections == 0L)
            check(metrics.registeredActors == carriers.size && metrics.scheduledActors == 0 && metrics.queuedResults == 0)
        }

        override fun close() {
            val futures = carriers.map(ActorProgramComputer::closeAsync)
            await(futures)
            check(service.metrics().registeredActors == 0)
            service.close()
            store.close()
        }
    }
}
