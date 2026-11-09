/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.core.device.runtime.fleet

import com.sun.management.ThreadMXBean
import jdk.jfr.Recording
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Duration

/** Opt-in observations for the standalone fixture, never used by production computers. */
internal object JvmFleetDiagnostics {
    private val mode = System.getProperty("compukters.fleet.diagnostics")
    private val phase = System.getProperty("compukters.fleet.profilePhase", "compute")
    private val clock = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private var output: Path? = null
    private var populationReady = false
    private val mallocInfo by lazy {
        check(ValueLayout.ADDRESS.byteSize() == 8L) { "mallinfo2 fixture requires a 64-bit glibc host" }
        val layout = MemoryLayout.structLayout(*Array(10) { ValueLayout.JAVA_LONG })
        val linker = Linker.nativeLinker()
        linker.downcallHandle(linker.defaultLookup().find("mallinfo2").orElseThrow(), FunctionDescriptor.of(layout))
    }

    fun initialize(path: Path) {
        require(mode == null || mode in setOf("cpu", "allocation", "memory", "perf"))
        if (mode == null) return
        check(clock.isThreadAllocatedMemorySupported)
        clock.isThreadAllocatedMemoryEnabled = true
        output = path
        if (mode in setOf("cpu", "allocation")) Recording().close() // Initialize JFR before warmup.
        if (mode == "memory") allocatorValues()
        if (mode == "perf") println("PROBE_PID\t${ProcessHandle.current().pid()}")
    }

    fun beginPopulation() {
        populationReady = true
    }

    fun measure(
        name: String,
        workerIds: List<Long>,
        block: () -> Unit,
    ) {
        if (mode == null || !populationReady || name != phase) {
            block()
            return
        }
        val recording = if (mode in setOf("memory", "perf")) null else Recording()
        recording?.apply {
            this.name = "fleet-$mode-$phase"
            if (mode == "cpu") {
                enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1)).withStackTrace()
                enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(1)).withStackTrace()
            } else {
                enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace()
            }
            enable("jdk.GarbageCollection")
            enable("jdk.GCPhasePause")
            start()
        }
        if (mode == "perf") barrier("PROFILE_BEGIN")
        val owner = Thread.currentThread().threadId()
        val beforeOwner = clock.getThreadAllocatedBytes(owner)
        val beforeWorkers = workerIds.sumOf(clock::getThreadAllocatedBytes)
        try {
            block()
        } finally {
            val allocatedOwner = clock.getThreadAllocatedBytes(owner) - beforeOwner
            val allocatedWorkers = workerIds.sumOf(clock::getThreadAllocatedBytes) - beforeWorkers
            check(allocatedOwner >= 0 && allocatedWorkers >= 0)
            if (mode == "perf") barrier("PROFILE_END")
            recording?.use {
                it.stop()
                it.dump(checkNotNull(output).resolve("$phase-$mode.jfr"))
            }
            println("ALLOCATED\t$phase\t$allocatedOwner\t$allocatedWorkers")
        }
    }

    private fun barrier(name: String) {
        println("$name\t$phase")
        System.out.flush()
        check(readlnOrNull() == "continue") { "Profiler controller disconnected" }
    }

    /** glibc process allocator counters; neither JVM NMT totals nor Rust-only live allocation bytes. */
    fun memory(name: String) {
        if (mode != "memory") return
        println("MALLOC\t$name\t${allocatorValues().joinToString("\t")}")
    }

    fun trimAllocator(): Boolean {
        if (mode != "memory") return false
        val linker = Linker.nativeLinker()
        val trim =
            linker.downcallHandle(
                linker.defaultLookup().find("malloc_trim").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
            )
        println("MALLOC_TRIM\t${trim.invoke(0L) as Int}")
        return true
    }

    private fun allocatorValues(): List<Long> =
        Arena.ofConfined().use { arena ->
            val info = mallocInfo.invoke(arena) as MemorySegment
            // glibc struct mallinfo2: arena, ordblks, smblks, hblks, hblkhd, usmblks,
            // fsmblks, uordblks, fordblks, keepcost. Size fields are size_t, not C int.
            listOf(0L, 4L, 7L, 8L).map { info.get(ValueLayout.JAVA_LONG, it * 8) }
        }
}
