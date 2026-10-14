/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import com.sun.management.ThreadMXBean
import ru.lazyhat.compukters.core.display.DisplayCanvas
import java.io.File
import java.lang.management.ManagementFactory
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

/** Opt-in host microbenchmark. Batch percentiles are not click-to-photon latency percentiles. */
object DisplayPipelineBenchmark {
    @Volatile
    private var sink: Any? = null
    private const val SAMPLES = 31
    private const val WARMUP_NS = 250_000_000L

    private class Fixture(
        val side: Int,
        val mode: Int,
    ) {
        val canvas = DisplayCanvas(side, side, mode)
        val owner = Any()
        val density = canvas.density
        val count = side * side
        val id = UUID(0, 1).toString()
        val positions =
            (0 until count).map { index ->
                ((128L + index % side) shl 38) or (128L shl 12) or (64L - index / side)
            }
        val members = positions.joinToString(",")
        val images = positions.associateWith { DisplayClientImage() }
        val incomplete = positions.associateWith { DisplayClientImage() }
        val tiles: List<ByteArray>
        var sequence = 0L
        var iteration = 0

        init {
            canvas.acquire(owner) { true }
            canvas.fill(owner, 0, 0, canvas.width, canvas.height, 0x102030)
            tiles = tiles()
            positions.dropLast(1).forEachIndexed { index, position ->
                incomplete.getValue(position).receive(mode, tiles[index], id, 1, members)
            }
        }

        fun tiles(): List<ByteArray> = (0 until count).map { canvas.tile(it % side, it / side) }

        fun button() {
            canvas.begin(owner)
            iteration++
            canvas.fill(owner, 0, 0, min(40, canvas.width), min(16, canvas.height), iteration and 1)
            DisplayRasterFont.draw(canvas, owner, 1, 1, if (iteration and 1 == 0) "RUN" else "STOP", 0xFFFFFF, 1)
            canvas.finish(owner, true)
            sink = canvas.revision
        }

        fun dashboard() {
            canvas.begin(owner)
            canvas.fill(owner, 0, 0, canvas.width, canvas.height, 0x101820)
            for (index in 0 until 8) {
                val y = index * 16
                canvas.fill(owner, 0, y, 40, 14, 0x183050)
                DisplayRasterFont.draw(canvas, owner, 1, y + 1, "RPM 128", 0xFFFFFF, 1)
            }
            for (index in 1..64) {
                canvas.line(owner, index - 1, 16 + (index - 1) % 12, index, 16 + index % 12, 0x00FF80)
            }
            canvas.finish(owner, true)
            sink = canvas.revision
        }

        fun receivePublish() {
            val publication = ++sequence
            positions.forEachIndexed { index, position ->
                images.getValue(position).receive(mode, tiles[index], id, publication, members)
            }
            images.values.forEach { it.publish(images::get) }
            sink = images.values.first().revision
        }

        fun incompletePass() {
            incomplete.values.forEach { it.publish(incomplete::get) }
            sink = incomplete.values.first().revision
        }

        // Exact current admission charge, with one fully loaded screen and no competing displays.
        // This is a deterministic scheduling model, not a network/latency measurement.
        fun syncTicks(): Int {
            val charge = density * density * 3 + members.length * 2 + 128
            val perTick = (512 * 1024) / charge
            return (count + perTick - 1) / perTick
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val output = File(args.single()).apply { mkdirs() }
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true) bean.isThreadAllocatedMemoryEnabled = true
        val allocation = bean?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        val thread = Thread.currentThread().threadId()
        val csv =
            StringBuilder(
                "side,density,panels,stage,batch,p50_us_per_op,p95_us_per_op,allocated_bytes_per_op,rgb_bytes_per_publication,modeled_sync_ticks,status\n",
            )
        val metadata =
            buildString {
                appendLine("Display host pipeline benchmark; no Guest VM, network transport, GPU upload or presentation timing.")
                appendLine("One invocation is one JVM fork. Run at least twice on an idle machine; keep both reports.")
                appendLine(
                    "Samples=$SAMPLES; warmup per case=${WARMUP_NS / 1_000_000}ms; percentiles of batch means, not individual event latencies.",
                )
                appendLine("Java=${System.getProperty("java.runtime.version")}; VM=${System.getProperty("java.vm.name")}")
                appendLine(
                    "OS=${System.getProperty(
                        "os.name",
                    )} ${System.getProperty("os.arch")}; CPUs=${Runtime.getRuntime().availableProcessors()}",
                )
                appendLine(
                    "CPU=" +
                        File("/proc/cpuinfo").takeIf { it.isFile }?.useLines { lines ->
                            lines.firstOrNull { it.startsWith("model name") }
                        },
                )
                appendLine("JVM arguments=${ManagementFactory.getRuntimeMXBean().inputArguments}")
                appendLine("Sync ticks model: DisplayWorldAccess 512KiB/tick admission, full loaded membership, full RGB per panel.")
                appendLine(
                    "Excludes NBT/compression/framing overhead; no competing screens; does not model tick phase or Guest scheduling.",
                )
                appendLine("Only canvas memory budgets apply to drawing here; the server world draw budget is not exercised.")
            }
        File(output, "environment.txt").writeText(metadata)
        println(metadata)
        for (side in listOf(1, 3, 8)) {
            for (mode in DisplayCanvas.DENSITIES.indices) {
                val fixture = Fixture(side, mode)
                val pixels = fixture.canvas.width * fixture.canvas.height
                val batch = (524288 / pixels).coerceIn(1, 128)
                val stages =
                    linkedMapOf<String, (() -> Unit)?>(
                        "pointer_offer_poll" to {
                            check(fixture.canvas.offerClick(1, 1))
                            sink = checkNotNull(fixture.canvas.pollClick(fixture.owner))
                        },
                        "frame_button" to if (pixels <= DisplayCanvas.MAXIMUM_FRAME_PIXELS) fixture::button else null,
                        "frame_dashboard" to if (pixels <= DisplayCanvas.MAXIMUM_FRAME_PIXELS) fixture::dashboard else null,
                        "rgb_tiles" to { sink = fixture.tiles() },
                        "client_receive_publish" to fixture::receivePublish,
                        "client_incomplete_pass" to if (fixture.count > 1) fixture::incompletePass else null,
                    )
                for ((stage, operation) in stages) {
                    val prefix = "$side,${fixture.density},${fixture.count},$stage,$batch"
                    val suffix = "${pixels * 3},${fixture.syncTicks()}"
                    if (operation == null) {
                        csv.appendLine("$prefix,,,,$suffix,unsupported")
                        continue
                    }
                    val warmupEnd = System.nanoTime() + WARMUP_NS
                    while (System.nanoTime() < warmupEnd) repeat(batch) { operation() }
                    val elapsed = LongArray(SAMPLES)
                    var allocated = 0L
                    repeat(SAMPLES) { sample ->
                        val before = allocation?.getThreadAllocatedBytes(thread) ?: 0
                        val start = System.nanoTime()
                        repeat(batch) { operation() }
                        elapsed[sample] = System.nanoTime() - start
                        allocated += (allocation?.getThreadAllocatedBytes(thread) ?: 0) - before
                    }
                    elapsed.sort()
                    val p50 = elapsed[SAMPLES / 2].toDouble() / batch / 1000
                    val p95 = elapsed[ceil(SAMPLES * 0.95).toInt() - 1].toDouble() / batch / 1000
                    val bytes = if (allocation == null) "" else (allocated.toDouble() / SAMPLES / batch).toString()
                    val row = "$prefix,$p50,$p95,$bytes,$suffix,measured"
                    csv.appendLine(row)
                    println(row)
                }
            }
        }
        File(output, "results.csv").writeText(csv.toString())
        check(sink != null)
        println("Report: ${output.absolutePath}")
    }
}
