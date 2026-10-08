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

package ru.lazyhat.compukters.compiler.artifact.model

/** Closed, versioned selector space for Runtime ABI 1.16 floating math. */
enum class MathUnaryOperation(
    val selector: UInt,
    val fixedCost: UInt,
) {
    ABS(1u, 2u),
    SIGN(2u, 2u),
    CEIL(3u, 4u),
    FLOOR(4u, 4u),
    TRUNCATE(5u, 4u),
    ROUND(6u, 4u),
    SIN(7u, 64u),
    COS(8u, 64u),
    TAN(9u, 64u),
    ASIN(10u, 64u),
    ACOS(11u, 64u),
    ATAN(12u, 64u),
    SINH(13u, 64u),
    COSH(14u, 64u),
    TANH(15u, 64u),
    ASINH(16u, 64u),
    ACOSH(17u, 64u),
    ATANH(18u, 64u),
    SQRT(19u, 16u),
    CBRT(20u, 64u),
    EXP(21u, 64u),
    EXPM1(22u, 64u),
    LN(23u, 64u),
    LN1P(24u, 64u),
    LOG10(25u, 64u),
    LOG2(26u, 64u),
    ULP(27u, 4u),
    NEXT_UP(28u, 4u),
    NEXT_DOWN(29u, 4u),
}

/** Closed, versioned selector space for Runtime ABI 1.16 floating math. */
enum class MathBinaryOperation(
    val selector: UInt,
    val fixedCost: UInt,
) {
    MIN(1u, 2u),
    MAX(2u, 2u),
    ATAN2(3u, 64u),
    HYPOT(4u, 32u),
    POW(5u, 64u),
    IEEE_REM(6u, 64u),
    COPY_SIGN(7u, 2u),
    NEXT_TOWARDS(8u, 4u),
}
