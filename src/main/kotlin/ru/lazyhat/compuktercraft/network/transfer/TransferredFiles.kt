// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.transfer

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
