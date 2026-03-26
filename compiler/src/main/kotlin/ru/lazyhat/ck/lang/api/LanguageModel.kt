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

package ru.lazyhat.ck.lang.api

data class SourceLocation(
    val offset: Int,
    val line: Int,
    val column: Int,
)

data class Token(
    val kind: TokenKind,
    val text: String,
    val range: SourceRange,
)

data class Program(
    val imports: List<ImportDeclaration>,
    val declarations: List<TopLevelDeclaration>,
    val range: SourceRange?,
)

data class ImportDeclaration(
    val moduleName: String,
    val range: SourceRange,
)

sealed interface TopLevelDeclaration {
    val name: String
    val range: SourceRange
}

data class FunctionDeclaration(
    override val name: String,
    val parameters: List<ParameterDeclaration>,
    val returnType: TypeSyntax?,
    val body: BlockStatement,
    override val range: SourceRange,
) : TopLevelDeclaration

data class StructDeclaration(
    override val name: String,
    val fields: List<RecordFieldDeclaration>,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ParameterDeclaration(
    val name: String,
    val type: TypeSyntax,
    val range: SourceRange,
)

data class RecordFieldDeclaration(
    val name: String,
    val type: TypeSyntax,
    val range: SourceRange,
)

data class TypeSyntax(
    val name: String,
    val nullable: Boolean = false,
    val range: SourceRange,
) {
    val displayName: String
        get() = if (nullable) "$name?" else name
}

sealed interface Statement {
    val range: SourceRange
}

data class BlockStatement(
    val statements: List<Statement>,
    override val range: SourceRange,
) : Statement

data class VariableDeclarationStatement(
    val mutable: Boolean,
    val name: String,
    val type: TypeSyntax?,
    val initializer: Expression,
    override val range: SourceRange,
) : Statement

data class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: BlockStatement?,
    override val range: SourceRange,
) : Statement

data class WhileStatement(
    val condition: Expression,
    val body: BlockStatement,
    override val range: SourceRange,
) : Statement

data class ReturnStatement(
    val expression: Expression?,
    override val range: SourceRange,
) : Statement

data class ExpressionStatement(
    val expression: Expression,
    override val range: SourceRange,
) : Statement

sealed interface Expression {
    val range: SourceRange
}

data class LiteralExpression(
    val value: LiteralValue,
    override val range: SourceRange,
) : Expression

data class NameExpression(
    val name: String,
    override val range: SourceRange,
) : Expression

data class MemberAccessExpression(
    val receiver: Expression,
    val memberName: String,
    override val range: SourceRange,
) : Expression

data class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    override val range: SourceRange,
) : Expression

data class UnaryExpression(
    val operator: UnaryOperator,
    val operand: Expression,
    override val range: SourceRange,
) : Expression

data class BinaryExpression(
    val left: Expression,
    val operator: BinaryOperator,
    val right: Expression,
    override val range: SourceRange,
) : Expression

data class GroupExpression(
    val expression: Expression,
    override val range: SourceRange,
) : Expression

data class RecordConstructionExpression(
    val typeName: String,
    val fields: List<RecordFieldInitializer>,
    override val range: SourceRange,
) : Expression

data class RecordFieldInitializer(
    val name: String,
    val expression: Expression,
    val range: SourceRange,
)

sealed interface LiteralValue

data class IntLiteralValue(
    val value: Int,
) : LiteralValue

data class LongLiteralValue(
    val value: Long,
) : LiteralValue

data class StringLiteralValue(
    val value: String,
) : LiteralValue

data class BoolLiteralValue(
    val value: Boolean,
) : LiteralValue

data object NullLiteralValue : LiteralValue

enum class UnaryOperator {
    NEGATE,
    NOT,
}

enum class BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    EQUALS,
    NOT_EQUALS,
    LESS,
    LESS_EQUALS,
    GREATER,
    GREATER_EQUALS,
    AND,
    OR,
}

data class BuiltinRegistry(
    val modules: List<BuiltinModule>,
    val globals: List<BuiltinFunction>,
    val builtinTypes: List<BuiltinType>,
) {
    fun module(name: String): BuiltinModule? = modules.firstOrNull { it.name == name }

    fun global(
        name: String,
        argumentCount: Int,
    ): BuiltinFunction? = globals.firstOrNull { it.name == name && it.parameterTypes.size == argumentCount }

    fun builtinType(name: String): BuiltinType? = builtinTypes.firstOrNull { it.name == name }
}

data class BuiltinModule(
    val name: String,
    val documentation: String,
    val functions: List<BuiltinFunction>,
)

data class BuiltinFunction(
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val documentation: String,
)

data class BuiltinType(
    val name: String,
    val documentation: String,
    val fields: List<RecordFieldDefinition> = emptyList(),
)

data class RecordFieldDefinition(
    val name: String,
    val typeName: String,
    val documentation: String? = null,
)

data class BytecodeModule(
    val name: String,
    val functions: List<BytecodeFunction>,
    val records: List<BytecodeRecord>,
    val entryFunctionIndex: Int,
    val registry: BuiltinRegistry,
)

data class BytecodeFunction(
    val name: String,
    val parameters: List<BytecodeLocal>,
    val locals: List<BytecodeLocal>,
    val returnType: String,
    val instructions: List<Instruction>,
    val sourceRange: SourceRange?,
)

data class BytecodeLocal(
    val name: String,
    val typeName: String,
)

data class BytecodeRecord(
    val name: String,
    val fields: List<RecordFieldDefinition>,
)

sealed interface Instruction {
    data class PushInt(
        val value: Int,
    ) : Instruction

    data class PushLong(
        val value: Long,
    ) : Instruction

    data class PushString(
        val value: String,
    ) : Instruction

    data class PushBool(
        val value: Boolean,
    ) : Instruction

    data object PushUnit : Instruction

    data object PushNull : Instruction

    data class LoadLocal(
        val slot: Int,
    ) : Instruction

    data class StoreLocal(
        val slot: Int,
    ) : Instruction

    data object Pop : Instruction

    data class Jump(
        val target: Int,
    ) : Instruction

    data class JumpIfFalse(
        val target: Int,
    ) : Instruction

    data class CallFunction(
        val functionIndex: Int,
        val argumentCount: Int,
    ) : Instruction

    data class CallBuiltin(
        val moduleName: String?,
        val functionName: String,
        val argumentCount: Int,
    ) : Instruction

    data class GetField(
        val fieldName: String,
    ) : Instruction

    data class ConstructRecord(
        val typeName: String,
        val fieldNames: List<String>,
    ) : Instruction

    data class Binary(
        val operator: BinaryOperator,
    ) : Instruction

    data class Unary(
        val operator: UnaryOperator,
    ) : Instruction

    data object Return : Instruction
}
