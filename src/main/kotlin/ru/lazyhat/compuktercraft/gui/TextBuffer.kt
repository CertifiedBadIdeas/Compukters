// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.gui

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class TextBuffer {
	private val text: CharArray

	constructor(c: Char, length: Int) {
		text = CharArray(length)
		fill(c)
	}

	constructor(text: String) {
		this.text = text.toCharArray()
	}

	fun length(): Int {
		return text.size
	}

	@JvmOverloads
	fun write(text: String, start: Int = 0) {
		var start = start
		val pos = start
		start = max(start, 0)
		var end = min(start + text.length, pos + text.length)
		end = min(end, this.text.size)
		for (i in start ..< end) {
			this.text[i] = text.get(i - pos)
		}
	}

	fun write(text: ByteBuffer, start: Int) {
		var start = start
		val pos = start
		val bufferPos = text.position()

		start = max(start, 0)
		val length = text.remaining()
		var end = min(start + length, pos + length)
		end = min(end, this.text.size)
		for (i in start ..< end) {
			this.text[i] = (text.get(bufferPos + i - pos).toInt() and 0xFF).toChar()
		}
	}

	fun write(text: TextBuffer) {
		val end = min(text.length(), this.text.size)
		for (i in 0 ..< end) {
			this.text[i] = text.charAt(i)
		}
	}

	@JvmOverloads
	fun fill(c: Char, start: Int = 0, end: Int = text.size) {
		var start = start
		var end = end
		start = max(start, 0)
		end = min(end, text.size)
		for (i in start ..< end) {
			text[i] = c
		}
	}

	fun charAt(i: Int): Char {
		return text[i]
	}

	fun setChar(i: Int, c: Char) {
		if (i >= 0 && i < text.size) {
			text[i] = c
		}
	}

	override fun toString(): String {
		return String(text)
	}
}
