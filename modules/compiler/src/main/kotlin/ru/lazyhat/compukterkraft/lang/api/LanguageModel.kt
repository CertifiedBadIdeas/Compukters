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
    val fields: List<RecordFieldDeclaration>,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ClassDeclaration(
    override val name: String,
    val constructorParameters: List<ClassConstructorParameter>,
    val members: List<ClassMemberDeclaration>,
    override val range: SourceRange,
) : TopLevelDeclaration

data class ClassConstructorParameter(
    val name: String,
    val type: TypeSyntax,
    val fieldMutability: FieldMutability?,
    val range: SourceRange,
)

enum class FieldMutability { VAL, VAR }

sealed interface ClassMemberDeclaration {
    val range: SourceRange
}

data class ClassFieldDeclaration(
    val name: String,
    val type: TypeSyntax?,
    val mutable: Boolean,
    val initializer: Expression,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassInitBlock(
    val body: BlockStatement,
    override val range: SourceRange,
) : ClassMemberDeclaration

data class ClassMethodDeclaration(
    val function: FunctionDeclaration,
    val static: Boolean,
    override val range: SourceRange,
) : ClassMemberDeclaration

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
    val qualifier: String? = null,
) {
    val displayName: String
        get() {
            val qualifiedName = qualifier?.let { "$it::$name" } ?: name
            return if (nullable) "$qualifiedName?" else qualifiedName
        }
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

data class AssignmentStatement(
    val name: String,
    val nameRange: SourceRange,
    val expression: Expression,
    override val range: SourceRange,
) : Statement

data class MemberAssignmentStatement(
    val receiver: Expression,
    val memberName: String,
    val memberRange: SourceRange,
    val expression: Expression,
    override val range: SourceRange,
) : Statement

data class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: Statement?,
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

data class WhenBranch(
    val values: List<Expression>,
    val body: BlockStatement,
    val range: SourceRange,
)

data class WhenStatement(
    val subject: Expression?,
    val branches: List<WhenBranch>,
    val elseBranch: BlockStatement?,
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

data class ThisExpression(
    override val range: SourceRange,
) : Expression

data class MemberAccessExpression(
    val receiver: Expression,
    val memberName: String,
    override val range: SourceRange,
) : Expression

/**
 * Namespace/scope resolution: `qualifier::name`.
 * The qualifier is a compile-time scope name (for example, a built-in module).
 */
data class ScopeAccessExpression(
    val qualifier: String,
    val name: String,
    val qualifierRange: SourceRange,
    override val range: SourceRange,
) : Expression

data class CallExpression(
    val callee: Expression,
    val arguments: List<CallArgument>,
    override val range: SourceRange,
) : Expression

sealed interface CallArgument {
    val expression: Expression
    val range: SourceRange
}

data class PositionalCallArgument(
    override val expression: Expression,
    override val range: SourceRange,
) : CallArgument

data class NamedCallArgument(
    val name: String,
    val nameRange: SourceRange,
    override val expression: Expression,
    override val range: SourceRange,
) : CallArgument

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
    val qualifier: String? = null,
) : Expression

data class LegacyRecordConstructionExpression(
    val typeName: String,
    val fields: List<RecordFieldInitializer>,
    override val range: SourceRange,
    val qualifier: String? = null,
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

enum class ModuleOrigin {
    BASE_VM,
    OPTIONAL_VM,
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
    ): BuiltinFunction? =
        globals.firstOrNull {
            it.name == name &&
                it.parameterTypes.size == argumentCount
        }

    fun builtinType(name: String): BuiltinType? = builtinTypes.firstOrNull { it.name == name }
}

data class BuiltinModule(
    val name: String,
    val documentation: String,
    val functions: List<BuiltinFunction>,
    val origin: ModuleOrigin = ModuleOrigin.BASE_VM,
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
    val classes: List<BytecodeClass> = emptyList(),
)

data class BytecodeClass(
    val name: String,
    val fields: List<BytecodeClassField>,
    val initFunctionIndex: Int?,
    val instanceMethods: Map<String, Int>,
    val staticMethods: Map<String, Int>,
)

data class BytecodeClassField(
    val name: String,
    val typeName: String,
    val mutable: Boolean,
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

    data class SetField(
        val fieldName: String,
    ) : Instruction

    data class ConstructRecord(
        val typeName: String,
        val fieldNames: List<String>,
    ) : Instruction

    data class ConstructClass(
        val className: String,
        val fieldNames: List<String>,
    ) : Instruction

    data class CallMethod(
        val methodName: String,
        val argumentCount: Int,
    ) : Instruction

    data class CallStaticMethod(
        val className: String,
        val methodName: String,
        val argumentCount: Int,
    ) : Instruction

    data class Binary(
        val operator: BinaryOperator,
    ) : Instruction

    data class Unary(
        val operator: UnaryOperator,
    ) : Instruction

    data object Return : Instruction
}
