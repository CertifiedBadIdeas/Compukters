/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.vm

data class CapabilityIdentity(
    val namespace: String,
    val name: String,
    val abiMajor: Int,
    val abiMinor: Int,
)

sealed interface VmValue {
    data class I32(
        val value: Int,
    ) : VmValue

    data class I64(
        val value: Long,
    ) : VmValue

    data class F32(
        val bits: Int,
    ) : VmValue

    data class F64(
        val bits: Long,
    ) : VmValue

    data class Bool(
        val value: Boolean,
    ) : VmValue

    data class CharValue(
        val value: Char,
    ) : VmValue

    data class StringValue(
        val value: String,
    ) : VmValue
}

data class VmHostRequest(
    val id: Long,
    val capability: CapabilityIdentity,
    val operation: Int,
    val arguments: List<VmValue>,
)

enum class GuestTrap(
    internal val wireCode: Int,
) {
    DIVISION_BY_ZERO(0),
    STACK_OVERFLOW(1),
    NEGATIVE_ARRAY_SIZE(2),
    NULL_REFERENCE(3),
    INDEX_OUT_OF_BOUNDS(4),
    CLASS_CAST(5),
}

enum class VmFault(
    internal val wireCode: Int,
) {
    INVALID_RESOLVED_ID(0),
    INVALID_VALUE_TYPE(1),
    ACCOUNTING_OVERFLOW(2),
    INVALID_STORAGE_PLAN(3),
    CORRUPT_LIFECYCLE(4),
    REACHED_UNREACHABLE(5),
    UNSUPPORTED_INSTRUCTION(6),
    HANDLE_EXHAUSTED(7),
    CORRUPT_HEAP(8),
    INVALID_REFERENCE(9),
}

enum class QuotaKind(
    internal val wireCode: Int,
) {
    HOST_REQUEST_CODE_UNITS(0),
    HOST_REQUESTS(1),
    ACCEPTED_RESPONSES(2),
}

enum class HostFailureKind(
    internal val wireCode: Int,
) {
    END_OF_FILE(0),
    UNAVAILABLE(1),
    INPUT_OUTPUT(2),
    CANCELLED(3),
    OTHER(4),
}

sealed interface VmOutcome {
    data object SliceExhausted : VmOutcome

    data object WaitingForLine : VmOutcome

    data class HostRequest(
        val request: VmHostRequest,
    ) : VmOutcome

    data class AllocationExhausted(
        val collectionAttempted: Boolean,
    ) : VmOutcome

    data class QuotaExhausted(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : VmOutcome

    data class Halted(
        val value: VmValue?,
    ) : VmOutcome

    data class Crashed(
        val trap: GuestTrap,
    ) : VmOutcome

    data class Faulted(
        val fault: VmFault,
    ) : VmOutcome

    data class HostFailed(
        val kind: HostFailureKind,
        val code: Long,
    ) : VmOutcome
}

class VmVerificationException : IllegalArgumentException("native VM rejected the artifact")

class VmAdmissionException(
    val code: Int,
) : IllegalArgumentException("native VM admission failed with code $code")

class VmStartException(
    val code: Int,
) : IllegalStateException("native VM start failed with code $code")

class VmBridgeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
