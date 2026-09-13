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

package ru.lazyhat.compukters.addon.api

data class AddonCapabilitySignature(
    val arguments: List<AddonCapabilityValueType>,
    val result: AddonCapabilityValueType,
) {
    companion object {
        fun parse(signature: String): AddonCapabilitySignature {
            val match =
                Regex("fun\\(([^)]*)\\):([A-Za-z0-9_.]+)").matchEntire(signature)
                    ?: throw IllegalArgumentException("addon external binding must use a canonical function signature")
            val arguments =
                match.groupValues[1]
                    .takeIf(String::isNotEmpty)
                    ?.split(',')
                    ?.map(::hostType)
                    .orEmpty()
            return AddonCapabilitySignature(arguments, hostType(match.groupValues[2]))
        }

        private fun hostType(value: String): AddonCapabilityValueType =
            when (value.substringAfterLast('.')) {
                "Unit" -> AddonCapabilityValueType.UNIT
                "Int" -> AddonCapabilityValueType.I32
                "Long" -> AddonCapabilityValueType.I64
                "Float" -> AddonCapabilityValueType.F32
                "Double" -> AddonCapabilityValueType.F64
                "Boolean" -> AddonCapabilityValueType.BOOL
                "Char" -> AddonCapabilityValueType.CHAR
                "String" -> AddonCapabilityValueType.STRING
                else -> throw IllegalArgumentException("unsupported addon capability ABI type: $value")
            }
    }
}
