/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.build

/** Host representations are generated from resolved Guest fields; their methods are not Guest implementations. */
internal fun StringBuilder.appendNativeHostRecord(
    type: String,
    fields: List<Pair<String, String>>,
    boundary: GuestBoundaryTypes,
) {
    val name = type.substringAfterLast('.')
    val construction = boundary.construction(type)?.takeIf { it.factory.isNotEmpty() }
    if (construction != null) appendLine("@ConsistentCopyVisibility")
    appendLine("public data class $name${if (construction != null) " private constructor" else ""}(")
    fields.forEach { (field, fieldType) -> appendLine("    public val $field: ${fieldType.substringAfterLast('.')},") }
    appendLine(")${if (construction != null) " {" else ""}")
    if (construction == null) return
    appendLine("    public companion object {")
    val parameters = fields.joinToString { (field, type) -> "$field: ${type.substringAfterLast('.')}" }
    appendLine("        public fun ${construction.factory}($parameters): $name {")
    if (construction.normalize.isNotEmpty()) {
        val normalizedType = fields.single { it.first == construction.normalize }.second
        val components = mutableListOf<Pair<String, String>>()

        fun flatten(
            type: String,
            path: String,
        ) {
            val children = boundary.fields(type)
            if (children == null) {
                components += path to type
            } else {
                children.forEach { flatten(it.type, "$path.${it.name}") }
            }
        }
        flatten(normalizedType, construction.normalize)
        val scalar = components.first().second
        require(components.all { it.second == scalar } && scalar in setOf("Float", "Double")) { "Invalid normalization descriptor" }
        val zero = if (scalar == "Float") "0.0f" else "0.0"
        appendLine(
            "            require(${components.joinToString(" && ") { "${it.first}.isFinite()" }}) { \"Non-finite record components\" }",
        )
        appendLine("            val scale = maxOf($zero, ${components.joinToString { "kotlin.math.abs(${it.first})" }})")
        appendLine("            require(scale != $zero) { \"Zero record components\" }")
        components.forEachIndexed { index, (path, _) -> appendLine("            val component$index = $path / scale") }
        appendLine(
            "            val magnitude = kotlin.math.sqrt(${components.indices.joinToString(" + ") { "component$it * component$it" }})",
        )
        var cursor = 0

        fun reconstruct(type: String): String {
            val children = boundary.fields(type) ?: return "component${cursor++} / magnitude"
            return "${type.substringAfterLast('.')}(${children.joinToString { reconstruct(it.type) }})"
        }
        appendLine("            val normalized = ${reconstruct(normalizedType)}")
    }
    val arguments = fields.joinToString { (field, _) -> if (field == construction.normalize) "normalized" else field }
    appendLine("            return $name($arguments)")
    appendLine("        }")
    appendLine("    }")
    appendLine("}")
}
