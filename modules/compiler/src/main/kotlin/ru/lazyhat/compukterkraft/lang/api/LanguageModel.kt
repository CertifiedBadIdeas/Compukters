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

package ru.lazyhat.compukterkraft.lang.api

data class StructDeclaration(
    override val name: String,
    val fields: List<ru.lazyhat.compukterkraft.lang.api.RecordFieldDeclaration>,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.TopLevelDeclaration

data class ParameterDeclaration(
    val name: String,
    val type: ru.lazyhat.compukterkraft.lang.api.TypeSyntax,
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
)

data class RecordFieldDeclaration(
    val name: String,
    val type: ru.lazyhat.compukterkraft.lang.api.TypeSyntax,
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
)

data class TypeSyntax(
    val name: String,
    val nullable: Boolean = false,
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
    val qualifier: String? = null,
) {
    val displayName: String
        get() {
            val qualifiedName = qualifier?.let { "$it::$name" } ?: name
            return if (nullable) "$qualifiedName?" else qualifiedName
        }
}

sealed interface Statement {
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange
}

data class BlockStatement(
    val statements: List<ru.lazyhat.compukterkraft.lang.api.Statement>,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class VariableDeclarationStatement(
    val mutable: Boolean,
    val name: String,
    val type: ru.lazyhat.compukterkraft.lang.api.TypeSyntax?,
    val initializer: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class AssignmentStatement(
    val name: String,
    val nameRange: ru.lazyhat.compukterkraft.lang.api.SourceRange,
    val expression: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class IfStatement(
    val condition: ru.lazyhat.compukterkraft.lang.api.Expression,
    val thenBranch: ru.lazyhat.compukterkraft.lang.api.BlockStatement,
    val elseBranch: ru.lazyhat.compukterkraft.lang.api.Statement?,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class WhileStatement(
    val condition: ru.lazyhat.compukterkraft.lang.api.Expression,
    val body: ru.lazyhat.compukterkraft.lang.api.BlockStatement,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class ReturnStatement(
    val expression: ru.lazyhat.compukterkraft.lang.api.Expression?,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class ExpressionStatement(
    val expression: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

data class WhenBranch(
    val values: List<ru.lazyhat.compukterkraft.lang.api.Expression>,
    val body: ru.lazyhat.compukterkraft.lang.api.BlockStatement,
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
)

data class WhenStatement(
    val subject: ru.lazyhat.compukterkraft.lang.api.Expression?,
    val branches: List<ru.lazyhat.compukterkraft.lang.api.WhenBranch>,
    val elseBranch: ru.lazyhat.compukterkraft.lang.api.BlockStatement?,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Statement

sealed interface Expression {
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange
}

data class LiteralExpression(
    val value: ru.lazyhat.compukterkraft.lang.api.LiteralValue,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class NameExpression(
    val name: String,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class MemberAccessExpression(
    val receiver: ru.lazyhat.compukterkraft.lang.api.Expression,
    val memberName: String,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

/**
 * Namespace/scope resolution: `qualifier::name`.
 * The qualifier is a compile-time scope name (for example, a built-in module).
 */
data class ScopeAccessExpression(
    val qualifier: String,
    val name: String,
    val qualifierRange: ru.lazyhat.compukterkraft.lang.api.SourceRange,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class CallExpression(
    val callee: ru.lazyhat.compukterkraft.lang.api.Expression,
    val arguments: List<ru.lazyhat.compukterkraft.lang.api.Expression>,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class UnaryExpression(
    val operator: ru.lazyhat.compukterkraft.lang.api.UnaryOperator,
    val operand: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class BinaryExpression(
    val left: ru.lazyhat.compukterkraft.lang.api.Expression,
    val operator: ru.lazyhat.compukterkraft.lang.api.BinaryOperator,
    val right: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class GroupExpression(
    val expression: ru.lazyhat.compukterkraft.lang.api.Expression,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class RecordConstructionExpression(
    val typeName: String,
    val fields: List<ru.lazyhat.compukterkraft.lang.api.RecordFieldInitializer>,
    override val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
    val qualifier: String? = null,
) : ru.lazyhat.compukterkraft.lang.api.Expression

data class RecordFieldInitializer(
    val name: String,
    val expression: ru.lazyhat.compukterkraft.lang.api.Expression,
    val range: ru.lazyhat.compukterkraft.lang.api.SourceRange,
)

sealed interface LiteralValue

data class IntLiteralValue(
    val value: Int,
) : ru.lazyhat.compukterkraft.lang.api.LiteralValue

data class LongLiteralValue(
    val value: Long,
) : ru.lazyhat.compukterkraft.lang.api.LiteralValue

data class StringLiteralValue(
    val value: String,
) : ru.lazyhat.compukterkraft.lang.api.LiteralValue

data class BoolLiteralValue(
    val value: Boolean,
) : ru.lazyhat.compukterkraft.lang.api.LiteralValue

data object NullLiteralValue : ru.lazyhat.compukterkraft.lang.api.LiteralValue

enum class ModuleOrigin {
    BASE_VM,
    OPTIONAL_VM,
}

data class BuiltinRegistry(
    val modules: List<ru.lazyhat.compukterkraft.lang.api.BuiltinModule>,
    val globals: List<ru.lazyhat.compukterkraft.lang.api.BuiltinFunction>,
    val builtinTypes: List<ru.lazyhat.compukterkraft.lang.api.BuiltinType>,
) {
    fun module(name: String): ru.lazyhat.compukterkraft.lang.api.BuiltinModule? = modules.firstOrNull { it.name == name }

    fun global(
        name: String,
        argumentCount: Int,
    ): ru.lazyhat.compukterkraft.lang.api.BuiltinFunction? =
        globals.firstOrNull {
            it.name == name &&
                it.parameterTypes.size == argumentCount
        }

    fun builtinType(name: String): ru.lazyhat.compukterkraft.lang.api.BuiltinType? = builtinTypes.firstOrNull { it.name == name }
}

data class BuiltinModule(
    val name: String,
    val documentation: String,
    val functions: List<ru.lazyhat.compukterkraft.lang.api.BuiltinFunction>,
    val origin: ru.lazyhat.compukterkraft.lang.api.ModuleOrigin = ru.lazyhat.compukterkraft.lang.api.ModuleOrigin.BASE_VM,
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
    val fields: List<ru.lazyhat.compukterkraft.lang.api.RecordFieldDefinition> = emptyList(),
)

data class RecordFieldDefinition(
    val name: String,
    val typeName: String,
    val documentation: String? = null,
)

data class BytecodeModule(
    val name: String,
    val functions: List<ru.lazyhat.compukterkraft.lang.api.BytecodeFunction>,
    val records: List<ru.lazyhat.compukterkraft.lang.api.BytecodeRecord>,
    val entryFunctionIndex: Int,
    val registry: ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry,
)

data class BytecodeFunction(
    val name: String,
    val parameters: List<ru.lazyhat.compukterkraft.lang.api.BytecodeLocal>,
    val locals: List<ru.lazyhat.compukterkraft.lang.api.BytecodeLocal>,
    val returnType: String,
    val instructions: List<ru.lazyhat.compukterkraft.lang.api.Instruction>,
    val sourceRange: ru.lazyhat.compukterkraft.lang.api.SourceRange?,
)

data class BytecodeLocal(
    val name: String,
    val typeName: String,
)

data class BytecodeRecord(
    val name: String,
    val fields: List<ru.lazyhat.compukterkraft.lang.api.RecordFieldDefinition>,
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

    data class JumpIfTrue(
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
        val operator: ru.lazyhat.compukterkraft.lang.api.BinaryOperator,
    ) : Instruction

    data class Unary(
        val operator: ru.lazyhat.compukterkraft.lang.api.UnaryOperator,
    ) : Instruction

    data object Return : Instruction
}
