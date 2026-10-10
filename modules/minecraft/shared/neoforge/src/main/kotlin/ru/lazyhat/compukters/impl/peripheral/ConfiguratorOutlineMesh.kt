/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.phys.AABB
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOutline
import kotlin.math.sin

/** Solid thin edge ribbons rather than hardware lines: stable width and normal depth testing. */
internal object ConfiguratorOutlineMesh {
    fun draw(
        pose: PoseStack.Pose,
        vertices: VertexConsumer,
        outlines: List<ConfiguratorOutline>,
        time: Double,
    ) {
        outlines.forEach { outline ->
            val pulse = (sin(time * 0.11) + 1.0) * 0.5
            val alpha = if (outline.strong) (190 + pulse * 55).toInt() else (85 + pulse * 25).toInt()
            val color = alpha shl 24 or outline.color
            val width = if (outline.strong) 0.018 else 0.009
            val b = outline.bounds

            fun edge(
                axis: Int,
                start: Double,
                end: Double,
                a: Double,
                c: Double,
            ) {
                if (end - start < 0.03) return
                val parts = if (outline.dashed) kotlin.math.ceil((end - start) / 0.2).toInt() else 1
                repeat(parts) { index ->
                    val from = start + (end - start) * index / parts
                    val to = start + (end - start) * (index + if (outline.dashed) 0.6 else 1.0) / parts
                    val box =
                        when (axis) {
                            0 -> AABB(from, a - width, c - width, to, a + width, c + width)
                            1 -> AABB(a - width, from, c - width, a + width, to, c + width)
                            else -> AABB(a - width, c - width, from, a + width, c + width, to)
                        }
                    cuboid(pose, vertices, box, color)
                }
            }

            fun sides(
                minimum: Double,
                maximum: Double,
            ): List<Double> = if (maximum - minimum < 0.03) listOf((minimum + maximum) * 0.5) else listOf(minimum, maximum)
            for (y in sides(b.minY, b.maxY)) for (z in sides(b.minZ, b.maxZ)) edge(0, b.minX, b.maxX, y, z)
            for (x in sides(b.minX, b.maxX)) for (z in sides(b.minZ, b.maxZ)) edge(1, b.minY, b.maxY, x, z)
            for (x in sides(b.minX, b.maxX)) for (y in sides(b.minY, b.maxY)) edge(2, b.minZ, b.maxZ, x, y)
        }
    }

    private fun cuboid(
        pose: PoseStack.Pose,
        vertices: VertexConsumer,
        b: AABB,
        color: Int,
    ) {
        fun vertex(
            x: Double,
            y: Double,
            z: Double,
            u: Float,
            v: Float,
            nx: Float,
            ny: Float,
            nz: Float,
        ) {
            vertices
                .addVertex(pose, x.toFloat(), y.toFloat(), z.toFloat())
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0)
                .setNormal(pose, nx, ny, nz)
        }

        fun face(
            points: Array<DoubleArray>,
            nx: Float,
            ny: Float,
            nz: Float,
        ) {
            points.forEachIndexed { i, p -> vertex(p[0], p[1], p[2], if (i < 2) 0f else 1f, if (i == 0 || i == 3) 0f else 1f, nx, ny, nz) }
        }
        val x = b.minX
        val maxX = b.maxX
        val y = b.minY
        val maxY = b.maxY
        val z = b.minZ
        val maxZ = b.maxZ
        face(
            arrayOf(doubleArrayOf(x, maxY, maxZ), doubleArrayOf(x, y, maxZ), doubleArrayOf(maxX, y, maxZ), doubleArrayOf(maxX, maxY, maxZ)),
            0f,
            0f,
            1f,
        )
        face(
            arrayOf(doubleArrayOf(maxX, maxY, z), doubleArrayOf(maxX, y, z), doubleArrayOf(x, y, z), doubleArrayOf(x, maxY, z)),
            0f,
            0f,
            -1f,
        )
        face(
            arrayOf(doubleArrayOf(maxX, maxY, maxZ), doubleArrayOf(maxX, y, maxZ), doubleArrayOf(maxX, y, z), doubleArrayOf(maxX, maxY, z)),
            1f,
            0f,
            0f,
        )
        face(
            arrayOf(doubleArrayOf(x, maxY, z), doubleArrayOf(x, y, z), doubleArrayOf(x, y, maxZ), doubleArrayOf(x, maxY, maxZ)),
            -1f,
            0f,
            0f,
        )
        face(
            arrayOf(doubleArrayOf(x, maxY, z), doubleArrayOf(x, maxY, maxZ), doubleArrayOf(maxX, maxY, maxZ), doubleArrayOf(maxX, maxY, z)),
            0f,
            1f,
            0f,
        )
        face(
            arrayOf(doubleArrayOf(x, y, maxZ), doubleArrayOf(x, y, z), doubleArrayOf(maxX, y, z), doubleArrayOf(maxX, y, maxZ)),
            0f,
            -1f,
            0f,
        )
    }
}
