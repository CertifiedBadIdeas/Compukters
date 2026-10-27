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
    /** North, south, west, east, up, down. */
    val facing: Int,
    val canvas: DisplayCanvas,
    panels: List<DisplayPanel>,
    name: String? = null,
    val attached: Boolean = false,
) {
    internal val members: MutableList<DisplayPanel> = panels.toMutableList()
    val panels: List<DisplayPanel> get() = members.toList()
    var name: String? = name
        internal set

    init {
        require(facing in 0..5 && (attached || facing < 4))
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
    private val attachments = hashMapOf<DisplayOffset, UUID>()

    fun attachedAt(
        x: Int,
        y: Int,
        z: Int,
    ): DisplaySurface? = attachments[DisplayOffset(x, y, z)]?.let(surfaces::get)

    private fun checkPlacement(
        surface: DisplaySurface,
        ignoring: UUID? = null,
    ) {
        if (!surface.attached) return
        require(
            surface.panels.all { panel ->
                val owner = attachments[DisplayFace.position(surface, panel.column, panel.row)]
                owner == null || owner == ignoring
            },
        ) { "A backing block already carries a surface display" }
    }

    private fun structureChanged() {
        attachments.clear()
        for (surface in surfaces.values.filter { it.attached }) {
            for (panel in surface.panels) attachments[DisplayFace.position(surface, panel.column, panel.row)] = surface.id
        }
        changed()
    }

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
        attached: Boolean = false,
    ): DisplaySurface {
        check(surfaces.size < MAXIMUM_SCREENS) { "Display count limit reached" }
        require(columns in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE && rows in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE)
        check(snapshot().sumOf { it.canvas.columns * it.canvas.rows } + columns * rows <= MAXIMUM_WORLD_BLOCK_AREA) {
            "World display area limit reached"
        }
        require(panels.all { it.instance !in memberships }) { "Panel already belongs to a display" }
        val canvas = DisplayCanvas(columns, rows, changed = changed)
        val surface = DisplaySurface(UUID.randomUUID(), originX, originY, originZ, facing, canvas, panels, attached = attached)
        checkPlacement(surface)
        surfaces[surface.id] = surface
        panels.forEach { memberships[it.instance] = surface.id }
        structureChanged()
        return surface
    }

    /** Grow without losing world-aligned pixels; old handles and private frames are retired. */
    fun expand(
        id: UUID,
        originX: Int,
        originY: Int,
        originZ: Int,
        columns: Int,
        rows: Int,
        offsetColumn: Int,
        offsetRow: Int,
    ): DisplaySurface {
        val old = requireNotNull(surfaces[id]) { "Display was deleted" }
        require(columns in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE && rows in 1..DisplayCanvas.MAXIMUM_BLOCK_SIDE)
        require(offsetColumn >= 0 && offsetRow >= 0)
        require(offsetColumn + old.canvas.columns <= columns && offsetRow + old.canvas.rows <= rows)
        val offset = DisplayFace.offset(old.facing, offsetColumn, offsetRow)
        require(
            originX.toLong() + offset.x == old.originX.toLong() && originY.toLong() + offset.y == old.originY.toLong() &&
                originZ.toLong() + offset.z == old.originZ.toLong(),
        ) { "Expansion must preserve panel positions" }
        check(
            snapshot().sumOf { it.canvas.columns * it.canvas.rows } - old.canvas.columns * old.canvas.rows + columns * rows <=
                MAXIMUM_WORLD_BLOCK_AREA,
        ) { "World display area limit reached" }
        val canvas = DisplayCanvas(columns, rows, old.canvas.mode, changed)
        val previous = old.canvas.encodeRgb()
        val rgb = ByteArray(canvas.width * canvas.height * 3)
        val offsetX = offsetColumn * canvas.density
        val offsetY = offsetRow * canvas.density
        for (y in 0 until old.canvas.height) {
            previous.copyInto(rgb, ((y + offsetY) * canvas.width + offsetX) * 3, y * old.canvas.width * 3, (y + 1) * old.canvas.width * 3)
        }
        canvas.restoreRgb(old.canvas.mode, rgb)
        canvas.restoreOpacity(old.canvas.opacity)
        val expanded =
            DisplaySurface(
                id,
                originX,
                originY,
                originZ,
                old.facing,
                canvas,
                old.panels.map { it.copy(column = it.column + offsetColumn, row = it.row + offsetRow) },
                old.name,
                old.attached,
            )
        checkPlacement(expanded, id)
        surfaces[id] = expanded
        old.canvas.retire()
        structureChanged()
        return expanded
    }

    /** Move a complete canvas without changing its identity or published pixels. Retire location-bound handles. */
    fun relocate(
        id: UUID,
        originX: Int,
        originY: Int,
        originZ: Int,
        facing: Int,
        destination: DisplayDirectory = this,
    ): DisplaySurface {
        val old = requireNotNull(surfaces[id]) { "Display was deleted" }
        require(facing in 0..5 && (old.attached || facing < 4))
        if (destination !== this) {
            check(destination.surfaces.size < MAXIMUM_SCREENS)
            check(
                destination.snapshot().sumOf { it.canvas.columns * it.canvas.rows } +
                    old.canvas.columns * old.canvas.rows <= MAXIMUM_WORLD_BLOCK_AREA,
            )
            require(id !in destination.surfaces && old.panels.none { it.instance in destination.memberships })
        }
        val canvas = DisplayCanvas(old.canvas.columns, old.canvas.rows, old.canvas.mode, destination.changed)
        canvas.restoreRgb(old.canvas.mode, old.canvas.encodeRgb())
        canvas.restoreOpacity(old.canvas.opacity)
        val moved = DisplaySurface(id, originX, originY, originZ, facing, canvas, old.panels, old.name, old.attached)
        destination.checkPlacement(moved, if (destination === this) id else null)
        if (destination !== this) {
            surfaces.remove(id)
            old.panels.forEach { memberships.remove(it.instance) }
            destination.changed()
        }
        destination.surfaces[id] = moved
        old.panels.forEach { destination.memberships[it.instance] = id }
        old.canvas.retire()
        if (destination !== this) destination.structureChanged()
        structureChanged()
        return moved
    }

    /** Dismantle only installed panels; preserve each published tile, never private frames or shared names. */
    fun split(id: UUID): List<DisplaySurface> {
        val old = requireNotNull(surfaces[id]) { "Display was deleted" }
        check(surfaces.size - 1 + old.panels.size <= MAXIMUM_SCREENS) { "Display count limit reached" }
        val replacements =
            old.panels.map { panel ->
                val offset = DisplayFace.offset(old.facing, panel.column, panel.row)
                val canvas = DisplayCanvas(1, 1, old.canvas.mode, changed)
                canvas.restoreRgb(old.canvas.mode, old.canvas.tile(panel.column, panel.row))
                canvas.restoreOpacity(old.canvas.opacity)
                DisplaySurface(
                    UUID.randomUUID(),
                    old.originX + offset.x,
                    old.originY + offset.y,
                    old.originZ + offset.z,
                    old.facing,
                    canvas,
                    listOf(DisplayPanel(0, 0, panel.instance)),
                    attached = old.attached,
                )
            }
        surfaces.remove(id)
        replacements.forEach { surface ->
            surfaces[surface.id] = surface
            surface.panels.forEach { memberships[it.instance] = surface.id }
        }
        old.canvas.retire()
        structureChanged()
        return replacements
    }

    fun join(
        id: UUID,
        panel: DisplayPanel,
    ) {
        val surface = requireNotNull(surfaces[id]) { "Display was deleted" }
        require(panel.instance !in memberships)
        require(panel.column in 0 until surface.canvas.columns && panel.row in 0 until surface.canvas.rows)
        require(surface.members.none { it.column == panel.column && it.row == panel.row }) { "Display slot is occupied" }
        if (surface.attached) require(DisplayFace.position(surface, panel.column, panel.row) !in attachments)
        surface.members += panel
        memberships[panel.instance] = id
        structureChanged()
    }

    /** Destruction removes only the panel; last destruction removes the entire canvas. */
    fun remove(instance: UUID): Boolean {
        val id = memberships.remove(instance) ?: return false
        val surface = surfaces.getValue(id)
        surface.members.removeAll { it.instance == instance }
        if (surface.members.isEmpty()) {
            surfaces.remove(id)
            surface.canvas.retire()
        }
        structureChanged()
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
                out.writeBoolean(surface.attached)
                out.writeInt(surface.canvas.columns)
                out.writeInt(surface.canvas.rows)
                out.writeInt(surface.canvas.mode)
                out.writeByte(surface.canvas.opacity)
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
        private const val FORMAT = 2
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
                val format = input.readInt()
                require(format in 1..FORMAT) { "Unsupported display directory format" }
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
                    val attached = if (format >= 2) input.readBoolean() else false
                    val columns = input.readInt()
                    val rows = input.readInt()
                    val mode = input.readInt()
                    val opacity = if (format >= 2) input.readUnsignedByte() else 255
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
                    canvas.restoreOpacity(opacity)
                    val surface = DisplaySurface(id, x, y, z, facing, canvas, panels, name, attached)
                    result.checkPlacement(surface)
                    result.surfaces[id] = surface
                    panels.forEach { result.memberships[it.instance] = id }
                    if (attached) panels.forEach { result.attachments[DisplayFace.position(surface, it.column, it.row)] = id }
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
