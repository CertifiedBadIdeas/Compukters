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

package ru.lazyhat.compukterkraft.common.computer.client.retained

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayInstallDamage
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedCompiledCommand
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedCompiledPresentation
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedDisplayGeometryCompiler
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedGeometryBatch
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedInstanceChunkKey
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedInstanceSpan

fun interface MinecraftRetainedBatchTargetFactory {
    fun create(batch: RetainedGeometryBatch): MinecraftRetainedBatchTarget
}

interface MinecraftRetainedBatchTarget : AutoCloseable {
    fun draw(
        modelView: Matrix4f,
        projection: Matrix4f,
    )
}

fun interface MinecraftRetainedBatchSubmitter {
    fun submit(
        target: MinecraftRetainedBatchTarget,
        translationX: Int,
        translationY: Int,
    )
}

class MinecraftRetainedNativePresentation internal constructor(
    private val drawCalls: List<DrawCall>,
    private val metrics: RetainedDisplayRenderMetrics,
) {
    fun submit(submitter: MinecraftRetainedBatchSubmitter) {
        metrics.recordFrameSubmission()
        var index = 0
        while (index < drawCalls.size) {
            val call = drawCalls[index]
            submitter.submit(call.target, call.translationX, call.translationY)
            index += 1
        }
    }

    internal data class DrawCall(
        val target: MinecraftRetainedBatchTarget,
        val translationX: Int,
        val translationY: Int,
    )
}

