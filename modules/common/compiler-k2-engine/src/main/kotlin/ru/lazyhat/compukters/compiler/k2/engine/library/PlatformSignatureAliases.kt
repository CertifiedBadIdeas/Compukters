/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine.library

import ru.lazyhat.compukters.platform.bundle.PlatformModule

/** Source metadata keeps alias spellings; compiled exports use the expanded type. */
internal class PlatformSignatureAliases(
    modules: List<PlatformModule>,
) {
    private val aliases =
        modules
            .flatMap { it.declarations }
            .filter { it.signature.startsWith("typealias:") }
            .associate { it.symbol to it.signature.removePrefix("typealias:") }
    private val names = aliases.keys.groupBy { it.substringAfterLast('.') }
    private val pattern =
        (aliases.keys + names.keys)
            .sortedByDescending(String::length)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|", prefix = "\\b(?:", postfix = ")\\b", transform = Regex::escape)
            ?.let(::Regex)

    fun expand(
        signature: String,
        owner: String,
    ): String = expand(signature, owner, emptySet())

    private fun expand(
        text: String,
        owner: String,
        visiting: Set<String>,
    ): String =
        pattern?.replace(text) { match ->
            val direct = match.value.takeIf { it in aliases }
            val candidates = names[match.value].orEmpty()
            val local = candidates.filter { owner.startsWith(it.substringBeforeLast('.') + ".") }
            val alias =
                direct ?: local.singleOrNull() ?: candidates.singleOrNull()
                    ?: error("ambiguous platform typealias ${match.value} in $owner")
            require(alias !in visiting && visiting.size < 64) { "cyclic or excessively nested platform typealias $alias" }
            expand(aliases.getValue(alias), alias, visiting + alias)
        } ?: text
}
