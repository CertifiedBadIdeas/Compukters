/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.artifact.pool

import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import ru.lazyhat.compukters.compiler.artifact.model.Utf16LiteralId

class MetadataKey internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

class Utf16LiteralKey internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

class MetadataPoolBuilder {
    private val owner = Any()
    private val values = mutableListOf<MetadataText>()

    fun intern(value: MetadataText): MetadataKey {
        values += value
        return MetadataKey(owner, values.lastIndex)
    }

    fun freeze(): FrozenMetadataPool {
        val records = values.distinct().sorted()
        val ids = records.withIndex().associate { (index, value) -> value to StringId.of(index.toUInt()) }
        return FrozenMetadataPool(
            owner = owner,
            records = records,
            idsByInsertion = values.map(ids::getValue),
        )
    }
}

class FrozenMetadataPool internal constructor(
    private val owner: Any,
    records: List<MetadataText>,
    private val idsByInsertion: List<StringId>,
) {
    val records: List<MetadataText> = records.toList()

    fun idOf(key: MetadataKey): StringId {
        require(key.owner === owner) { "metadata key belongs to another pool builder" }
        return idsByInsertion[key.index]
    }
}

class Utf16LiteralPoolBuilder {
    private val owner = Any()
    private val values = mutableListOf<Utf16Literal>()

    fun intern(value: Utf16Literal): Utf16LiteralKey {
        values += value
        return Utf16LiteralKey(owner, values.lastIndex)
    }

    fun freeze(): FrozenUtf16LiteralPool {
        val records = values.distinct().sorted()
        val ids = records.withIndex().associate { (index, value) -> value to Utf16LiteralId.of(index.toUInt()) }
        return FrozenUtf16LiteralPool(
            owner = owner,
            records = records,
            idsByInsertion = values.map(ids::getValue),
        )
    }
}

class FrozenUtf16LiteralPool internal constructor(
    private val owner: Any,
    records: List<Utf16Literal>,
    private val idsByInsertion: List<Utf16LiteralId>,
) {
    val records: List<Utf16Literal> = records.toList()

    fun idOf(key: Utf16LiteralKey): Utf16LiteralId {
        require(key.owner === owner) { "UTF-16 literal key belongs to another pool builder" }
        return idsByInsertion[key.index]
    }
}