class MinecraftRetainedBatchCache(
    private val targetFactory: MinecraftRetainedBatchTargetFactory,
    private val metrics: RetainedDisplayRenderMetrics,
) : AutoCloseable {
    private var logicalPresentation: RetainedCompiledPresentation? = null
    private var nativePresentation: MinecraftRetainedNativePresentation? = null
    private var targets = linkedMapOf<BatchKey, TargetRecord>()

    fun install(
        state: RetainedDisplayState,
        damage: RetainedDisplayInstallDamage,
    ) {
        val previous = logicalPresentation
        val compiled =
            if (previous == null) {
                RetainedDisplayGeometryCompiler.compile(state)
            } else {
                RetainedDisplayGeometryCompiler.update(previous, state, damage)
            }
        recordCompilationMetrics(previous, compiled)
        val descriptors = descriptors(compiled)
        val reconciled = reconcile(descriptors)
        logicalPresentation = compiled
        targets = reconciled
        nativePresentation =
            MinecraftRetainedNativePresentation(
                descriptors.map { descriptor ->
                    MinecraftRetainedNativePresentation.DrawCall(
                        reconciled.getValue(descriptor.key).target,
                        descriptor.translationX,
                        descriptor.translationY,
                    )
                },
                metrics,
            )
    }

    fun presentation(): MinecraftRetainedNativePresentation =
        checkNotNull(nativePresentation) { "Retained batch presentation is not installed" }

    fun invalidate() {
        releaseAll()
        logicalPresentation = null
        nativePresentation = null
    }

    override fun close() {
        invalidate()
    }

    private fun recordCompilationMetrics(
        previous: RetainedCompiledPresentation?,
        compiled: RetainedCompiledPresentation,
    ) {
        for ((key, chunk) in compiled.instanceChunks) {
            if (previous?.instanceChunks?.get(key) !== chunk) metrics.recordInstanceChunkCompilation()
        }
        compiled.commands.forEachIndexed { commandIndex, command ->
            if (command !is RetainedCompiledCommand.InstanceRange) return@forEachIndexed
            val previousCommand = previous?.commands?.getOrNull(commandIndex) as? RetainedCompiledCommand.InstanceRange
            command.spans.forEachIndexed { spanIndex, span ->
                if (span is RetainedInstanceSpan.Direct && previousCommand?.spans?.getOrNull(spanIndex) !== span) {
                    metrics.recordBoundaryFragmentCompilation()
                }
            }
        }
    }

    private fun reconcile(descriptors: List<BatchDescriptor>): LinkedHashMap<BatchKey, TargetRecord> {
        val requested = linkedMapOf<BatchKey, RetainedGeometryBatch>()
        for (descriptor in descriptors) {
            val existing = requested.putIfAbsent(descriptor.key, descriptor.batch)
            check(existing == null || existing == descriptor.batch) {
                "Retained batch key resolved to different geometry: ${descriptor.key}"
            }
        }

        val next = linkedMapOf<BatchKey, TargetRecord>()
        val created = mutableListOf<TargetRecord>()
        try {
            for ((key, batch) in requested) {
                val current = targets[key]
                val record =
                    if (current != null && current.batch == batch) {
                        current
                    } else {
                        TargetRecord(batch, targetFactory.create(batch)).also {
                            created += it
                            metrics.recordBatchCreation()
                        }
                    }
                next[key] = record
            }
        } catch (failure: Throwable) {
            created.forEach(::release)
            throw failure
        }

        for ((key, record) in targets) {
            if (next[key] !== record) release(record)
        }
        return next
    }

    private fun releaseAll() {
        val releasing = targets.values.toList()
        targets.clear()
        releasing.forEach(::release)
    }

    private fun release(record: TargetRecord) {
        record.target.close()
        metrics.recordBatchRelease()
    }

    private fun descriptors(presentation: RetainedCompiledPresentation): List<BatchDescriptor> =
        buildList {
            add(BatchDescriptor(BatchKey.Background, presentation.background, 0, 0))
            presentation.commands.forEachIndexed { commandIndex, command ->
                when (command) {
                    is RetainedCompiledCommand.Direct -> {
                        command.batches.forEachIndexed { batchIndex, batch ->
                            add(BatchDescriptor(BatchKey.Direct(commandIndex, batchIndex), batch, 0, 0))
                        }
                    }

                    is RetainedCompiledCommand.InstanceRange -> {
                        command.spans.forEachIndexed { spanIndex, span ->
                            when (span) {
                                is RetainedInstanceSpan.Cached -> {
                                    val chunk = presentation.instanceChunks.getValue(span.key)
                                    chunk.batches.forEachIndexed { batchIndex, batch ->
                                        add(
                                            BatchDescriptor(
                                                BatchKey.Chunk(span.key, batchIndex),
                                                batch,
                                                span.translationX,
                                                span.translationY,
                                            ),
                                        )
                                    }
                                }

                                is RetainedInstanceSpan.Direct -> {
                                    span.batches.forEachIndexed { batchIndex, batch ->
                                        add(
                                            BatchDescriptor(
                                                BatchKey.Boundary(commandIndex, spanIndex, batchIndex),
                                                batch,
                                                0,
                                                0,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private sealed interface BatchKey {
        data object Background : BatchKey

        data class Direct(
            val commandIndex: Int,
            val batchIndex: Int,
        ) : BatchKey

        data class Chunk(
            val chunk: RetainedInstanceChunkKey,
            val batchIndex: Int,
        ) : BatchKey

        data class Boundary(
            val commandIndex: Int,
            val spanIndex: Int,
            val batchIndex: Int,
        ) : BatchKey
    }

    private data class BatchDescriptor(
        val key: BatchKey,
        val batch: RetainedGeometryBatch,
        val translationX: Int,
        val translationY: Int,
    )

    private data class TargetRecord(
        val batch: RetainedGeometryBatch,
        val target: MinecraftRetainedBatchTarget,
    )
}

class NativeVertexBufferRetainedBatchTargetFactory(
    private val textureLocation: (Long) -> ResourceLocation,
) : MinecraftRetainedBatchTargetFactory {
    constructor(textureCache: MinecraftRetainedTextureCache) : this(
        { identity ->
            textureCache.texture(identity)?.location
                ?: error("Retained batch texture is not installed: $identity")
        },
    )

    override fun create(batch: RetainedGeometryBatch): MinecraftRetainedBatchTarget {
        RenderSystem.assertOnRenderThread()
        val texture = batch.textureIdentity?.let(textureLocation)
        val format = if (texture == null) DefaultVertexFormat.POSITION_COLOR else DefaultVertexFormat.POSITION_TEX_COLOR
        val byteBuffer = ByteBufferBuilder(format.vertexSize * batch.quads.size * VERTICES_PER_QUAD)
        try {
            val builder = BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, format)
            for (quad in batch.quads) appendQuad(builder, quad.destination, quad.sourceUv, quad.argb)
            val vertexBuffer = VertexBuffer(VertexBuffer.Usage.STATIC)
            try {
                builder.buildOrThrow().use(vertexBuffer::upload)
            } catch (failure: Throwable) {
                vertexBuffer.close()
                throw failure
            }
            return NativeVertexBufferRetainedBatchTarget(vertexBuffer, texture)
        } finally {
            byteBuffer.close()
        }
    }

    private companion object {
        const val VERTICES_PER_QUAD = 4

        fun appendQuad(
            builder: BufferBuilder,
            destination: ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedFloatRect,
            sourceUv: ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedFloatRect?,
            argb: Int,
        ) {
            val left = destination.x
            val top = destination.y
            val right = left + destination.width
            val bottom = top + destination.height
            if (sourceUv == null) {
                builder.addVertex(left, bottom, 0f).setColor(argb)
                builder.addVertex(right, bottom, 0f).setColor(argb)
                builder.addVertex(right, top, 0f).setColor(argb)
                builder.addVertex(left, top, 0f).setColor(argb)
            } else {
                val u0 = sourceUv.x
                val v0 = sourceUv.y
                val u1 = u0 + sourceUv.width
                val v1 = v0 + sourceUv.height
                builder.addVertex(left, bottom, 0f).setUv(u0, v1).setColor(argb)
                builder.addVertex(right, bottom, 0f).setUv(u1, v1).setColor(argb)
                builder.addVertex(right, top, 0f).setUv(u1, v0).setColor(argb)
                builder.addVertex(left, top, 0f).setUv(u0, v0).setColor(argb)
            }
        }
    }
}

private class NativeVertexBufferRetainedBatchTarget(
    private val vertexBuffer: VertexBuffer,
    private val texture: ResourceLocation?,
) : MinecraftRetainedBatchTarget {
    override fun draw(
        modelView: Matrix4f,
        projection: Matrix4f,
    ) {
        val shader =
            if (texture == null) {
                GameRenderer.getPositionColorShader()
            } else {
                RenderSystem.setShaderTexture(0, texture)
                GameRenderer.getPositionTexColorShader()
            }
        vertexBuffer.bind()
        vertexBuffer.drawWithShader(modelView, projection, checkNotNull(shader) { "Retained batch shader is not loaded" })
        VertexBuffer.unbind()
    }

    override fun close() {
        vertexBuffer.close()
    }
}
