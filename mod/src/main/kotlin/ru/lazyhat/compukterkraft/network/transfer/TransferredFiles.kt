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
package ru.lazyhat.compukterkraft.network.transfer

/**
 * A list of files that have been transferred to this computer.
 *
 * @cc.module [kind=event] file_transfer.TransferredFiles
 */
// class TransferredFiles @JvmOverloads constructor(
// 	private val files: MutableList<TransferredFile?>?,
// 	@field:Nullable
// 	@param:Nullable
// 	private val onConsumed: Runnable? = null
// ) {
// 	private val consumed: AtomicBoolean = AtomicBoolean(false)
//
// 	/**
// 	 * All the files that are being transferred to this computer.
// 	 *
// 	 * @return The list of files.
// 	 */
// 	@LuaFunction
// 	fun getFiles(): MutableList<TransferredFile?>? {
// 		consumed()
// 		return files
// 	}
//
// 	private fun consumed() {
// 		if (consumed.getAndSet(true)) return
// 		if (onConsumed != null) onConsumed.run()
// 	}
//
// 	companion object {
// 		const val EVENT: String = "file_transfer"
// 	}
// }
