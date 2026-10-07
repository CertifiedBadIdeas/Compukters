/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only host-owned state; execution, filesystem and terminal remain in the native payload. */
internal data class ProgramHostCheckpoint(
    val waitingForInput: Boolean,
    val granted: GrantedResourceBudgetSnapshot,
    val confirmedRedstoneOutput: Int,
    val programScopes: List<Long>,
    val timers: List<ProgramTimerCheckpoint>,
    val addonState: ByteArray,
) {
    fun validate() {
        require(granted.guestUnits >= 0 && granted.maintenanceUnits >= 0)
        RedstoneWire.requireOutputRegister(confirmedRedstoneOutput)
        require(programScopes.size in 1..32 && programScopes.first() == 0L)
        require(programScopes.zipWithNext().all { (parent, child) -> child > parent })
        require(timers.size <= 256 && timers.map { it.identity }.distinct().size == timers.size)
        timers.forEach {
            require(it.programId in programScopes)
            require(it.durationTicks >= 0 && it.remainingTicks in 0..maxOf(1, it.durationTicks).toLong())
        }
        require(addonState.size <= MAXIMUM_ADDON_BYTES)
    }

    fun encode(): ByteArray {
        validate()
        val bytes =
            ByteBuffer
                .allocate(HEADER_BYTES + programScopes.size * 8 + timers.size * TIMER_BYTES + addonState.size)
                .order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(MAGIC).putInt(VERSION).put(if (waitingForInput) 1.toByte() else 0.toByte())
        bytes.putLong(granted.guestUnits).putLong(granted.maintenanceUnits).put(if (granted.saturated) 1.toByte() else 0.toByte())
        bytes.putInt(confirmedRedstoneOutput).putInt(programScopes.size)
        programScopes.forEach(bytes::putLong)
        bytes.putInt(timers.size)
        timers.forEach {
            bytes
                .putInt(it.identity.taskId)
                .putLong(it.identity.requestId)
                .putLong(it.programId)
                .putInt(it.durationTicks)
                .putLong(it.remainingTicks)
        }
        bytes.putInt(addonState.size).put(addonState)
        check(!bytes.hasRemaining())
        return bytes.array()
    }

    companion object {
        private const val MAGIC = 0x48545043
        private const val VERSION = 1
        private const val HEADER_BYTES = 42
        private const val TIMER_BYTES = 32
        private const val MAXIMUM_ADDON_BYTES = 1024 * 1024
        private const val MAXIMUM_BYTES = HEADER_BYTES + 32 * 8 + 256 * TIMER_BYTES + MAXIMUM_ADDON_BYTES

        fun decode(encoded: ByteArray): ProgramHostCheckpoint {
            require(encoded.size in HEADER_BYTES..MAXIMUM_BYTES) { "invalid host checkpoint size" }
            val bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

            fun boolean(): Boolean =
                when (bytes.get().toInt()) {
                    0 -> false
                    1 -> true
                    else -> throw IllegalArgumentException("invalid host checkpoint boolean")
                }

            fun count(
                maximum: Int,
                width: Int,
            ): Int {
                val count = bytes.int
                require(count in 0..maximum && count <= bytes.remaining() / width) { "invalid host checkpoint count" }
                return count
            }
            try {
                require(bytes.int == MAGIC && bytes.int == VERSION) { "incompatible host checkpoint" }
                val waiting = boolean()
                val granted = GrantedResourceBudgetSnapshot(bytes.long, bytes.long, boolean())
                val redstone = bytes.int
                val scopes = List(count(32, 8)) { bytes.long }
                val timers =
                    List(count(256, TIMER_BYTES)) {
                        ProgramTimerCheckpoint(VmHostRequestIdentity(bytes.int, bytes.long), bytes.long, bytes.int, bytes.long)
                    }
                val addon = ByteArray(count(MAXIMUM_ADDON_BYTES, 1)).also(bytes::get)
                require(!bytes.hasRemaining()) { "trailing host checkpoint bytes" }
                return ProgramHostCheckpoint(waiting, granted, redstone, scopes, timers, addon).also { it.validate() }
            } catch (error: java.nio.BufferUnderflowException) {
                throw IllegalArgumentException("truncated host checkpoint", error)
            }
        }
    }
}

internal data class ProgramTimerCheckpoint(
    val identity: VmHostRequestIdentity,
    val programId: Long,
    val durationTicks: Int,
    val remainingTicks: Long,
)
