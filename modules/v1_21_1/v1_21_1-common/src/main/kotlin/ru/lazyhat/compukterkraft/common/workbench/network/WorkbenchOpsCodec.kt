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

package ru.lazyhat.compukterkraft.common.workbench.network

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.core.workbench.EditorPresence
import ru.lazyhat.compukterkraft.core.workbench.crdt.AtomId
import ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.workbench.crdt.TextRun

/**
 * Wire-format helpers shared by every workbench-CRDT network message.
 *
 * Encoding spec (also documented in the async-sync design doc):
 * - `SiteId`         → `writeUtf(raw, max = 32)`
 * - `AtomId`         → `SiteId` + `writeVarInt(clock)`
 * - `AtomId?`        → `writeBoolean(present)` then payload when present
 * - `Op`             → `writeByte(0 = Insert, 1 = Delete)` then fields
 * - `List<Op>`       → `writeVarInt(size)` then each `Op`
 * - `Map<SiteId,Int>`→ `writeVarInt(size)` then `(SiteId, varInt)` pairs
 * - `List<TextRun>`  → `writeVarInt(size)` then each `(AtomId, AtomId?, writeUtf(text, MAX), bool deleted)`
 *
 * `MAX_TEXT_LENGTH` bounds Insert / TextRun text to keep packets within Minecraft's frame budget.
 * A single Insert that would exceed it must be sliced by the producer before enqueue.
 */

internal const val MAX_TEXT_LENGTH: Int =
    32 * 1024 // Per-string ceiling for any user-authored text payload on the wire.

private const val KIND_INSERT: Int = 0
private const val KIND_DELETE: Int = 1

internal fun FriendlyByteBuf.writeSiteId(site: SiteId) {
    writeUtf(site.raw, SiteId.MAX_LENGTH)
}

internal fun FriendlyByteBuf.readSiteId(): SiteId = SiteId(readUtf(SiteId.MAX_LENGTH))

internal fun FriendlyByteBuf.writeAtomId(atom: AtomId) {
    writeSiteId(atom.site)
    writeVarInt(atom.clock)
}

internal fun FriendlyByteBuf.readAtomId(): AtomId = AtomId(readSiteId(), readVarInt())

internal fun FriendlyByteBuf.writeNullableAtomId(atom: AtomId?) {
    writeBoolean(atom != null)
    if (atom != null) writeAtomId(atom)
}

internal fun FriendlyByteBuf.readNullableAtomId(): AtomId? = if (readBoolean()) readAtomId() else null

internal fun FriendlyByteBuf.writeOp(op: Op) {
    when (op) {
        is Op.Insert -> {
            writeByte(KIND_INSERT)
            writeSiteId(op.author)
            writeVarInt(op.clock)
            writeNullableAtomId(op.leftId)
            writeUtf(op.text, MAX_TEXT_LENGTH)
        }

        is Op.Delete -> {
            writeByte(KIND_DELETE)
            writeSiteId(op.author)
            writeVarInt(op.clock)
            writeAtomId(op.targetId)
            writeVarInt(op.length)
        }
    }
}

internal fun FriendlyByteBuf.readOp(): Op =
    when (val kind = readByte().toInt()) {
        KIND_INSERT -> {
            Op.Insert(
                author = readSiteId(),
                clock = readVarInt(),
                leftId = readNullableAtomId(),
                text = readUtf(MAX_TEXT_LENGTH),
            )
        }

        KIND_DELETE -> {
            Op.Delete(
                author = readSiteId(),
                clock = readVarInt(),
                targetId = readAtomId(),
                length = readVarInt(),
            )
        }

        else -> {
            error("Unknown Op kind on the wire: $kind")
        }
    }

internal fun FriendlyByteBuf.writeOps(ops: List<Op>) {
    writeVarInt(ops.size)
    ops.forEach { writeOp(it) }
}

internal fun FriendlyByteBuf.readOps(): List<Op> {
    val n = readVarInt()
    return List(n) { readOp() }
}

internal fun FriendlyByteBuf.writeVersionVector(vv: Map<SiteId, Int>) {
    writeVarInt(vv.size)
    vv.forEach { (site, clock) ->
        writeSiteId(site)
        writeVarInt(clock)
    }
}

internal fun FriendlyByteBuf.readVersionVector(): Map<SiteId, Int> {
    val n = readVarInt()
    val out = HashMap<SiteId, Int>(n)
    repeat(n) { out[readSiteId()] = readVarInt() }
    return out
}

internal fun FriendlyByteBuf.writeRun(run: TextRun) {
    writeAtomId(run.id)
    writeNullableAtomId(run.leftId)
    writeUtf(run.text, MAX_TEXT_LENGTH)
    writeBoolean(run.deleted)
}

internal fun FriendlyByteBuf.readRun(): TextRun =
    TextRun(
        id = readAtomId(),
        leftId = readNullableAtomId(),
        text = readUtf(MAX_TEXT_LENGTH),
        deleted = readBoolean(),
    )

internal fun FriendlyByteBuf.writeRuns(runs: List<TextRun>) {
    writeVarInt(runs.size)
    runs.forEach { writeRun(it) }
}

internal fun FriendlyByteBuf.readRuns(): List<TextRun> {
    val n = readVarInt()
    return List(n) { readRun() }
}

internal fun FriendlyByteBuf.writeCursorAnchor(cursor: CursorAnchor) {
    writeNullableAtomId(cursor.atomId)
    writeVarInt(cursor.offsetWithinRun)
}

internal fun FriendlyByteBuf.readCursorAnchor(): CursorAnchor =
    CursorAnchor(
        atomId = readNullableAtomId(),
        offsetWithinRun = readVarInt(),
    )

internal fun FriendlyByteBuf.writeNullableCursorAnchor(cursor: CursorAnchor?) {
    writeBoolean(cursor != null)
    if (cursor != null) writeCursorAnchor(cursor)
}

internal fun FriendlyByteBuf.readNullableCursorAnchor(): CursorAnchor? = if (readBoolean()) readCursorAnchor() else null

/**
 * Per-presence display name ceiling. Minecraft profile names are 16 chars max; we add slack
 * for future "alias" use cases (e.g. localized command-block names). Generous but bounded.
 */
private const val MAX_DISPLAY_NAME_LENGTH: Int = 64

/**
 * Per-presence path ceiling. Workspace paths are bounded by the same limits as
 * [ComputerWorkspaceEntry] uses today; 256 is comfortable for any realistic nesting.
 */
private const val MAX_PRESENCE_PATH_LENGTH: Int = 256

internal fun FriendlyByteBuf.writePresence(presence: EditorPresence) {
    writeSiteId(presence.siteId)
    writeUtf(presence.displayName, MAX_DISPLAY_NAME_LENGTH)
    writeUtf(presence.path, MAX_PRESENCE_PATH_LENGTH)
    writeNullableCursorAnchor(presence.cursor)
}

internal fun FriendlyByteBuf.readPresence(): EditorPresence =
    EditorPresence(
        siteId = readSiteId(),
        displayName = readUtf(MAX_DISPLAY_NAME_LENGTH),
        path = readUtf(MAX_PRESENCE_PATH_LENGTH),
        cursor = readNullableCursorAnchor(),
    )

internal fun FriendlyByteBuf.writePresences(presences: List<EditorPresence>) {
    writeVarInt(presences.size)
    presences.forEach { writePresence(it) }
}

internal fun FriendlyByteBuf.readPresences(): List<EditorPresence> {
    val n = readVarInt()
    return List(n) { readPresence() }
}
