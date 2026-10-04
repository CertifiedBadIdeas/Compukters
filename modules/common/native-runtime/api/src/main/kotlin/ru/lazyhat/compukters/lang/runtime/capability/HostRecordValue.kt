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

package ru.lazyhat.compukters.lang.runtime.capability

import java.util.Collections

class HostRecordValue(
    val schema: HostRecordSchema,
    values: List<HostResponse>,
) {
    val values: List<HostResponse> = Collections.unmodifiableList(values.toList())

    init {
        require(this.values.size == schema.fields.size) { "record response field count does not match schema" }
        schema.fields.zip(this.values).forEach { (field, value) ->
            require(field.type == value.valueType()) { "record response field type does not match schema: ${field.name}" }
            if (value is HostResponse.RecordSuccess) {
                require(field.record == value.value.schema) { "nested record response schema does not match: ${field.name}" }
            }
        }
        var units = 0

        fun count(value: HostResponse) {
            when (value) {
                is HostResponse.StringSuccess -> {
                    units += value.value.length
                    require(units <= 4096) { "record response strings exceed UTF-16 limit" }
                }

                is HostResponse.RecordSuccess -> {
                    value.value.values.forEach(::count)
                }

                else -> {
                    Unit
                }
            }
        }
        this.values.forEach(::count)
    }
}

internal fun HostResponse.valueType(): HostValueType =
    when (this) {
        HostResponse.UnitSuccess -> HostValueType.UNIT
        is HostResponse.IntSuccess -> HostValueType.I32
        is HostResponse.LongSuccess -> HostValueType.I64
        is HostResponse.FloatSuccess -> HostValueType.F32
        is HostResponse.DoubleSuccess -> HostValueType.F64
        is HostResponse.BoolSuccess -> HostValueType.BOOL
        is HostResponse.CharSuccess -> HostValueType.CHAR
        is HostResponse.StringSuccess -> HostValueType.STRING
        is HostResponse.RecordSuccess -> HostValueType.RECORD
        is HostResponse.Failure -> error("failures cannot be record fields")
    }
