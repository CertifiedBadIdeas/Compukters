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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind

internal object IdeSourceStyles {
    fun styleColor(style: IdeTextStyle): Int =
        when (style) {
            IdeTextStyle.Ui -> IdeColors.TEXT
            IdeTextStyle.Plain -> IdeColors.EDITOR_TEXT
            is IdeTextStyle.Lexical -> lexicalColor(style.kind)
            is IdeTextStyle.Semantic -> semanticColor(style.category)
        }

    private fun lexicalColor(kind: KotlinLexicalKind): Int =
        when (kind) {
            KotlinLexicalKind.Keyword -> IdeColors.KEYWORD

            KotlinLexicalKind.String,
            KotlinLexicalKind.Character,
            KotlinLexicalKind.MultilineString,
            -> IdeColors.STRING

            KotlinLexicalKind.Escape -> IdeColors.STRING_ESCAPE

            KotlinLexicalKind.FunctionCall -> IdeColors.FUNCTION

            KotlinLexicalKind.Number -> IdeColors.NUMBER

            KotlinLexicalKind.LineComment, KotlinLexicalKind.BlockComment -> IdeColors.COMMENT

            KotlinLexicalKind.KDocTag -> IdeColors.KEYWORD

            KotlinLexicalKind.KDocSubject -> IdeColors.ANNOTATION

            KotlinLexicalKind.KDocLink -> IdeColors.ACCENT

            KotlinLexicalKind.KDocCode -> IdeColors.TEXT

            KotlinLexicalKind.TypeLike -> IdeColors.TYPE

            KotlinLexicalKind.Annotation -> IdeColors.ANNOTATION

            else -> IdeColors.EDITOR_TEXT
        }

    private fun semanticColor(category: SemanticCategory): Int =
        when (category) {
            SemanticCategory.Class,
            SemanticCategory.Interface,
            SemanticCategory.Object,
            -> IdeColors.TYPE

            SemanticCategory.PeripheralProvider -> IdeColors.PERIPHERAL_PROVIDER

            SemanticCategory.TypeParameter -> IdeColors.TYPE_PARAMETER

            SemanticCategory.EnumEntry -> IdeColors.PROPERTY

            SemanticCategory.Function, SemanticCategory.ExtensionFunction -> IdeColors.FUNCTION

            SemanticCategory.Property -> IdeColors.PROPERTY

            SemanticCategory.LocalVariable, SemanticCategory.Parameter -> IdeColors.LOCAL_VARIABLE

            SemanticCategory.InferredExpression,
            SemanticCategory.SmartCastExpression,
            -> IdeColors.EDITOR_TEXT
        }

    fun diagnosticColor(severity: EditorDiagnosticSeverity): Int =
        when (severity) {
            EditorDiagnosticSeverity.Info -> IdeColors.INFO
            EditorDiagnosticSeverity.Warning -> IdeColors.WARNING
            EditorDiagnosticSeverity.Error -> IdeColors.ERROR
        }
}

internal fun SemanticCategory.contributesTextStyle(): Boolean =
    this != SemanticCategory.InferredExpression && this != SemanticCategory.SmartCastExpression
