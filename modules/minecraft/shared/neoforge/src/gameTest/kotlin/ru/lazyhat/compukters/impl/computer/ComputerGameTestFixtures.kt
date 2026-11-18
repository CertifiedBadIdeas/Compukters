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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorRequestException
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal object ComputerGameTestFixtures {
    /** Keep one observation in flight; a refused admission may be attempted on the next test tick. */
    fun <T> awaitObservation(
        helper: GameTestHelper,
        future: CompletableFuture<T>,
        onRefused: () -> Unit,
    ): T {
        helper.assertTrue(future.isDone, "computer observation is pending")
        try {
            return future.join()
        } catch (failure: CompletionException) {
            var cause: Throwable = failure
            while (cause is CompletionException && cause.cause != null) cause = checkNotNull(cause.cause)
            if (cause is ProgramRuntimeActorRequestException && cause.submission == VmActorSubmission.MAILBOX_FULL) {
                onRefused()
                helper.fail("computer observation admission is busy; retrying on the next tick")
            }
            throw failure
        }
    }

    fun processTestRom(): ByteArray {
        val programs =
            listOf(
                "/boot/init" to resource("/system/programs/boot"),
                "/bin/edit" to resource("/system/programs/edit"),
                "/bin/hello" to fixture("process-terminal-child.cpkt"),
                "/bin/kotlinc" to resource("/system/programs/kotlinc"),
                "/bin/shell" to resource("/system/programs/shell"),
                "/bin/vmbench" to resource("/system/programs/vmbench"),
            ).sortedBy { it.first }
        val payloadSize =
            programs.fold(16) { size, (path, artifact) ->
                Math.addExact(size, 16 + path.encodeToByteArray().size + artifact.size)
            }
        val payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        payload
            .put("CPKTSYS\u0000".encodeToByteArray())
            .putShort(1.toShort())
            .putShort(0.toShort())
            .putInt(programs.size)
        programs.forEach { (pathText, artifact) ->
            val path = pathText.encodeToByteArray()
            payload
                .putInt(path.size)
                .put(path)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0.toShort())
                .putLong(artifact.size.toLong())
                .put(artifact)
        }
        return payload.array() + MessageDigest.getInstance("SHA-256").digest(payload.array())
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "missing GameTest fixture $name"
        }.use { it.readAllBytes() }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) {
            "missing GameTest resource $name"
        }.use { it.readAllBytes() }
}
