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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.security.MessageDigest

object CompilationIdentity {
    fun compute(request: CompileRequest): Hash256 {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("Compukter compilation identity v1\u0000".encodeToByteArray())
        val identity = request.expectedIdentity
        digest.field(1, request.source.toByteArray())
        digest.field(2, request.path.value.encodeToByteArray())
        digest.field(3, request.target.name.encodeToByteArray())
        digest.field(4, identity.compilerVersion.encodeToByteArray())
        digest.field(5, identity.languageVersion.encodeToByteArray())
        digest.field(6, identity.codegenAbi.littleEndian())
        digest.field(7, identity.payloadHash.toByteArray())
        digest.field(8, identity.standardLibraryAbi.toByteArray())
        digest.field(9, identity.artifactWriterVersion.littleEndian())
        return Hash256.of(digest.digest())
    }
}

class CompilationCacheArtifact private constructor(
    val artifact: BinaryValue,
    val artifactHash: Hash256,
) {
    companion object {
        fun admit(result: CompileResult): CompilationCacheArtifact? {
            val success = result as? CompileSuccess ?: return null
            val actual = Hash256.of(MessageDigest.getInstance("SHA-256").digest(success.artifact.toByteArray()))
            if (actual != success.artifactHash) return null
            return CompilationCacheArtifact(BinaryValue.of(success.artifact.toByteArray()), actual)
        }
    }
}

private fun MessageDigest.field(
    tag: Int,
    bytes: ByteArray,
) {
    update(tag.toByte())
    update(bytes.size.toUInt().littleEndian())
    update(bytes)
}

private fun UInt.littleEndian(): ByteArray = ByteArray(4) { index -> (this shr (index * 8)).toByte() }
