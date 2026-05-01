/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.lang.frontend

import ru.lazyhat.compukterkraft.lang.api.AssignmentStatement
import ru.lazyhat.compukterkraft.lang.api.BinaryExpression
import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BlockStatement
import ru.lazyhat.compukterkraft.lang.api.BoolLiteralValue
import ru.lazyhat.compukterkraft.lang.api.CallArgument
import ru.lazyhat.compukterkraft.lang.api.CallExpression
import ru.lazyhat.compukterkraft.lang.api.ClassDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassFieldDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassInitBlock
import ru.lazyhat.compukterkraft.lang.api.ClassMemberDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassMethodDeclaration
import ru.lazyhat.compukterkraft.lang.api.Expression
import ru.lazyhat.compukterkraft.lang.api.ExpressionStatement
import ru.lazyhat.compukterkraft.lang.api.FieldMutability
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.GroupExpression
import ru.lazyhat.compukterkraft.lang.api.IfStatement
import ru.lazyhat.compukterkraft.lang.api.ImportDeclaration
import ru.lazyhat.compukterkraft.lang.api.ImportMode
import ru.lazyhat.compukterkraft.lang.api.ImportSource
import ru.lazyhat.compukterkraft.lang.api.IntLiteralValue
import ru.lazyhat.compukterkraft.lang.api.LegacyRecordConstructionExpression
import ru.lazyhat.compukterkraft.lang.api.LiteralExpression
import ru.lazyhat.compukterkraft.lang.api.LongLiteralValue
import ru.lazyhat.compukterkraft.lang.api.MemberAccessExpression
import ru.lazyhat.compukterkraft.lang.api.MemberAssignmentStatement
import ru.lazyhat.compukterkraft.lang.api.NamedCallArgument
import ru.lazyhat.compukterkraft.lang.api.NameExpression
import ru.lazyhat.compukterkraft.lang.api.NullLiteralValue
import ru.lazyhat.compukterkraft.lang.api.PositionalCallArgument
import ru.lazyhat.compukterkraft.lang.api.RecordConstructionExpression
import ru.lazyhat.compukterkraft.lang.api.ReturnStatement
import ru.lazyhat.compukterkraft.lang.api.ScopeAccessExpression
import ru.lazyhat.compukterkraft.lang.api.Statement
import ru.lazyhat.compukterkraft.lang.api.StringLiteralValue
import ru.lazyhat.compukterkraft.lang.api.StructDeclaration
import ru.lazyhat.compukterkraft.lang.api.ThisExpression
import ru.lazyhat.compukterkraft.lang.api.TopLevelDeclaration
import ru.lazyhat.compukterkraft.lang.api.TypeSyntax
import ru.lazyhat.compukterkraft.lang.api.UnaryExpression
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import ru.lazyhat.compukterkraft.lang.api.VariableDeclarationStatement
import ru.lazyhat.compukterkraft.lang.api.WhenStatement
import ru.lazyhat.compukterkraft.lang.api.WhileStatement
import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit

data class FormatOptions(
    val cleanup: Boolean = false,
)

data class FormatResult(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val changed: Boolean
        get() = edits.isNotEmpty()
}

