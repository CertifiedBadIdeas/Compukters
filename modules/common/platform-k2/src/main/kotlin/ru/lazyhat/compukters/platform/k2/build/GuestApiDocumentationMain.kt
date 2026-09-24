/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.platform.k2.build

import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/** Exports the same parsed declarations used by the Guest platform bundle for the documentation site. */
object GuestApiDocumentationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: GuestApiDocumentationMain <source-root> <output-json>" }
        val root = Path.of(args[0])
        val output = Path.of(args[1])
        val paths =
            Files.walk(root).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .map { root.relativize(it).invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }
        require(paths.isNotEmpty()) { "no Guest Kotlin sources under $root" }
        val content = paths.associateWith { path -> root.resolve(path).readText() }
        val sources = paths.map { path -> PlatformSource(path, ImmutableBytes.of(content.getValue(path).toByteArray())) }
        val compiled = PlatformMetadataCompiler().compile(PlatformModuleId("docs", "guest-api"), sources)
        val exported = compiled.exportedSymbols.toSet()
        val declarations =
            compiled.declarations
                .filter { it.symbol in exported }
                .filterNot { it.symbol.substringAfterLast('.').startsWith("<get-") }
                .filterNot { declaration ->
                    val text = content.getValue(declaration.sourcePath).substring(declaration.startUtf16, declaration.endUtf16)
                    text.startsWith("private ") || text.startsWith("internal ")
                }.map { declaration ->
                    val text = content.getValue(declaration.sourcePath)
                    val source = text.substring(declaration.startUtf16, declaration.endUtf16)
                    val packageName =
                        Regex("(?m)^package\\s+([\\w.]+)").find(text)?.groupValues?.get(1)
                            ?: error("missing package in ${declaration.sourcePath}")
                    val relativeSymbol = declaration.symbol.removePrefix("$packageName.")
                    val kind = declaration.signature.substringBefore('(').substringBefore(':')
                    ApiEntry(
                        packageName = packageName,
                        symbol = declaration.symbol,
                        name = relativeSymbol.substringAfterLast('.'),
                        owner = relativeSymbol.substringBeforeLast('.', ""),
                        kind = kind,
                        signature = declarationHeader(source, kind),
                        sourcePath = declaration.sourcePath,
                        line = text.substring(0, declaration.startUtf16).count { it == '\n' } + 1,
                        description = precedingKDoc(text, declaration.startUtf16),
                    )
                }.distinctBy { listOf(it.symbol, it.signature, it.sourcePath, it.line) }
                .sortedWith(compareBy(ApiEntry::packageName, ApiEntry::owner, ApiEntry::name, ApiEntry::signature))
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, entriesJson(declarations))
        println("Exported ${declarations.size} Guest API declarations to $output")
    }
}

private data class ApiEntry(
    val packageName: String,
    val symbol: String,
    val name: String,
    val owner: String,
    val kind: String,
    val signature: String,
    val sourcePath: String,
    val line: Int,
    val description: String,
)

private fun declarationHeader(
    source: String,
    kind: String,
): String {
    if (kind == "constructor" && source.trimStart().startsWith('(')) {
        return "constructor${source.trim().replace(Regex("\\s+"), " ")}"
    }
    if (kind == "val" || kind == "var") return source.lineSequence().first().trim()
    var parentheses = 0
    var brackets = 0
    var quoted = false
    var escaped = false
    for (index in source.indices) {
        val character = source[index]
        if (quoted) {
            if (character == '"' && !escaped) quoted = false
            escaped = character == '\\' && !escaped
            continue
        }
        when (character) {
            '"' -> {
                quoted = true
            }

            '(' -> {
                parentheses++
            }

            ')' -> {
                parentheses--
            }

            '[' -> {
                brackets++
            }

            ']' -> {
                brackets--
            }

            '{', '=' -> {
                if (parentheses == 0 && brackets == 0) {
                    return source.substring(0, index).trim().replace(Regex("\\s+"), " ")
                }
            }
        }
    }
    return source.trim().replace(Regex("\\s+"), " ")
}

private fun precedingKDoc(
    source: String,
    offset: Int,
): String {
    val before = source.substring(0, offset).trimEnd()
    if (!before.endsWith("*/")) return ""
    val start = before.lastIndexOf("/**")
    if (start < 0 || before.indexOf("*/", start) != before.length - 2) return ""
    return before
        .substring(start + 3, before.length - 2)
        .lineSequence()
        .map { it.trim().removePrefix("*").trim() }
        .joinToString("\n")
        .trim()
}

private fun entriesJson(entries: List<ApiEntry>): String =
    buildString {
        append("[\n")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(",\n")
            append("  {")
            append("\"package\":").append(entry.packageName.json()).append(',')
            append("\"symbol\":").append(entry.symbol.json()).append(',')
            append("\"name\":").append(entry.name.json()).append(',')
            append("\"owner\":").append(entry.owner.json()).append(',')
            append("\"kind\":").append(entry.kind.json()).append(',')
            append("\"signature\":").append(entry.signature.json()).append(',')
            append("\"source\":").append(entry.sourcePath.json()).append(',')
            append("\"line\":").append(entry.line).append(',')
            append("\"description\":").append(entry.description.json())
            append('}')
        }
        append("\n]\n")
    }

private fun String.json(): String =
    buildString {
        append('"')
        for (character in this@json) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
