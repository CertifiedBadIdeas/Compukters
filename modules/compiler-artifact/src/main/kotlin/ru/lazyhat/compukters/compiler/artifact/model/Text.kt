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

package ru.lazyhat.compukters.compiler.artifact.model

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class MetadataText private constructor(private val bytes: ByteArray) : Comparable<MetadataText> {
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun compareTo(other: MetadataText): Int = compareUnsigned(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is MetadataText && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = String(bytes, StandardCharsets.UTF_8)

    companion object {
        fun of(value: String): MetadataText {
            val encoder =
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val encoded =
                try {
                    encoder.encode(CharBuffer.wrap(value))
                } catch (failure: CharacterCodingException) {
                    throw IllegalArgumentException("metadata must be strict UTF-8", failure)
                }
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            return MetadataText(bytes)
        }
    }
}

class Utf16Literal private constructor(private val codeUnits: CharArray) : Comparable<Utf16Literal> {
    val size: Int
        get() = codeUnits.size

    fun toLittleEndianByteArray(): ByteArray =
        ByteArray(codeUnits.size * 2).also { bytes ->
            codeUnits.forEachIndexed { index, codeUnit ->
                val value = codeUnit.code
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value ushr 8).toByte()
            }
        }

    override fun compareTo(other: Utf16Literal): Int =
        compareUnsigned(toLittleEndianByteArray(), other.toLittleEndianByteArray())

    override fun equals(other: Any?): Boolean = other is Utf16Literal && codeUnits.contentEquals(other.codeUnits)

    override fun hashCode(): Int = codeUnits.contentHashCode()

    companion object {
        fun of(vararg codeUnits: Int): Utf16Literal {
            require(codeUnits.all { it in 0..0xffff }) { "UTF-16 code units must fit u16" }
            return Utf16Literal(CharArray(codeUnits.size) { codeUnits[it].toChar() })
        }

        fun fromString(value: String): Utf16Literal = Utf16Literal(value.toCharArray())
    }
}

internal fun compareUnsigned(
    left: ByteArray,
    right: ByteArray,
): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}
