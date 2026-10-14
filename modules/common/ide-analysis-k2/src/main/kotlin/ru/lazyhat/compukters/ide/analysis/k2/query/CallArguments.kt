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

package ru.lazyhat.compukters.ide.analysis.k2.query

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtValueArgumentList

internal object CallArguments {
    fun listAt(
        file: org.jetbrains.kotlin.psi.KtFile,
        offsetUtf16: Int,
        sourceLength: Int,
    ): KtValueArgumentList? {
        if (sourceLength == 0) return null
        val probes = listOf(offsetUtf16.coerceAtMost(sourceLength - 1), (offsetUtf16 - 1).coerceAtLeast(0)).distinct()
        return probes
            .asSequence()
            .flatMap { probe -> generateSequence(file.findElementAt(probe)) { it.parent }.filterIsInstance<KtValueArgumentList>() }
            .filter { list ->
                val left = list.leftParenthesis?.textRange?.endOffset ?: return@filter false
                val right = list.rightParenthesis?.textRange?.startOffset ?: list.textRange.endOffset
                offsetUtf16 in left..right
            }.minByOrNull { it.textRange.length }
    }

    fun indexAt(
        list: KtValueArgumentList,
        offsetUtf16: Int,
    ): Int = list.node.getChildren(null).count { child -> child.elementType == KtTokens.COMMA && child.startOffset < offsetUtf16 }
}
