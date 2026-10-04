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

import java.util.Collections

/** A bounded immutable tree. Record fields describe values, never constructors or arbitrary Guest objects. */
class AddonRecordSchema(
    val typeName: String,
    fields: List<AddonRecordField>,
) {
    val fields: List<AddonRecordField> = Collections.unmodifiableList(fields.toList())

    init {
        require(TYPE_NAME.matches(typeName) && typeName.length <= 256) { "invalid record type name: $typeName" }
        require(this.fields.isNotEmpty() && this.fields.size <= MAXIMUM_FIELDS) { "invalid record field count" }
        require(this.fields.distinctBy { it.name }.size == this.fields.size) { "duplicate record field name" }
        var fieldCount = 0
        var nodeCount = 0

        fun visit(
            record: AddonRecordSchema,
            depth: Int,
        ) {
            require(depth <= MAXIMUM_DEPTH) { "record nesting exceeds limit" }
            nodeCount++
            fieldCount += record.fields.size
            require(nodeCount <= MAXIMUM_NODES && fieldCount <= MAXIMUM_FIELDS) { "record tree exceeds limit" }
            record.fields.forEach { field ->
                if (field.record != null) {
                    visit(field.record, depth + 1)
                } else if (field.type == AddonCapabilityValueType.STRING) {
                    nodeCount++
                    require(nodeCount <= MAXIMUM_NODES) { "record tree exceeds limit" }
                }
            }
        }
        visit(this, 1)
    }

    override fun equals(other: Any?): Boolean = other is AddonRecordSchema && typeName == other.typeName && fields == other.fields

    override fun hashCode(): Int = 31 * typeName.hashCode() + fields.hashCode()

    companion object {
        const val MAXIMUM_DEPTH: Int = 8
        const val MAXIMUM_NODES: Int = 32
        const val MAXIMUM_FIELDS: Int = 64
        private val TYPE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}

data class AddonRecordField(
    val name: String,
    val type: AddonCapabilityValueType,
    val record: AddonRecordSchema? = null,
) {
    init {
        require(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(name)) { "invalid record field name: $name" }
        require(type != AddonCapabilityValueType.UNIT) { "Unit cannot be a record field" }
        require((type == AddonCapabilityValueType.RECORD) == (record != null)) { "record field schema does not match its type" }
    }
}
