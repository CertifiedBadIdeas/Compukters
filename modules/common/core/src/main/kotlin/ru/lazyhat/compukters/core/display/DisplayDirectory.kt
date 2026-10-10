/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Exact block instance occupying one slot; positions alone cannot revive a replacement. */
data class DisplayPanel(
    val column: Int,
    val row: Int,
    val instance: UUID,
)

class DisplaySurface(
    val id: UUID,
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    /** Horizontal facing: north, south, west, east. */
    val facing: Int,
    val canvas: DisplayCanvas,
    panels: List<DisplayPanel>,
    name: String? = null,
) {
    internal val members: MutableList<DisplayPanel> = panels.toMutableList()
    val panels: List<DisplayPanel> get() = members.toList()
    var name: String? = name
        internal set

    init {
        require(facing in 0..3)
        require(panels.isNotEmpty() && panels.size <= canvas.columns * canvas.rows)
        require(panels.distinctBy { it.column to it.row }.size == panels.size)
        require(panels.distinctBy { it.instance }.size == panels.size)
        require(panels.all { it.column in 0 until canvas.columns && it.row in 0 until canvas.rows })
        require(name == null || (name.length in 1..64 && name.none(Char::isISOControl)))
    }
}

/** Saved world authority; holes consume canvas area but do not need installed blocks. */
class DisplayDirectory(
    private val changed: () -> Unit = {},
) {
    private val surfaces = linkedMapOf<UUID, DisplaySurface>()
    private val memberships = hashMapOf<UUID, UUID>()

    fun snapshot(): List<DisplaySurface> = surfaces.values.toList()

    fun byId(id: UUID): DisplaySurface? = surfaces[id]

    fun byPanel(instance: UUID): DisplaySurface? = memberships[instance]?.let(surfaces::get)

    fun create(
        originX: Int,
        originY: Int,
        originZ: Int,
        facing: Int,
        columns: Int,
        rows: Int,
        panels: List<DisplayPanel>,
    ): DisplaySurface {
        check(surfaces.size < MAXIMUM_SCREENS) { "Display count limit reached" }
        require(columns in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE && rows in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE)
        check(snapshot().sumOf { it.canvas.columns * it.canvas.rows } + columns * rows <= MAXIMUM_WORLD_BLOCK_AREA) {
            "World display area limit reached"
        }
        require(panels.all { it.instance !in memberships }) { "Panel already belongs to a display" }
        val canvas = DisplayCanvas(columns, rows, changed = changed)
        val surface = DisplaySurface(UUID.randomUUID(), originX, originY, originZ, facing, canvas, panels)
        surfaces[surface.id] = surface
        panels.forEach { memberships[it.instance] = surface.id }
        changed()
        return surface
    }

    fun join(
        id: UUID,
        panel: DisplayPanel,
    ) {
        val surface = requireNotNull(surfaces[id]) { "Display was deleted" }
        require(panel.instance !in memberships)
        require(panel.column in 0 until surface.canvas.columns && panel.row in 0 until surface.canvas.rows)
        require(surface.members.none { it.column == panel.column && it.row == panel.row }) { "Display slot is occupied" }
        surface.members += panel
        memberships[panel.instance] = id
        changed()
    }

    /** Destruction removes only the panel; last destruction removes the entire canvas. */
    fun remove(instance: UUID): Boolean {
        val id = memberships.remove(instance) ?: return false
        val surface = surfaces.getValue(id)
        surface.members.removeAll { it.instance == instance }
        if (surface.members.isEmpty()) surfaces.remove(id)
        changed()
        return true
    }

    fun rename(
        id: UUID,
        name: String?,
    ) {
        require(name == null || (name.length in 1..64 && name.none(Char::isISOControl)))
        surfaces.getValue(id).name = name
        changed()
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(surfaces.size)
            surfaces.values.forEach { surface ->
                out.writeUuid(surface.id)
                out.writeInt(surface.originX)
                out.writeInt(surface.originY)
                out.writeInt(surface.originZ)
                out.writeInt(surface.facing)
                out.writeInt(surface.canvas.columns)
                out.writeInt(surface.canvas.rows)
                out.writeInt(surface.canvas.mode)
                out.writeUTF(surface.name.orEmpty())
                out.writeInt(surface.members.size)
                surface.members.forEach { panel ->
                    out.writeInt(panel.column)
                    out.writeInt(panel.row)
                    out.writeUuid(panel.instance)
                }
                val rgb = surface.canvas.encodeRgb()
                out.writeInt(rgb.size)
                out.write(rgb)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val FORMAT = 1
        const val MAXIMUM_SCREENS: Int = 128
        const val MAXIMUM_WORLD_BLOCK_AREA: Int = 1024
        const val MAXIMUM_ENCODED_BYTES: Int = MAXIMUM_WORLD_BLOCK_AREA * 128 * 128 * 3 + 128 * 4096

        fun decode(
            bytes: ByteArray,
            changed: () -> Unit = {},
        ): DisplayDirectory {
            require(bytes.size <= MAXIMUM_ENCODED_BYTES)
            val result = DisplayDirectory(changed)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == FORMAT) { "Unsupported display directory format" }
                val count = input.readInt()
                require(count in 0..MAXIMUM_SCREENS)
                var area = 0
                repeat(count) {
                    val id = input.readUuid()
                    require(id !in result.surfaces)
                    val x = input.readInt()
                    val y = input.readInt()
                    val z = input.readInt()
                    val facing = input.readInt()
                    val columns = input.readInt()
                    val rows = input.readInt()
                    val mode = input.readInt()
                    require(columns in 1..8 && rows in 1..8 && mode in 0..3)
                    area += columns * rows
                    require(area <= MAXIMUM_WORLD_BLOCK_AREA)
                    val name = input.readUTF().takeIf(String::isNotEmpty)
                    val members = input.readInt()
                    require(members in 1..columns * rows)
                    val panels = List(members) { DisplayPanel(input.readInt(), input.readInt(), input.readUuid()) }
                    require(panels.none { it.instance in result.memberships })
                    val size = input.readInt()
                    require(size == columns * rows * DisplayCanvas.DENSITIES[mode] * DisplayCanvas.DENSITIES[mode] * 3)
                    require(input.available() >= size)
                    val rgb = ByteArray(size)
                    input.readFully(rgb)
                    val canvas = DisplayCanvas(columns, rows, mode, changed)
                    canvas.restoreRgb(mode, rgb)
                    val surface = DisplaySurface(id, x, y, z, facing, canvas, panels, name)
                    result.surfaces[id] = surface
                    panels.forEach { result.memberships[it.instance] = id }
                }
                require(input.available() == 0) { "Trailing display directory bytes" }
            }
            return result
        }

        private fun DataOutputStream.writeUuid(id: UUID) {
            writeLong(id.mostSignificantBits)
            writeLong(id.leastSignificantBits)
        }

        private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())
    }
}
