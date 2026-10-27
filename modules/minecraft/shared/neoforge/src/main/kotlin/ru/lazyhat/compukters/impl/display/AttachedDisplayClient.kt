/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.core.display.DisplayFace
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayMessage
import ru.lazyhat.compukters.minecraft.display.DisplayClientImage
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
import java.util.UUID

/** Client publications use the same image barrier and texture backend as physical display blocks. */
internal object AttachedDisplayClient {
    private class Tile(
        val member: AttachedDisplayMessage.Member,
        val image: DisplayClientImage = DisplayClientImage(),
    )

    private class Surface(
        var manifest: AttachedDisplayMessage.Manifest,
        var tiles: Map<UUID, Tile>,
        var requested: Long = -100,
    )

    private var level: ClientLevel? = null
    private val surfaces = linkedMapOf<UUID, Surface>()

    private fun context(): ClientLevel? {
        val current = Minecraft.getInstance().level
        if (level !== current) {
            surfaces.clear()
            level = current
        }
        return current
    }

    fun contains(hit: net.minecraft.world.phys.BlockHitResult): Boolean {
        context() ?: return false
        return surfaces.values.any { surface ->
            ru.lazyhat.compukters.minecraft.display.AttachedDisplaySurfaces.facings[surface.manifest.facing] == hit.direction &&
                surface.tiles.values.any { it.member.position == hit.blockPos }
        }
    }

    fun receive(message: AttachedDisplayMessage) {
        context() ?: return
        when (message) {
            is AttachedDisplayMessage.Remove -> {
                surfaces.remove(message.surface)
            }

            is AttachedDisplayMessage.Manifest -> {
                val old = surfaces[message.surface]
                if (old != null && message.publication < old.manifest.publication) return
                if (surfaces.filterKeys { it != message.surface }.values.sumOf { it.tiles.size } + message.members.size > 1024) return
                val tiles =
                    message.members.associate { member ->
                        member.instance to
                            (old?.tiles?.get(member.instance)?.takeIf { it.member == member } ?: Tile(member))
                    }
                if (old == null) {
                    surfaces[message.surface] = Surface(message, tiles)
                } else {
                    old.manifest = message
                    old.tiles = tiles
                }
            }

            is AttachedDisplayMessage.Tile -> {
                val surface = surfaces[message.surface] ?: return
                if (message.publication != surface.manifest.publication) return
                val tile = surface.tiles[message.instance] ?: return
                tile.image.receive(
                    surface.manifest.mode,
                    message.rgb,
                    message.surface.toString(),
                    message.publication,
                    surface.tiles.values
                        .map { it.member.position.asLong() }
                        .sorted()
                        .joinToString(","),
                    message.base,
                    message.x,
                    message.y,
                    message.width,
                    message.height,
                )
            }
        }
    }

    fun tick() {
        val current = context() ?: return
        val tick = current.gameTime
        val missing =
            surfaces.entries.firstOrNull { (_, surface) ->
                surface.tiles.values.any { it.image.needsSnapshot } &&
                    tick - surface.requested >= 20
            }
        if (missing != null) {
            missing.value.requested = tick
            Minecraft.getInstance().connection?.send(
                net.minecraft.network.protocol.common
                    .ServerboundCustomPayloadPacket(AttachedDisplaySnapshotRequest(missing.key)),
            )
        }
    }

    fun frame(): List<AttachedDisplayQuad> {
        val current = context() ?: return emptyList()
        return surfaces.values.flatMap { surface ->
            val peers = surface.tiles.values.associateBy { it.member.position.asLong() }
            surface.tiles.values.forEach { tile -> tile.image.publish { pos -> peers[pos]?.image } }
            surface.tiles.values.mapNotNull { tile ->
                val image = tile.image
                if (!current.hasChunkAt(tile.member.position)) return@mapNotNull null
                val texture =
                    DisplayTextureCache.texture(tile, image.density, image.revision, image.rgb, {
                        level === current && surfaces[surface.manifest.surface] === surface && surface.tiles[tile.member.instance] === tile
                    }) ?: return@mapNotNull null
                val facing = surface.manifest.facing
                val n = DisplayFace.normal(facing).let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                val r = DisplayFace.right(facing).let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                val d = DisplayFace.down(facing).let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                val corner =
                    tile.member.position.center
                        .add(n.scale(0.503))
                        .subtract(r.scale(0.5))
                        .subtract(d.scale(0.5))
                val points =
                    listOf(corner, corner.add(d), corner.add(d).add(r), corner.add(r)).map {
                        PeripheralWorldPositions.project(current, it)
                            ?: return@mapNotNull null
                    }
                val normal =
                    points[3]
                        .subtract(points[0])
                        .cross(points[1].subtract(points[0]))
                        .scale(-1.0)
                        .normalize()
                AttachedDisplayQuad(texture, points, normal, surface.manifest.opacity)
            }
        }
    }
}

internal data class AttachedDisplayQuad(
    val texture: ru.lazyhat.compukters.impl.compat.Identifier,
    val points: List<Vec3>,
    val normal: Vec3,
    val opacity: Int,
)

internal object AttachedDisplayMesh {
    fun draw(
        pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
        vertices: com.mojang.blaze3d.vertex.VertexConsumer,
        quad: AttachedDisplayQuad,
        camera: Vec3,
    ) {
        quad.points.forEachIndexed { i, world ->
            val p = world.subtract(camera)
            vertices
                .addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .setColor((quad.opacity shl 24) or 0xFFFFFF)
                .setUv(if (i < 2) 0f else 1f, if (i == 0 || i == 3) 0f else 1f)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0)
                .setNormal(pose, quad.normal.x.toFloat(), quad.normal.y.toFloat(), quad.normal.z.toFloat())
        }
    }
}