class LanguageFormatter(
    private val parser: ParserFacade = DefaultParserFacade(),
) {
    fun formatDocument(
        name: String,
        source: String,
    ): FormatResult {
        val parsed = parser.parse(name, source)
        if (parsed.syntaxDiagnostics.any { it.severity == FrontendSeverity.ERROR }) {
            return cannotFormat()
        }
        val formatted = renderCanonical(parsed)
        return if (formatted == source) {
            FormatResult(emptyList())
        } else {
            FormatResult(listOf(TextEdit(0, source.length, formatted)))
        }
    }

    fun cleanupDocument(
        name: String,
        source: String,
        loader: SourceLoader = NoOpSourceLoader,
    ): FormatResult = formatDocument(name, source)

    private fun cannotFormat(): FormatResult =
        FormatResult(
            edits = emptyList(),
            diagnostics =
                listOf(
                    Diagnostic(
                        message = "Cannot format source with syntax errors.",
                        severity = IdeDiagnosticSeverity.ERROR,
                    ),
                ),
        )

    private fun renderCanonical(parsed: ParsedSource): String {
        val writer = CklWriter()
        val imports = parsed.program.imports.sortedWith(compareBy({ it.source.displayText() }, { it.range.start.offset }))
        imports.forEachIndexed { index, declaration ->
            if (index > 0) writer.line()
            writer.write(renderImport(declaration))
        }
        if (imports.isNotEmpty() && parsed.program.declarations.isNotEmpty()) writer.blankLine()
        parsed.program.declarations.forEachIndexed { index, declaration ->
            if (index > 0) writer.blankLine()
            renderTopLevel(writer, declaration)
        }
        return writer.result()
    }

    private fun renderImport(declaration: ImportDeclaration): String {
        val source = declaration.source.displayText()
        val mode = declaration.mode
        return when (mode) {
            is ImportMode.Invalid -> "import $source;"
            is ImportMode.Namespace -> "import $source as ${mode.alias};"
            is ImportMode.Selective -> "import $source { ${mode.items.joinToString(", ") { it.name }} };"
        }
    }

    private fun renderTopLevel(
        writer: CklWriter,
        declaration: TopLevelDeclaration,
    ) {
        when (declaration) {
            is ClassDeclaration -> renderClass(writer, declaration)
            is FunctionDeclaration -> renderFunction(writer, declaration, static = false)
            is StructDeclaration -> renderStruct(writer, declaration)
        }
    }

    private fun renderStruct(
        writer: CklWriter,
        declaration: StructDeclaration,
    ) {
        writer.write("struct ${declaration.name} { ")
        writer.write(declaration.fields.joinToString(", ") { "${it.name}: ${renderType(it.type)}" })
        writer.write(" }")
        writer.line()
    }

    private fun renderClass(
        writer: CklWriter,
        declaration: ClassDeclaration,
    ) {
        val parameters =
            declaration.constructorParameters.joinToString(", ") { parameter ->
                val prefix =
                    when (parameter.fieldMutability) {
                        FieldMutability.VAL -> "val "
                        FieldMutability.VAR -> "var "
                        null -> ""
                    }
                "$prefix${parameter.name}: ${renderType(parameter.type)}"
            }
        writer.write("class ${declaration.name}($parameters)")
        renderClassBody(writer, declaration.members)
    }

    private fun renderClassBody(
        writer: CklWriter,
        members: List<ClassMemberDeclaration>,
    ) {
        writer.write(" {")
        writer.line()
        writer.indented {
            members.forEachIndexed { index, member ->
                if (index > 0) writer.blankLine()
                when (member) {
                    is ClassFieldDeclaration -> {
                        writer.write(if (member.mutable) "var " else "val ")
                        writer.write(member.name)
                        member.type?.let { writer.write(": ${renderType(it)}") }
                        writer.write(" = ${renderExpression(member.initializer)};")
                        writer.line()
                    }
                    is ClassInitBlock -> {
                        writer.write("init")
                        renderBlock(writer, member.body)
                    }
                    is ClassMethodDeclaration -> renderFunction(writer, member.function, static = member.static)
                }
            }
        }
        writer.write("}")
        writer.line()
    }

    private fun renderFunction(
        writer: CklWriter,
        declaration: FunctionDeclaration,
        static: Boolean,
    ) {
        if (static) writer.write("static ")
        writer.write("fun ${declaration.name}(")
        writer.write(declaration.parameters.joinToString(", ") { "${it.name}: ${renderType(it.type)}" })
        writer.write(")")
        declaration.returnType?.let { writer.write(": ${renderType(it)}") }
        renderBlock(writer, declaration.body)
    }

    private fun renderBlock(
        writer: CklWriter,
        block: BlockStatement,
    ) {
        writer.write(" {")
        writer.line()
        writer.indented {
            block.statements.forEach { renderStatement(writer, it) }
        }
        writer.write("}")
        writer.line()
    }

    private fun renderStatement(
        writer: CklWriter,
        statement: Statement,
    ) {
        when (statement) {
            is AssignmentStatement -> {
                writer.write("${statement.name} = ${renderExpression(statement.expression)};")
                writer.line()
            }
            is BlockStatement -> renderBlock(writer, statement)
            is ExpressionStatement -> {
                writer.write("${renderExpression(statement.expression)};")
                writer.line()
            }
            is IfStatement -> renderIf(writer, statement)
            is MemberAssignmentStatement -> {
                writer.write("${renderExpression(statement.receiver)}.${statement.memberName} = ${renderExpression(statement.expression)};")
                writer.line()
            }
            is ReturnStatement -> {
                writer.write("return")
                statement.expression?.let { writer.write(" ${renderExpression(it)}") }
                writer.write(";")
                writer.line()
            }
            is VariableDeclarationStatement -> {
                writer.write(if (statement.mutable) "var " else "val ")
                writer.write(statement.name)
                statement.type?.let { writer.write(": ${renderType(it)}") }
                writer.write(" = ${renderExpression(statement.initializer)};")
                writer.line()
            }
            is WhenStatement -> renderWhen(writer, statement)
            is WhileStatement -> {
                writer.write("while ${renderExpression(statement.condition)}")
                renderBlock(writer, statement.body)
            }
        }
    }

    private fun renderIf(
        writer: CklWriter,
        statement: IfStatement,
    ) {
        writer.write("if (${renderExpression(statement.condition)})")
        renderBlockInline(writer, statement.thenBranch)
        statement.elseBranch?.let { elseBranch ->
            when (elseBranch) {
                is BlockStatement -> {
                    writer.write(" else")
                    renderBlockInline(writer, elseBranch)
                    writer.line()
                }
                is IfStatement -> {
                    writer.write(" else ")
                    renderIf(writer, elseBranch)
                }
                else -> {
                    writer.write(" else ")
                    renderStatement(writer, elseBranch)
                }
            }
        } ?: writer.line()
    }

    private fun renderBlockInline(
        writer: CklWriter,
        block: BlockStatement,
    ) {
        writer.write(" {")
        writer.line()
        writer.indented {
            block.statements.forEach { renderStatement(writer, it) }
        }
        writer.write("}")
    }

    private fun renderWhen(
        writer: CklWriter,
        statement: WhenStatement,
    ) {
        writer.write("when")
        statement.subject?.let { writer.write("(${renderExpression(it)})") }
        writer.write(" {")
        writer.line()
        writer.indented {
            statement.branches.forEach { branch ->
                writer.write(branch.values.joinToString(", ") { renderExpression(it) })
                writer.write(" ->")
                renderBlock(writer, branch.body)
            }
            statement.elseBranch?.let { elseBranch ->
                writer.write("else ->")
                renderBlock(writer, elseBranch)
            }
        }
        writer.write("}")
        writer.line()
    }

    private fun renderExpression(
        expression: Expression,
        parentPrecedence: Int = 0,
    ): String {
        val rendered =
            when (expression) {
                is BinaryExpression -> renderBinary(expression)
                is CallExpression -> "${renderExpression(expression.callee, PRECEDENCE_CALL)}(${expression.arguments.joinToString(", ") { renderCallArgument(it) }})"
                is GroupExpression -> "(${renderExpression(expression.expression)})"
                is LegacyRecordConstructionExpression -> renderLegacyRecordConstruction(expression)
                is LiteralExpression -> renderLiteral(expression)
                is MemberAccessExpression -> "${renderExpression(expression.receiver, PRECEDENCE_CALL)}.${expression.memberName}"
                is NameExpression -> expression.name
                is RecordConstructionExpression -> renderRecordConstruction(expression)
                is ScopeAccessExpression -> "${expression.qualifier}::${expression.name}"
                is ThisExpression -> "this"
                is UnaryExpression -> renderUnary(expression)
            }
        return if (expression.precedence() < parentPrecedence) "($rendered)" else rendered
    }

    private fun renderBinary(expression: BinaryExpression): String {
        val precedence = expression.operator.precedence()
        val left = renderExpression(expression.left, precedence)
        val right = renderExpression(expression.right, precedence + 1)
        return "$left ${expression.operator.symbol()} $right"
    }

    private fun renderUnary(expression: UnaryExpression): String =
        "${expression.operator.symbol()}${renderExpression(expression.operand, PRECEDENCE_UNARY)}"

    private fun renderLiteral(expression: LiteralExpression): String =
        when (val value = expression.value) {
            is BoolLiteralValue -> value.value.toString()
            is IntLiteralValue -> value.value.toString()
            is LongLiteralValue -> "${value.value}L"
            NullLiteralValue -> "null"
            is StringLiteralValue -> "\"${value.value.escapeString()}\""
        }

    private fun renderRecordConstruction(expression: RecordConstructionExpression): String {
        val qualifier = expression.qualifier?.let { "$it::" }.orEmpty()
        return "$qualifier${expression.typeName}(${expression.fields.joinToString(", ") { "${it.name} = ${renderExpression(it.expression)}" }})"
    }

    private fun renderLegacyRecordConstruction(expression: LegacyRecordConstructionExpression): String {
        val qualifier = expression.qualifier?.let { "$it::" }.orEmpty()
        return "$qualifier${expression.typeName} { ${expression.fields.joinToString(", ") { "${it.name}: ${renderExpression(it.expression)}" }} }"
    }

    private fun renderCallArgument(argument: CallArgument): String =
        when (argument) {
            is NamedCallArgument -> "${argument.name} = ${renderExpression(argument.expression)}"
            is PositionalCallArgument -> renderExpression(argument.expression)
        }

    private fun renderType(type: TypeSyntax): String = type.displayName
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

private class CklWriter {
    private val builder = StringBuilder()
    private var indentLevel = 0
    private var lineStart = true

    fun write(text: String) {
        if (lineStart && text.isNotEmpty()) {
            repeat(indentLevel) { builder.append("    ") }
            lineStart = false
        }
        builder.append(text)
    }

    fun line() {
        builder.append('\n')
        lineStart = true
    }

    fun blankLine() {
        if (!builder.endsWith("\n")) line()
        if (!builder.endsWith("\n\n")) builder.append('\n')
        lineStart = true
    }

    fun indented(block: () -> Unit) {
        indentLevel += 1
        try {
            block()
        } finally {
            indentLevel -= 1
        }
    }

    fun result(): String = builder.toString().trimEnd() + "\n"
}

private const val PRECEDENCE_OR = 1
private const val PRECEDENCE_AND = 2
private const val PRECEDENCE_EQUALITY = 3
private const val PRECEDENCE_COMPARISON = 4
private const val PRECEDENCE_TERM = 5
private const val PRECEDENCE_FACTOR = 6
private const val PRECEDENCE_UNARY = 7
private const val PRECEDENCE_CALL = 8
private const val PRECEDENCE_PRIMARY = 9

private fun ImportSource.displayText(): String =
    when (this) {
        is ImportSource.BuiltinNamespace -> name
        is ImportSource.FilePath -> "\"$path\""
    }

private fun Expression.precedence(): Int =
    when (this) {
        is BinaryExpression -> operator.precedence()
        is UnaryExpression -> PRECEDENCE_UNARY
        is CallExpression, is MemberAccessExpression, is ScopeAccessExpression -> PRECEDENCE_CALL
        else -> PRECEDENCE_PRIMARY
    }

private fun BinaryOperator.precedence(): Int =
    when (this) {
        BinaryOperator.OR -> PRECEDENCE_OR
        BinaryOperator.AND -> PRECEDENCE_AND
        BinaryOperator.EQUALS, BinaryOperator.NOT_EQUALS -> PRECEDENCE_EQUALITY
        BinaryOperator.LESS, BinaryOperator.LESS_EQUALS, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUALS -> PRECEDENCE_COMPARISON
        BinaryOperator.ADD, BinaryOperator.SUBTRACT -> PRECEDENCE_TERM
        BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE -> PRECEDENCE_FACTOR
    }

private fun BinaryOperator.symbol(): String =
    when (this) {
        BinaryOperator.ADD -> "+"
        BinaryOperator.SUBTRACT -> "-"
        BinaryOperator.MULTIPLY -> "*"
        BinaryOperator.DIVIDE -> "/"
        BinaryOperator.EQUALS -> "=="
        BinaryOperator.NOT_EQUALS -> "!="
        BinaryOperator.LESS -> "<"
        BinaryOperator.LESS_EQUALS -> "<="
        BinaryOperator.GREATER -> ">"
        BinaryOperator.GREATER_EQUALS -> ">="
        BinaryOperator.AND -> "&&"
        BinaryOperator.OR -> "||"
    }

private fun UnaryOperator.symbol(): String =
    when (this) {
        UnaryOperator.NEGATE -> "-"
        UnaryOperator.NOT -> "!"
    }

private fun String.escapeString(): String =
    buildString {
        this@escapeString.forEach { ch ->
            append(
                when (ch) {
                    '\n' -> "\\n"
                    '\t' -> "\\t"
                    '"' -> "\\\""
                    '\\' -> "\\\\"
                    else -> ch.toString()
                },
            )
        }
    }
