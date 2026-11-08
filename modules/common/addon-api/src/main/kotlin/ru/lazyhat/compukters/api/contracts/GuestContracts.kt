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

package ru.lazyhat.compukters.api.contracts

/** Declarations only. The SDK generates the device implementation and its discovery provider. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestDevice(
    val id: String,
    val bindings: String = "",
)

/** A program-scoped service, exposed as a Guest object. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestService(
    val bindings: String = "",
)

/** A host-owned handle returned by another method, without peripheral discovery. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestResource(
    val bindings: String = "",
)

/** An immutable constructor-only response record. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestRecord

/**
 * Immediate observation.
 * @param binding Optional ABI name; defaults to the class name in camelCase followed by the capitalized method name.
 * Set it explicitly to preserve an existing ABI identity when renaming or migrating declarations.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestRead(
    val binding: String = "",
)

/**
 * Immediate ordinary call, including operations that return a value or release a device.
 * @param binding Optional ABI name; defaults to the class name in camelCase followed by the capitalized method name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestCall(
    val binding: String = "",
)

/**
 * Immediate Unit command. Generates both the ordinary method and its immutable Command<Unit> factory.
 * @param binding Optional ABI name; defaults to the class name in camelCase followed by the capitalized method name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestCommand(
    val binding: String = "",
)

/**
 * Explicitly allows a host operation to retain a pending continuation. Never generates a batch command.
 * @param binding Optional ABI name; defaults to the class name in camelCase followed by the capitalized method name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class GuestWait(
    val binding: String = "",
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Finite

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class DoubleRange(
    val min: Double = -Double.MAX_VALUE,
    val max: Double = Double.MAX_VALUE,
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class IntRange(
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class MaxLength(
    val value: Int,
)
