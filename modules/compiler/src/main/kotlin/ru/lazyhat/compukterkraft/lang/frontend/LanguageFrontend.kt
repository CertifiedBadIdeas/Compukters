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
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeLocal
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.BytecodeRecord
import ru.lazyhat.compukterkraft.lang.api.CallExpression
import ru.lazyhat.compukterkraft.lang.api.Expression
import ru.lazyhat.compukterkraft.lang.api.ExpressionStatement
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.GroupExpression
import ru.lazyhat.compukterkraft.lang.api.IfStatement
import ru.lazyhat.compukterkraft.lang.api.ImportDeclaration
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.IntLiteralValue
import ru.lazyhat.compukterkraft.lang.api.LiteralExpression
import ru.lazyhat.compukterkraft.lang.api.LongLiteralValue
import ru.lazyhat.compukterkraft.lang.api.MemberAccessExpression
import ru.lazyhat.compukterkraft.lang.api.NameExpression
import ru.lazyhat.compukterkraft.lang.api.NullLiteralValue
import ru.lazyhat.compukterkraft.lang.api.ParameterDeclaration
import ru.lazyhat.compukterkraft.lang.api.Program
import ru.lazyhat.compukterkraft.lang.api.RecordConstructionExpression
import ru.lazyhat.compukterkraft.lang.api.RecordFieldDeclaration
import ru.lazyhat.compukterkraft.lang.api.RecordFieldDefinition
import ru.lazyhat.compukterkraft.lang.api.RecordFieldInitializer
import ru.lazyhat.compukterkraft.lang.api.ReturnStatement
import ru.lazyhat.compukterkraft.lang.api.ScopeAccessExpression
import ru.lazyhat.compukterkraft.lang.api.SourceLocation
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.api.Statement
import ru.lazyhat.compukterkraft.lang.api.StringLiteralValue
import ru.lazyhat.compukterkraft.lang.api.StructDeclaration
import ru.lazyhat.compukterkraft.lang.api.Token
import ru.lazyhat.compukterkraft.lang.api.TokenKind
import ru.lazyhat.compukterkraft.lang.api.TopLevelDeclaration
import ru.lazyhat.compukterkraft.lang.api.TypeSyntax
import ru.lazyhat.compukterkraft.lang.api.UnaryExpression
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import ru.lazyhat.compukterkraft.lang.api.VariableDeclarationStatement
import ru.lazyhat.compukterkraft.lang.api.WhenBranch
import ru.lazyhat.compukterkraft.lang.api.WhenStatement
import ru.lazyhat.compukterkraft.lang.api.WhileStatement
import java.util.IdentityHashMap

class LanguageFrontend(
    val registry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
) {
    private val analyzer: AnalyzerFacade =
        DefaultAnalyzerFacade(registry)
    private val compiler: CompilerFacade =
        DefaultCompilerFacade(registry, analyzer)

    fun analyze(
        name: String,
        source: String,
    ): AnalyzedProgram = analyzer.analyze(name, source)

    fun compile(
        name: String,
        source: String,
    ): CompilationArtifact = compiler.compile(name, source)
}

internal data class TypeRef(
    val name: String,
    val nullable: Boolean = false,
) {
    val displayName: String
        get() = if (nullable) "$name?" else name
}

internal sealed interface Binding {
    val symbol: SymbolInfo
}

internal data class VariableBinding(
    override val symbol: SymbolInfo,
    val type: TypeRef,
    val mutable: Boolean = false,
) : Binding

internal data class FunctionBinding(
    override val symbol: SymbolInfo,
    val declaration: FunctionDeclaration?,
    val parameterTypes: List<TypeRef>,
    val returnType: TypeRef,
    val builtinModuleName: String? = null,
) : Binding

internal data class RecordBinding(
    override val symbol: SymbolInfo,
    val declaration: StructDeclaration?,
    val fields: Map<String, TypeRef>,
) : Binding

internal data class ModuleBinding(
    override val symbol: SymbolInfo,
    val module: BuiltinModule,
) : Binding

internal data class MemberBinding(
    override val symbol: SymbolInfo,
    val ownerType: TypeRef,
    val type: TypeRef,
) : Binding

internal data class SemanticResult(
    val diagnostics: List<FrontendDiagnostic>,
    val symbols: List<SymbolInfo>,
    val references: List<ReferenceInfo>,
    val functionBindings: Map<FunctionDeclaration, FunctionBinding>,
    val recordBindings: Map<StructDeclaration, RecordBinding>,
    val localBindings: IdentityHashMap<Expression, Binding>,
    val expressionTypes: IdentityHashMap<Expression, TypeRef>,
    val callBindings: IdentityHashMap<CallExpression, FunctionBinding>,
    val memberBindings: IdentityHashMap<MemberAccessExpression, Binding>,
    val program: Program,
)

internal class SemanticAnalyzer(
    private val registry: BuiltinRegistry,
    private val sourceName: String,
) {
    private val diagnostics = mutableListOf<FrontendDiagnostic>()
    private val symbols = mutableListOf<SymbolInfo>()
    private val references = mutableListOf<ReferenceInfo>()
    private val functionBindings = mutableMapOf<FunctionDeclaration, FunctionBinding>()
    private val recordBindings = mutableMapOf<StructDeclaration, RecordBinding>()
    private val localBindings = IdentityHashMap<Expression, Binding>()
    private val expressionTypes = IdentityHashMap<Expression, TypeRef>()
    private val callBindings = IdentityHashMap<CallExpression, FunctionBinding>()
    private val memberBindings = IdentityHashMap<MemberAccessExpression, Binding>()
    private val builtinModules = registry.modules.associateBy { it.name }

    private val typeNames: MutableMap<String, TypeRef> =
        registry.builtinTypes
            .associate {
                it.name to
                    TypeRef(
                        it.name,
                    )
            }.toMutableMap()

    private val importedModules = mutableMapOf<String, ModuleBinding>()
    private val userFunctionsByName = mutableMapOf<String, FunctionBinding>()
    private val userRecordsByName = mutableMapOf<String, RecordBinding>()

    fun analyze(program: Program): SemanticResult {
        registerImports(program.imports)
        registerTopLevel(program.declarations)
        for (declaration in program.declarations) {
            when (declaration) {
                is FunctionDeclaration -> analyzeFunction(declaration)
                is StructDeclaration -> Unit
            }
        }
        return SemanticResult(
            diagnostics = diagnostics.toList(),
            symbols = symbols.toList(),
            references = references.toList(),
            functionBindings = functionBindings,
            recordBindings = recordBindings,
            localBindings = localBindings,
            expressionTypes = expressionTypes,
            callBindings = callBindings,
            memberBindings = memberBindings,
            program = program,
        )
    }

    private fun registerImports(imports: List<ImportDeclaration>) {
        imports.forEach { declaration ->
            val module = builtinModules[declaration.moduleName]
            if (module == null) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Runtime module `${declaration.moduleName}` is not supported by this VM.",
                        declaration.range,
                    )
                return@forEach
            }
            val symbol =
                SymbolInfo(
                    name = declaration.moduleName,
                    kind = SymbolKind.MODULE,
                    range = declaration.range,
                    detail = "module ${declaration.moduleName}",
                    documentation = module.documentation,
                )
            symbols += symbol
            importedModules[declaration.moduleName] =
                ModuleBinding(symbol, module)
        }
    }

    private fun registerTopLevel(declarations: List<TopLevelDeclaration>) {
        declarations.filterIsInstance<StructDeclaration>().forEach { declaration ->
            if (typeNames.containsKey(declaration.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Type `${declaration.name}` is already defined.",
                        declaration.range,
                    )
                return@forEach
            }
            val fields =
                declaration.fields.associate { field ->
                    field.name to
                        (
                            resolveType(field.type, field.range) ?: TypeRef(
                                "Unit",
                            )
                        )
                }
            val symbol =
                SymbolInfo(
                    name = declaration.name,
                    kind = SymbolKind.RECORD,
                    range = declaration.range,
                    detail = "struct ${declaration.name}",
                )
            typeNames[declaration.name] =
                TypeRef(declaration.name)
            val binding =
                RecordBinding(symbol, declaration, fields)
            symbols += symbol
            declaration.fields.forEach { field ->
                symbols +=
                    SymbolInfo(
                        name = field.name,
                        kind = SymbolKind.FIELD,
                        range = field.range,
                        detail = "${declaration.name}.${field.name}: ${fields[field.name]?.displayName ?: field.type.displayName}",
                    )
            }
            recordBindings[declaration] = binding
            userRecordsByName[declaration.name] = binding
        }
        declarations.filterIsInstance<FunctionDeclaration>().forEach { declaration ->
            if (userFunctionsByName.containsKey(declaration.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Function `${declaration.name}` is already defined.",
                        declaration.range,
                    )
                return@forEach
            }
            val parameterTypes =
                declaration.parameters.map {
                    resolveType(it.type, it.range)
                        ?: TypeRef(
                            "Unit",
                        )
                }
            val returnType =
                declaration.returnType?.let { resolveType(it, it.range) }
                    ?: TypeRef(
                        "Unit",
                    )
            val symbol =
                SymbolInfo(
                    name = declaration.name,
                    kind = SymbolKind.FUNCTION,
                    range = declaration.range,
                    detail = "fun ${declaration.name}(${parameterTypes.joinToString { it.displayName }}) : ${returnType.displayName}",
                )
            symbols += symbol
            val binding =
                FunctionBinding(
                    symbol,
                    declaration,
                    parameterTypes,
                    returnType,
                )
            functionBindings[declaration] = binding
            userFunctionsByName[declaration.name] = binding
        }
    }

    private fun analyzeFunction(declaration: FunctionDeclaration) {
        val binding = functionBindings[declaration] ?: return
        val scope = Scope(null)
        declaration.parameters.forEachIndexed { index, parameter ->
            val type = binding.parameterTypes[index]
            val symbol =
                SymbolInfo(
                    name = parameter.name,
                    kind = SymbolKind.PARAMETER,
                    range = parameter.range,
                    detail = "${parameter.name}: ${type.displayName}",
                    ownerFunctionRange = declaration.range,
                )
            symbols += symbol
            scope.define(
                parameter.name,
                VariableBinding(symbol, type, mutable = false),
            )
        }
        analyzeBlock(declaration.body, scope, declaration.range, binding.returnType)
    }

    private fun analyzeBlock(
        block: BlockStatement,
        parentScope: Scope,
        functionRange: SourceRange,
        expectedReturnType: TypeRef,
    ) {
        val scope = Scope(parentScope)
        block.statements.forEach { statement ->
            analyzeStatement(statement, scope, functionRange, expectedReturnType)
        }
    }

    private fun analyzeStatement(
        statement: Statement,
        scope: Scope,
        functionRange: SourceRange,
        expectedReturnType: TypeRef,
    ) {
        when (statement) {
            is BlockStatement -> {
                analyzeBlock(statement, scope, functionRange, expectedReturnType)
            }

            is ExpressionStatement -> {
                analyzeExpression(statement.expression, scope)
            }

            is IfStatement -> {
                val conditionType = analyzeExpression(statement.condition, scope)
                expectAssignable(
                    conditionType,
                    TypeRef("Bool"),
                    statement.condition.range,
                    "Condition must be Bool.",
                )
                analyzeBlock(statement.thenBranch, scope, functionRange, expectedReturnType)
                statement.elseBranch?.let { analyzeStatement(it, scope, functionRange, expectedReturnType) }
            }

            is ReturnStatement -> {
                val actual =
                    statement.expression?.let { analyzeExpression(it, scope) }
                        ?: TypeRef(
                            "Unit",
                        )
                expectAssignable(actual, expectedReturnType, statement.range, "Return type mismatch.")
            }

            is VariableDeclarationStatement -> {
                val valueType = analyzeExpression(statement.initializer, scope)
                val declaredType =
                    statement.type?.let { resolveType(it, statement.range) } ?: valueType
                expectAssignable(valueType, declaredType, statement.range, "Initializer type mismatch.")
                val symbol =
                    SymbolInfo(
                        name = statement.name,
                        kind = SymbolKind.VARIABLE,
                        range = statement.range,
                        detail = "${if (statement.mutable) "var" else "val"} ${statement.name}: ${declaredType.displayName}",
                        ownerFunctionRange = functionRange,
                    )
                symbols += symbol
                scope.define(
                    statement.name,
                    VariableBinding(symbol, declaredType, mutable = statement.mutable),
                )
            }

            is AssignmentStatement -> {
                analyzeAssignment(statement, scope)
            }

            is WhileStatement -> {
                val conditionType = analyzeExpression(statement.condition, scope)
                expectAssignable(
                    conditionType,
                    TypeRef("Bool"),
                    statement.condition.range,
                    "Condition must be Bool.",
                )
                analyzeBlock(statement.body, scope, functionRange, expectedReturnType)
            }

            is WhenStatement -> {
                val subjectType = statement.subject?.let { analyzeExpression(it, scope) }
                for (branch in statement.branches) {
                    for (value in branch.values) {
                        val valueType = analyzeExpression(value, scope)
                        if (subjectType != null) {
                            if (!isAssignable(valueType, subjectType) && !isAssignable(subjectType, valueType)) {
                                diagnostics +=
                                    FrontendDiagnostic(
                                        "When branch value type mismatch.",
                                        value.range,
                                    )
                            }
                        } else {
                            expectAssignable(
                                valueType,
                                TypeRef("Bool"),
                                value.range,
                                "When condition must be Bool.",
                            )
                        }
                    }
                    analyzeBlock(branch.body, scope, functionRange, expectedReturnType)
                }
                statement.elseBranch?.let { analyzeBlock(it, scope, functionRange, expectedReturnType) }
            }
        }
    }

    private fun analyzeExpression(
        expression: Expression,
        scope: Scope,
    ): TypeRef =
        expressionTypes[expression]
            ?: when (expression) {
                is BinaryExpression -> {
                    analyzeBinary(expression, scope)
                }

                is CallExpression -> {
                    analyzeCall(expression, scope)
                }

                is GroupExpression -> {
                    analyzeExpression(expression.expression, scope)
                }

                is LiteralExpression -> {
                    analyzeLiteral(expression)
                }

                is MemberAccessExpression -> {
                    analyzeMember(expression, scope).let { (binding, type) ->
                        memberBindings[expression] = binding
                        type
                    }
                }

                is NameExpression -> {
                    analyzeName(expression, scope)
                }

                is RecordConstructionExpression -> {
                    analyzeRecordConstruction(expression, scope)
                }

                is ScopeAccessExpression -> {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Unknown namespace `${expression.qualifier}`.",
                            expression.qualifierRange,
                        )
                    TypeRef("Unit")
                }

                is UnaryExpression -> {
                    analyzeUnary(expression, scope)
                }
            }.also { expressionTypes[expression] = it }

    private fun analyzeLiteral(expression: LiteralExpression): TypeRef =
        when (expression.value) {
            is BoolLiteralValue -> {
                TypeRef("Bool")
            }

            is IntLiteralValue -> {
                TypeRef("Int")
            }

            is LongLiteralValue -> {
                TypeRef("Long")
            }

            is StringLiteralValue -> {
                TypeRef("String")
            }

            NullLiteralValue -> {
                TypeRef(
                    "Unit",
                    nullable = true,
                )
            }
        }

    private fun analyzeAssignment(
        statement: AssignmentStatement,
        scope: Scope,
    ) {
        val binding = scope.resolve(statement.name)
        if (binding == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Unknown variable `${statement.name}`.",
                    statement.nameRange,
                )
            analyzeExpression(statement.expression, scope)
            return
        }
        references +=
            ReferenceInfo(
                statement.name,
                statement.nameRange,
                binding.symbol,
                binding.type.displayName,
            )
        if (!binding.mutable) {
            diagnostics +=
                FrontendDiagnostic(
                    "Cannot reassign `val` `${statement.name}`. Declare it with `var` to allow mutation.",
                    statement.nameRange,
                )
        }
        val valueType = analyzeExpression(statement.expression, scope)
        expectAssignable(
            valueType,
            binding.type,
            statement.expression.range,
            "Assignment type mismatch.",
        )
    }

    private fun analyzeName(
        expression: NameExpression,
        scope: Scope,
    ): TypeRef {
        val local = scope.resolve(expression.name)
        if (local != null) {
            localBindings[expression] = local
            references +=
                ReferenceInfo(
                    expression.name,
                    expression.range,
                    local.symbol,
                    local.type.displayName,
                )
            return local.type
        }
        importedModules[expression.name]?.let { module ->
            localBindings[expression] = module
            references +=
                ReferenceInfo(
                    expression.name,
                    expression.range,
                    module.symbol,
                )
            return TypeRef(module.symbol.name)
        }
        val record = userRecordsByName[expression.name]
        if (record != null) {
            localBindings[expression] = record
            references +=
                ReferenceInfo(
                    expression.name,
                    expression.range,
                    record.symbol,
                    record.symbol.name,
                )
            return TypeRef(record.symbol.name)
        }
        diagnostics +=
            FrontendDiagnostic(
                "Unknown symbol `${expression.name}`.",
                expression.range,
            )
        return TypeRef("Unit")
    }

    private fun analyzeMember(
        expression: MemberAccessExpression,
        scope: Scope,
    ): Pair<Binding, TypeRef> {
        val receiverName = expression.receiver as? NameExpression
        if (receiverName != null) {
            val module = importedModules[receiverName.name]
            if (module != null) {
                val member =
                    module.module.functions.firstOrNull { it.name == expression.memberName }
                        ?: run {
                            diagnostics +=
                                FrontendDiagnostic(
                                    "Module `${module.module.name}` has no member `${expression.memberName}`.",
                                    expression.range,
                                )
                            return module to
                                TypeRef("Unit")
                        }
                val symbol =
                    SymbolInfo(
                        name = expression.memberName,
                        kind = SymbolKind.BUILTIN_FUNCTION,
                        range = expression.range,
                        detail = "${module.module.name}.${member.name}(${member.parameterTypes.joinToString()}) : ${member.returnType}",
                        documentation = member.documentation,
                    )
                val binding =
                    FunctionBinding(
                        symbol,
                        null,
                        member.parameterTypes.map(::TypeRef),
                        TypeRef(member.returnType),
                        module.module.name,
                    )
                references +=
                    ReferenceInfo(
                        expression.memberName,
                        expression.range,
                        symbol,
                        member.returnType,
                    )
                return binding to
                    TypeRef(member.returnType)
            }
        }
        val receiverType = analyzeExpression(expression.receiver, scope)
        val recordBinding = userRecordsByName[receiverType.name]
        val builtinType = registry.builtinType(receiverType.name)
        val fieldType =
            recordBinding?.fields?.get(expression.memberName)
                ?: builtinType?.fields?.firstOrNull { it.name == expression.memberName }?.let {
                    TypeRef(
                        it.typeName,
                    )
                }
        if (fieldType == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Type `${receiverType.displayName}` has no field `${expression.memberName}`.",
                    expression.range,
                )
            return VariableBinding(
                SymbolInfo(
                    expression.memberName,
                    SymbolKind.FIELD,
                    expression.range,
                    "unknown field",
                ),
                TypeRef("Unit"),
            ) to
                TypeRef("Unit")
        }
        val symbol =
            SymbolInfo(
                name = expression.memberName,
                kind = SymbolKind.FIELD,
                range = expression.range,
                detail = "${receiverType.displayName}.${expression.memberName}: ${fieldType.displayName}",
            )
        references +=
            ReferenceInfo(
                expression.memberName,
                expression.range,
                symbol,
                fieldType.displayName,
            )
        return MemberBinding(
            symbol,
            receiverType,
            fieldType,
        ) to fieldType
    }

    private fun analyzeCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef {
        val binding =
            when (val callee = expression.callee) {
                is NameExpression -> {
                    val global = registry.global(callee.name, expression.arguments.size)
                    if (global != null) {
                        FunctionBinding(
                            symbol =
                                SymbolInfo(
                                    name = global.name,
                                    kind = SymbolKind.BUILTIN_FUNCTION,
                                    range = callee.range,
                                    detail = "${global.name}(${global.parameterTypes.joinToString()}) : ${global.returnType}",
                                    documentation = global.documentation,
                                ),
                            declaration = null,
                            parameterTypes = global.parameterTypes.map(::TypeRef),
                            returnType =
                                TypeRef(global.returnType),
                        )
                    } else {
                        userFunctionsByName[callee.name]
                    }
                }

                is MemberAccessExpression -> {
                    val receiverName = callee.receiver as? NameExpression
                    val module = receiverName?.let { importedModules[it.name] }
                    if (module != null) {
                        val builtin =
                            module.module.functions.firstOrNull {
                                it.name == callee.memberName && it.parameterTypes.size == expression.arguments.size
                            }
                        if (builtin != null) {
                            FunctionBinding(
                                symbol =
                                    SymbolInfo(
                                        name = builtin.name,
                                        kind = SymbolKind.BUILTIN_FUNCTION,
                                        range = callee.range,
                                        detail =
                                            "${module.module.name}.${builtin.name}" +
                                                "(${builtin.parameterTypes.joinToString()}) : ${builtin.returnType}",
                                        documentation = builtin.documentation,
                                    ),
                                declaration = null,
                                parameterTypes = builtin.parameterTypes.map(::TypeRef),
                                returnType =
                                    TypeRef(builtin.returnType),
                                builtinModuleName = module.module.name,
                            )
                        } else {
                            diagnostics +=
                                FrontendDiagnostic(
                                    "Expected ${
                                        module.module.functions.count {
                                            it.name == callee.memberName
                                        }
                                    } matching overloads but got ${expression.arguments.size} arguments.",
                                    expression.range,
                                )
                            null
                        }
                    } else {
                        analyzeMember(callee, scope).first as? FunctionBinding
                    }
                }

                else -> {
                    null
                }
            }
        if (binding == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Expression is not callable.",
                    expression.range,
                )
            return TypeRef("Unit")
        }
        if (binding.parameterTypes.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Expected ${binding.parameterTypes.size} arguments but got ${expression.arguments.size}.",
                    expression.range,
                )
        }
        expression.arguments.forEachIndexed { index, argument ->
            val actual = analyzeExpression(argument, scope)
            val expected = binding.parameterTypes.getOrNull(index) ?: return@forEachIndexed
            expectAssignable(actual, expected, argument.range, "Argument type mismatch.")
        }
        callBindings[expression] = binding
        references +=
            ReferenceInfo(
                binding.symbol.name,
                expression.callee.range,
                binding.symbol,
                binding.returnType.displayName,
            )
        return binding.returnType
    }

    private fun analyzeRecordConstruction(
        expression: RecordConstructionExpression,
        scope: Scope,
    ): TypeRef {
        val record = userRecordsByName[expression.typeName]
        if (record == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Unknown struct `${expression.typeName}`.",
                    expression.range,
                )
            return TypeRef("Unit")
        }
        expression.fields.forEach { field ->
            val expected = record.fields[field.name]
            if (expected == null) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Struct `${record.symbol.name}` has no field `${field.name}`.",
                        field.range,
                    )
            } else {
                val actual = analyzeExpression(field.expression, scope)
                expectAssignable(actual, expected, field.range, "Struct field type mismatch.")
            }
        }
        return TypeRef(record.symbol.name)
    }

    private fun analyzeUnary(
        expression: UnaryExpression,
        scope: Scope,
    ): TypeRef {
        val operandType = analyzeExpression(expression.operand, scope)
        return when (expression.operator) {
            UnaryOperator.NEGATE -> {
                if (operandType.name !in setOf("Int", "Long")) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Unary minus expects Int or Long.",
                            expression.range,
                        )
                }
                operandType
            }

            UnaryOperator.NOT -> {
                expectAssignable(
                    operandType,
                    TypeRef("Bool"),
                    expression.range,
                    "Logical not expects Bool.",
                )
                TypeRef("Bool")
            }
        }
    }

    private fun analyzeBinary(
        expression: BinaryExpression,
        scope: Scope,
    ): TypeRef {
        val left = analyzeExpression(expression.left, scope)
        val right = analyzeExpression(expression.right, scope)
        return when (expression.operator) {
            BinaryOperator.ADD -> {
                when {
                    left.name == "String" || right.name == "String" -> {
                        TypeRef("String")
                    }

                    left.name == right.name && left.name in setOf("Int", "Long") -> {
                        left
                    }

                    else -> {
                        diagnostics +=
                            FrontendDiagnostic(
                                "Operator + expects numbers or strings.",
                                expression.range,
                            )
                        TypeRef("Unit")
                    }
                }
            }

            BinaryOperator.SUBTRACT,
            BinaryOperator.MULTIPLY,
            BinaryOperator.DIVIDE,
            -> {
                if (left.name != right.name || left.name !in setOf("Int", "Long")) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Numeric operator expects matching Int or Long operands.",
                            expression.range,
                        )
                }
                left
            }

            BinaryOperator.AND,
            BinaryOperator.OR,
            -> {
                expectAssignable(
                    left,
                    TypeRef("Bool"),
                    expression.left.range,
                    "Logical operators expect Bool operands.",
                )
                expectAssignable(
                    right,
                    TypeRef("Bool"),
                    expression.right.range,
                    "Logical operators expect Bool operands.",
                )
                TypeRef("Bool")
            }

            BinaryOperator.EQUALS,
            BinaryOperator.NOT_EQUALS,
            BinaryOperator.LESS,
            BinaryOperator.LESS_EQUALS,
            BinaryOperator.GREATER,
            BinaryOperator.GREATER_EQUALS,
            -> {
                if (!isAssignable(left, right) && !isAssignable(right, left)) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Operands are not comparable.",
                            expression.range,
                        )
                }
                TypeRef("Bool")
            }
        }
    }

    private fun resolveType(
        syntax: TypeSyntax,
        range: SourceRange,
    ): TypeRef? {
        val type =
            typeNames[syntax.name] ?: run {
                diagnostics +=
                    FrontendDiagnostic(
                        "Unknown type `${syntax.displayName}`.",
                        range,
                    )
                return null
            }
        return type.copy(nullable = syntax.nullable)
    }

    private fun expectAssignable(
        actual: TypeRef,
        expected: TypeRef,
        range: SourceRange,
        message: String,
    ) {
        if (!isAssignable(actual, expected)) {
            diagnostics +=
                FrontendDiagnostic(
                    "$message Expected ${expected.displayName}, got ${actual.displayName}.",
                    range,
                )
        }
    }

    private fun isAssignable(
        actual: TypeRef,
        expected: TypeRef,
    ): Boolean =
        actual == expected ||
            (actual.name == "Int" && expected.name == "Long" && !actual.nullable && !expected.nullable) ||
            (actual.nullable && expected.nullable && actual.name == expected.name)

    private class Scope(
        private val parent: Scope?,
    ) {
        private val values = mutableMapOf<String, VariableBinding>()

        fun define(
            name: String,
            binding: VariableBinding,
        ) {
            values[name] = binding
        }

        fun resolve(name: String): VariableBinding? = values[name] ?: parent?.resolve(name)
    }
}

internal class BytecodeCompiler(
    private val registry: BuiltinRegistry,
    private val semantic: SemanticResult,
) {
    private val functionIndices =
        semantic.program.declarations
            .filterIsInstance<FunctionDeclaration>()
            .mapIndexed { index, declaration ->
                declaration to index
            }.toMap()

    fun compile(name: String): BytecodeModule {
        val functions =
            semantic.program.declarations
                .filterIsInstance<FunctionDeclaration>()
                .map(::compileFunction)
        val records =
            semantic.program.declarations.filterIsInstance<StructDeclaration>().map { declaration ->
                BytecodeRecord(
                    name = declaration.name,
                    fields =
                        declaration.fields.map { field ->
                            RecordFieldDefinition(
                                name = field.name,
                                typeName = field.type.displayName.removeSuffix("?"),
                            )
                        },
                )
            }
        val entryIndex =
            semantic.program.declarations
                .filterIsInstance<FunctionDeclaration>()
                .indexOfFirst { it.name == "main" }
        return BytecodeModule(
            name = name,
            functions = functions,
            records = records,
            entryFunctionIndex = entryIndex.coerceAtLeast(0),
            registry = registry,
        )
    }

    private fun compileFunction(declaration: FunctionDeclaration): BytecodeFunction {
        val parameters =
            declaration.parameters.map { parameter ->
                BytecodeLocal(parameter.name, parameter.type.displayName.removeSuffix("?"))
            }
        val compiler = FunctionCompiler(declaration, parameters)
        compiler.compileBlock(declaration.body)
        compiler.instructions += Instruction.PushUnit
        compiler.instructions += Instruction.Return
        return BytecodeFunction(
            name = declaration.name,
            parameters = parameters,
            locals = compiler.locals,
            returnType = semantic.functionBindings[declaration]?.returnType?.name ?: "Unit",
            instructions = compiler.instructions,
            sourceRange = declaration.range,
        )
    }

    private inner class FunctionCompiler(
        private val declaration: FunctionDeclaration,
        parameters: List<BytecodeLocal>,
    ) {
        val instructions = mutableListOf<Instruction>()
        val locals = parameters.toMutableList()
        private val scopes = ArrayDeque<MutableMap<String, Int>>()

        init {
            scopes += parameters.mapIndexed { index, local -> local.name to index }.toMap(mutableMapOf())
        }

        fun compileBlock(block: BlockStatement) {
            scopes.addLast(mutableMapOf())
            block.statements.forEach(::compileStatement)
            scopes.removeLast()
        }

        private fun compileStatement(statement: Statement) {
            when (statement) {
                is BlockStatement -> {
                    compileBlock(statement)
                }

                is ExpressionStatement -> {
                    compileExpression(statement.expression)
                    instructions += Instruction.Pop
                }

                is IfStatement -> {
                    compileExpression(statement.condition)
                    val jumpToElse = instructions.size
                    instructions += Instruction.JumpIfFalse(-1)
                    compileBlock(statement.thenBranch)
                    val jumpToEnd = instructions.size
                    instructions += Instruction.Jump(-1)
                    val elseStart = instructions.size
                    when (val elseBranch = statement.elseBranch) {
                        is BlockStatement -> {
                            compileBlock(elseBranch)
                        }

                        is Statement -> {
                            compileStatement(elseBranch)
                        }

                        null -> {}
                    }
                    val end = instructions.size
                    instructions[jumpToElse] = Instruction.JumpIfFalse(elseStart)
                    instructions[jumpToEnd] = Instruction.Jump(end)
                }

                is ReturnStatement -> {
                    statement.expression?.let(::compileExpression) ?: run { instructions += Instruction.PushUnit }
                    instructions += Instruction.Return
                }

                is VariableDeclarationStatement -> {
                    compileExpression(statement.initializer)
                    val slot = locals.size
                    val typeName =
                        semantic.expressionTypes[statement.initializer]?.name
                            ?: statement.type?.name
                            ?: "Unit"
                    locals += BytecodeLocal(statement.name, typeName)
                    scopes.last()[statement.name] = slot
                    instructions += Instruction.StoreLocal(slot)
                }

                is WhileStatement -> {
                    val loopStart = instructions.size
                    compileExpression(statement.condition)
                    val exitJump = instructions.size
                    instructions += Instruction.JumpIfFalse(-1)
                    compileBlock(statement.body)
                    instructions += Instruction.Jump(loopStart)
                    val end = instructions.size
                    instructions[exitJump] = Instruction.JumpIfFalse(end)
                }

                is WhenStatement -> {
                    compileWhen(statement)
                }

                is AssignmentStatement -> {
                    compileExpression(statement.expression)
                    val slot = resolveLocalSlot(statement.name)
                    instructions += Instruction.StoreLocal(slot)
                }
            }
        }

        private fun compileWhen(statement: WhenStatement) {
            val subjectSlot: Int? =
                if (statement.subject != null) {
                    compileExpression(statement.subject)
                    val slot = locals.size
                    val typeName = semantic.expressionTypes[statement.subject]?.name ?: "Unit"
                    locals += BytecodeLocal("\$when", typeName)
                    scopes.last()["\$when"] = slot
                    instructions += Instruction.StoreLocal(slot)
                    slot
                } else {
                    null
                }

            val jumpsToEnd = mutableListOf<Int>()

            for (branch in statement.branches) {
                if (subjectSlot != null) {
                    val jumpsToBody = mutableListOf<Int>()
                    for ((i, value) in branch.values.withIndex()) {
                        instructions += Instruction.LoadLocal(subjectSlot)
                        compileExpression(value)
                        instructions += Instruction.Binary(BinaryOperator.EQUALS)
                        if (i < branch.values.size - 1) {
                            val jumpToBody = instructions.size
                            instructions += Instruction.JumpIfTrue(-1)
                            jumpsToBody += jumpToBody
                        } else {
                            val jumpToNext = instructions.size
                            instructions += Instruction.JumpIfFalse(-1)
                            val bodyStart = instructions.size
                            for (j in jumpsToBody) {
                                instructions[j] = Instruction.JumpIfTrue(bodyStart)
                            }
                            compileBlock(branch.body)
                            jumpsToEnd += instructions.size
                            instructions += Instruction.Jump(-1)
                            instructions[jumpToNext] = Instruction.JumpIfFalse(instructions.size)
                        }
                    }
                } else {
                    compileExpression(branch.values.first())
                    val jumpToNext = instructions.size
                    instructions += Instruction.JumpIfFalse(-1)
                    compileBlock(branch.body)
                    jumpsToEnd += instructions.size
                    instructions += Instruction.Jump(-1)
                    instructions[jumpToNext] = Instruction.JumpIfFalse(instructions.size)
                }
            }

            statement.elseBranch?.let(::compileBlock)
            val end = instructions.size
            for (j in jumpsToEnd) {
                instructions[j] = Instruction.Jump(end)
            }
        }

        private fun compileExpression(expression: Expression) {
            when (expression) {
                is BinaryExpression -> {
                    compileExpression(expression.left)
                    compileExpression(expression.right)
                    instructions += Instruction.Binary(expression.operator)
                }

                is CallExpression -> {
                    expression.arguments.forEach(::compileExpression)
                    val binding = semantic.callBindings[expression]
                    when {
                        binding == null -> {
                            instructions += Instruction.PushUnit
                        }

                        binding.builtinModuleName != null || binding.declaration == null -> {
                            instructions +=
                                Instruction.CallBuiltin(
                                    moduleName = binding.builtinModuleName,
                                    functionName = binding.symbol.name,
                                    argumentCount = expression.arguments.size,
                                )
                        }

                        else -> {
                            val index = functionIndices[binding.declaration] ?: 0
                            instructions += Instruction.CallFunction(index, expression.arguments.size)
                        }
                    }
                }

                is GroupExpression -> {
                    compileExpression(expression.expression)
                }

                is LiteralExpression -> {
                    when (val value = expression.value) {
                        is BoolLiteralValue -> instructions += Instruction.PushBool(value.value)
                        is IntLiteralValue -> instructions += Instruction.PushInt(value.value)
                        is LongLiteralValue -> instructions += Instruction.PushLong(value.value)
                        is StringLiteralValue -> instructions += Instruction.PushString(value.value)
                        NullLiteralValue -> instructions += Instruction.PushNull
                    }
                }

                is MemberAccessExpression -> {
                    val binding = semantic.memberBindings[expression]
                    if (binding is MemberBinding) {
                        compileExpression(expression.receiver)
                        instructions += Instruction.GetField(expression.memberName)
                    }
                }

                is NameExpression -> {
                    when (val binding = semantic.localBindings[expression]) {
                        is VariableBinding -> {
                            instructions +=
                                Instruction.LoadLocal(resolveLocalSlot(binding.symbol.name))
                        }

                        else -> {
                            instructions += Instruction.PushUnit
                        }
                    }
                }

                is RecordConstructionExpression -> {
                    expression.fields.forEach { field ->
                        compileExpression(field.expression)
                    }
                    instructions += Instruction.ConstructRecord(expression.typeName, expression.fields.map { it.name })
                }

                is ScopeAccessExpression -> {
                    instructions += Instruction.PushUnit
                }

                is UnaryExpression -> {
                    compileExpression(expression.operand)
                    instructions += Instruction.Unary(expression.operator)
                }
            }
        }

        private fun resolveLocalSlot(name: String): Int =
            scopes.asReversed().firstNotNullOfOrNull { it[name] }
                ?: declaration.parameters.indexOfFirst { it.name == name }.takeIf { it >= 0 }
                ?: 0
    }
}

internal class Lexer(
    private val source: String,
) {
    val diagnostics = mutableListOf<FrontendDiagnostic>()
    private val tokens = mutableListOf<Token>()
    private var index = 0
    private var line = 0
    private var column = 0

    fun lex(): List<Token> {
        while (!isAtEnd()) {
            skipWhitespace()
            if (isAtEnd()) break
            val start = location()
            when (val ch = advance()) {
                '(' -> {
                    addToken(TokenKind.LPAREN, "(", start)
                }

                ')' -> {
                    addToken(TokenKind.RPAREN, ")", start)
                }

                '{' -> {
                    addToken(TokenKind.LBRACE, "{", start)
                }

                '}' -> {
                    addToken(TokenKind.RBRACE, "}", start)
                }

                ':' -> {
                    if (match(':')) {
                        addToken(TokenKind.COLON_COLON, "::", start)
                    } else {
                        addToken(TokenKind.COLON, ":", start)
                    }
                }

                ';' -> {
                    addToken(TokenKind.SEMICOLON, ";", start)
                }

                ',' -> {
                    addToken(TokenKind.COMMA, ",", start)
                }

                '.' -> {
                    addToken(TokenKind.DOT, ".", start)
                }

                '?' -> {
                    addToken(TokenKind.QUESTION, "?", start)
                }

                '+' -> {
                    if (match('=')) {
                        addToken(TokenKind.PLUS_EQUAL, "+=", start)
                    } else {
                        addToken(TokenKind.PLUS, "+", start)
                    }
                }

                '-' -> {
                    if (!isAtEnd() && peek() == '>') {
                        advance()
                        addToken(TokenKind.ARROW, "->", start)
                    } else if (match('=')) {
                        addToken(TokenKind.MINUS_EQUAL, "-=", start)
                    } else {
                        addToken(TokenKind.MINUS, "-", start)
                    }
                }

                '*' -> {
                    if (match('=')) {
                        addToken(TokenKind.STAR_EQUAL, "*=", start)
                    } else {
                        addToken(TokenKind.STAR, "*", start)
                    }
                }

                '/' -> {
                    if (match('/')) {
                        while (!isAtEnd() && peek() != '\n') advance()
                    } else if (match('*')) {
                        lexBlockComment(start)
                    } else if (match('=')) {
                        addToken(TokenKind.SLASH_EQUAL, "/=", start)
                    } else {
                        addToken(TokenKind.SLASH, "/", start)
                    }
                }

                '!' -> {
                    addToken(if (match('=')) TokenKind.BANG_EQUAL else TokenKind.BANG, if (previous() == '=') "!=" else "!", start)
                }

                '=' -> {
                    addToken(if (match('=')) TokenKind.EQUAL_EQUAL else TokenKind.EQUAL, if (previous() == '=') "==" else "=", start)
                }

                '<' -> {
                    addToken(if (match('=')) TokenKind.LTE else TokenKind.LT, if (previous() == '=') "<=" else "<", start)
                }

                '>' -> {
                    addToken(if (match('=')) TokenKind.GTE else TokenKind.GT, if (previous() == '=') ">=" else ">", start)
                }

                '&' -> {
                    if (match('&')) {
                        addToken(TokenKind.AMP_AMP, "&&", start)
                    } else {
                        diagnostics +=
                            FrontendDiagnostic(
                                "Unexpected `&`.",
                                range(start),
                            )
                    }
                }

                '|' -> {
                    if (match('|')) {
                        addToken(TokenKind.PIPE_PIPE, "||", start)
                    } else {
                        diagnostics +=
                            FrontendDiagnostic(
                                "Unexpected `|`.",
                                range(start),
                            )
                    }
                }

                '"' -> {
                    lexString(start)
                }

                else -> {
                    when {
                        ch.isDigit() -> {
                            lexNumber(start, ch)
                        }

                        ch.isIdentifierStart() -> {
                            lexIdentifier(start, ch)
                        }

                        else -> {
                            diagnostics +=
                                FrontendDiagnostic(
                                    "Unexpected character `$ch`.",
                                    range(start),
                                )
                        }
                    }
                }
            }
        }
        val eof = location()
        tokens += Token(TokenKind.EOF, "", SourceRange(eof, eof))
        return tokens.toList()
    }

    private fun lexString(start: SourceLocation) {
        val builder = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            val next = advance()
            if (next == '\\' && !isAtEnd()) {
                builder.append(
                    when (val escaped = advance()) {
                        'n' -> '\n'
                        't' -> '\t'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> escaped
                    },
                )
            } else {
                builder.append(next)
            }
        }
        if (isAtEnd()) {
            diagnostics += FrontendDiagnostic("Unterminated string literal.", range(start))
            return
        }
        advance()
        tokens += Token(TokenKind.STRING, builder.toString(), SourceRange(start, location()))
    }

    private fun lexBlockComment(start: SourceLocation) {
        while (!isAtEnd()) {
            if (peek() == '*' && index + 1 < source.length && source[index + 1] == '/') {
                advance()
                advance()
                return
            }
            advance()
        }
        diagnostics += FrontendDiagnostic("Unterminated block comment.", range(start))
    }

    private fun lexNumber(
        start: SourceLocation,
        first: Char,
    ) {
        val builder = StringBuilder().append(first)
        while (!isAtEnd() && peek().isDigit()) {
            builder.append(advance())
        }
        if (!isAtEnd() && peek() == 'L') {
            builder.append(advance())
        }
        tokens += Token(TokenKind.NUMBER, builder.toString(), SourceRange(start, location()))
    }

    private fun lexIdentifier(
        start: SourceLocation,
        first: Char,
    ) {
        val builder = StringBuilder().append(first)
        while (!isAtEnd() && peek().isIdentifierPart()) {
            builder.append(advance())
        }
        val text = builder.toString()
        val kind =
            when (text) {
                "fun" -> TokenKind.FUN
                "val" -> TokenKind.VAL
                "var" -> TokenKind.VAR
                "if" -> TokenKind.IF
                "else" -> TokenKind.ELSE
                "while" -> TokenKind.WHILE
                "when" -> TokenKind.WHEN
                "return" -> TokenKind.RETURN
                "import" -> TokenKind.IMPORT
                "struct" -> TokenKind.STRUCT
                "true" -> TokenKind.TRUE
                "false" -> TokenKind.FALSE
                "null" -> TokenKind.NULL
                else -> TokenKind.IDENTIFIER
            }
        tokens += Token(kind, text, SourceRange(start, location()))
    }

    private fun skipWhitespace() {
        while (!isAtEnd()) {
            when (peek()) {
                ' ', '\r', '\t', '\n' -> advance()
                else -> return
            }
        }
    }

    private fun addToken(
        kind: TokenKind,
        text: String,
        start: SourceLocation,
    ) {
        tokens += Token(kind, text, SourceRange(start, location()))
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[index] != expected) return false
        advance()
        return true
    }

    private fun advance(): Char {
        val ch = source[index++]
        if (ch == '\n') {
            line += 1
            column = 0
        } else {
            column += 1
        }
        return ch
    }

    private fun previous(): Char = source[index - 1]

    private fun peek(): Char = source[index]

    private fun location(): SourceLocation = SourceLocation(index, line, column)

    private fun range(start: SourceLocation): SourceRange = SourceRange(start, location())

    private fun isAtEnd(): Boolean = index >= source.length

    private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
}

internal class Parser(
    private val tokens: List<Token>,
    initialDiagnostics: List<FrontendDiagnostic>,
) {
    val diagnostics = initialDiagnostics.toMutableList()
    private var index = 0

    fun parseProgram(): Program {
        val imports = mutableListOf<ImportDeclaration>()
        val declarations = mutableListOf<TopLevelDeclaration>()
        while (!isAtEnd()) {
            when {
                match(TokenKind.IMPORT) -> {
                    val imp = parseImport()
                    if (imp != null) imports += imp else synchronize()
                }

                match(TokenKind.FUN) -> {
                    val decl = parseFunction()
                    if (decl != null) declarations += decl else synchronize()
                }

                match(TokenKind.STRUCT) -> {
                    val decl = parseStruct()
                    if (decl != null) declarations += decl else synchronize()
                }

                check(TokenKind.EOF) -> {
                    break
                }

                else -> {
                    diagnostics += FrontendDiagnostic("Expected a top-level declaration.", peek().range)
                    synchronize()
                }
            }
        }
        return Program(imports, declarations, declarations.lastOrNull()?.range ?: imports.lastOrNull()?.range)
    }

    private fun synchronize() {
        while (!isAtEnd()) {
            if (match(TokenKind.SEMICOLON)) return
            if (check(TokenKind.FUN) || check(TokenKind.IMPORT) || check(TokenKind.STRUCT)) return
            advance()
        }
    }

    private fun parseImport(): ImportDeclaration? {
        val module = consume(TokenKind.IDENTIFIER, "Expected module name after import.") ?: return null
        consumeOptional(TokenKind.SEMICOLON)
        return ImportDeclaration(module.text, module.range)
    }

    private fun parseFunction(): FunctionDeclaration? {
        val name = consume(TokenKind.IDENTIFIER, "Expected function name.") ?: return null
        consume(TokenKind.LPAREN, "Expected `(` after function name.") ?: return null
        val parameters = mutableListOf<ParameterDeclaration>()
        if (!check(TokenKind.RPAREN)) {
            do {
                val parameterName = consume(TokenKind.IDENTIFIER, "Expected parameter name.") ?: return null
                consume(TokenKind.COLON, "Expected `:` after parameter name.") ?: return null
                val type = parseType() ?: return null
                parameters += ParameterDeclaration(parameterName.text, type, SourceRange(parameterName.range.start, type.range.end))
            } while (match(TokenKind.COMMA))
        }
        consume(TokenKind.RPAREN, "Expected `)` after parameters.") ?: return null
        val returnType = if (match(TokenKind.COLON)) parseType() else null
        val body = parseBlock() ?: return null
        return FunctionDeclaration(name.text, parameters, returnType, body, SourceRange(name.range.start, body.range.end))
    }

    private fun parseStruct(): StructDeclaration? {
        val name = consume(TokenKind.IDENTIFIER, "Expected struct name.") ?: return null
        consume(TokenKind.LBRACE, "Expected `{` after struct name.") ?: return null
        val fields = mutableListOf<RecordFieldDeclaration>()
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            val fieldName = consume(TokenKind.IDENTIFIER, "Expected field name.") ?: return null
            consume(TokenKind.COLON, "Expected `:` after field name.") ?: return null
            val type = parseType() ?: return null
            fields += RecordFieldDeclaration(fieldName.text, type, SourceRange(fieldName.range.start, type.range.end))
            consumeOptional(TokenKind.COMMA)
            consumeOptional(TokenKind.SEMICOLON)
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after struct body.") ?: return null
        return StructDeclaration(name.text, fields, SourceRange(name.range.start, end.range.end))
    }

    private fun parseBlock(): BlockStatement? {
        val start = consume(TokenKind.LBRACE, "Expected `{`.") ?: return null
        val statements = mutableListOf<Statement>()
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            statements += parseStatement() ?: return null
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after block.") ?: return null
        return BlockStatement(statements, SourceRange(start.range.start, end.range.end))
    }

    private fun parseStatement(): Statement? =
        when {
            match(TokenKind.VAL) -> {
                parseVariable(mutable = false)
            }

            match(TokenKind.VAR) -> {
                parseVariable(mutable = true)
            }

            match(TokenKind.IF) -> {
                parseIf()
            }

            match(TokenKind.WHILE) -> {
                parseWhile()
            }

            match(TokenKind.WHEN) -> {
                parseWhen()
            }

            match(TokenKind.RETURN) -> {
                parseReturn()
            }

            check(TokenKind.LBRACE) -> {
                parseBlock()
            }

            else -> {
                if (check(TokenKind.IDENTIFIER) && peekAhead(1)?.kind in COMPOUND_ASSIGN_KINDS) {
                    parseAssignment()
                } else {
                    val expression = parseExpression() ?: return null
                    val range = expression.range
                    consumeOptional(TokenKind.SEMICOLON)
                    ExpressionStatement(expression, range)
                }
            }
        }

    private fun parseAssignment(): AssignmentStatement? {
        val nameTok = consume(TokenKind.IDENTIFIER, "Expected variable name.") ?: return null
        val opTok = advance()
        val rhs = parseExpression() ?: return null
        consumeOptional(TokenKind.SEMICOLON)
        val value: Expression =
            when (opTok.kind) {
                TokenKind.EQUAL -> rhs
                TokenKind.PLUS_EQUAL -> compoundDesugar(nameTok, BinaryOperator.ADD, rhs)
                TokenKind.MINUS_EQUAL -> compoundDesugar(nameTok, BinaryOperator.SUBTRACT, rhs)
                TokenKind.STAR_EQUAL -> compoundDesugar(nameTok, BinaryOperator.MULTIPLY, rhs)
                TokenKind.SLASH_EQUAL -> compoundDesugar(nameTok, BinaryOperator.DIVIDE, rhs)
                else -> {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Expected `=`, `+=`, `-=`, `*=` or `/=` in assignment.",
                            opTok.range,
                        )
                    return null
                }
            }
        return AssignmentStatement(
            name = nameTok.text,
            nameRange = nameTok.range,
            expression = value,
            range = SourceRange(nameTok.range.start, value.range.end),
        )
    }

    private fun compoundDesugar(
        nameTok: Token,
        operator: BinaryOperator,
        rhs: Expression,
    ): Expression =
        BinaryExpression(
            left = NameExpression(nameTok.text, nameTok.range),
            operator = operator,
            right = rhs,
            range = SourceRange(nameTok.range.start, rhs.range.end),
        )

    private fun parseVariable(mutable: Boolean): VariableDeclarationStatement? {
        val name = consume(TokenKind.IDENTIFIER, "Expected variable name.") ?: return null
        val type = if (match(TokenKind.COLON)) parseType() else null
        consume(TokenKind.EQUAL, "Expected `=` in variable declaration.") ?: return null
        val initializer = parseExpression() ?: return null
        consumeOptional(TokenKind.SEMICOLON)
        return VariableDeclarationStatement(
            mutable = mutable,
            name = name.text,
            type = type,
            initializer = initializer,
            range = SourceRange(name.range.start, initializer.range.end),
        )
    }

    private fun parseIf(): IfStatement? {
        consume(TokenKind.LPAREN, "Expected `(` after `if`.") ?: return null
        val condition = parseExpression() ?: return null
        consume(TokenKind.RPAREN, "Expected `)` after if condition.") ?: return null
        val thenBranch = parseBlock() ?: return null
        val elseBranch: Statement? =
            if (match(TokenKind.ELSE)) {
                if (match(TokenKind.IF)) {
                    parseIf()
                } else {
                    parseBlock()
                }
            } else {
                null
            }
        return IfStatement(condition, thenBranch, elseBranch, SourceRange(condition.range.start, (elseBranch ?: thenBranch).range.end))
    }

    private fun parseWhile(): WhileStatement? {
        val condition = parseExpression() ?: return null
        val body = parseBlock() ?: return null
        return WhileStatement(condition, body, SourceRange(condition.range.start, body.range.end))
    }

    private fun parseWhen(): WhenStatement? {
        val start = previous().range.start
        val subject: Expression? =
            if (match(TokenKind.LPAREN)) {
                val expr = parseExpression() ?: return null
                consume(TokenKind.RPAREN, "Expected `)` after when subject.") ?: return null
                expr
            } else {
                null
            }
        consume(TokenKind.LBRACE, "Expected `{` after when.") ?: return null
        val branches = mutableListOf<WhenBranch>()
        var elseBranch: BlockStatement? = null
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            if (match(TokenKind.ELSE)) {
                consume(TokenKind.ARROW, "Expected `->` after else.") ?: return null
                elseBranch = parseBlock() ?: return null
                break
            }
            val branchStart = peek().range.start
            val values = mutableListOf<Expression>()
            values += parseExpression() ?: return null
            while (match(TokenKind.COMMA)) {
                values += parseExpression() ?: return null
            }
            consume(TokenKind.ARROW, "Expected `->` after when value.") ?: return null
            val body = parseBlock() ?: return null
            branches += WhenBranch(values, body, SourceRange(branchStart, body.range.end))
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after when body.") ?: return null
        return WhenStatement(subject, branches, elseBranch, SourceRange(start, end.range.end))
    }

    private fun parseReturn(): ReturnStatement? {
        if (check(TokenKind.SEMICOLON) || check(TokenKind.RBRACE)) {
            consumeOptional(TokenKind.SEMICOLON)
            val anchor = previous()
            return ReturnStatement(null, anchor.range)
        }
        val expression = parseExpression() ?: return null
        consumeOptional(TokenKind.SEMICOLON)
        return ReturnStatement(expression, expression.range)
    }

    private fun parseType(): TypeSyntax? {
        val name = consume(TokenKind.IDENTIFIER, "Expected type name.") ?: return null
        val nullable = match(TokenKind.QUESTION)
        return TypeSyntax(name.text, nullable, SourceRange(name.range.start, previous().range.end))
    }

    private fun parseExpression(): Expression? = parseOr()

    private fun parseOr(): Expression? {
        var expression = parseAnd() ?: return null
        while (match(TokenKind.PIPE_PIPE)) {
            val operator = previous()
            val right = parseAnd() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.OR, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseAnd(): Expression? {
        var expression = parseEquality() ?: return null
        while (match(TokenKind.AMP_AMP)) {
            val right = parseEquality() ?: return null
            expression = BinaryExpression(expression, BinaryOperator.AND, right, SourceRange(expression.range.start, right.range.end))
        }
        return expression
    }

    private fun parseEquality(): Expression? {
        var expression = parseComparison() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.EQUAL_EQUAL) -> {
                        val right = parseComparison() ?: return null
                        BinaryExpression(expression, BinaryOperator.EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.BANG_EQUAL) -> {
                        val right = parseComparison() ?: return null
                        BinaryExpression(expression, BinaryOperator.NOT_EQUALS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parseComparison(): Expression? {
        var expression = parseTerm() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.LT) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(expression, BinaryOperator.LESS, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.LTE) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.LESS_EQUALS,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    match(TokenKind.GT) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(expression, BinaryOperator.GREATER, right, SourceRange(expression.range.start, right.range.end))
                    }

                    match(TokenKind.GTE) -> {
                        val right = parseTerm() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.GREATER_EQUALS,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parseTerm(): Expression? {
        var expression = parseFactor() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.PLUS) -> {
                        val right = parseFactor() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.ADD,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    match(TokenKind.MINUS) -> {
                        val right = parseFactor() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.SUBTRACT,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parseFactor(): Expression? {
        var expression = parseUnary() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.STAR) -> {
                        val right = parseUnary() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.MULTIPLY,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    match(TokenKind.SLASH) -> {
                        val right = parseUnary() ?: return null
                        BinaryExpression(
                            expression,
                            BinaryOperator.DIVIDE,
                            right,
                            SourceRange(expression.range.start, right.range.end),
                        )
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parseUnary(): Expression? =
        when {
            match(TokenKind.BANG) -> {
                val operand = parseUnary() ?: return null
                UnaryExpression(
                    UnaryOperator.NOT,
                    operand,
                    SourceRange(previous().range.start, operand.range.end),
                )
            }

            match(TokenKind.MINUS) -> {
                val operand = parseUnary() ?: return null
                UnaryExpression(
                    UnaryOperator.NEGATE,
                    operand,
                    SourceRange(previous().range.start, operand.range.end),
                )
            }

            else -> {
                parseCall()
            }
        }

    private fun parseCall(): Expression? {
        var expression = parsePrimary() ?: return null
        while (true) {
            expression =
                when {
                    match(TokenKind.LPAREN) -> {
                        val arguments = mutableListOf<Expression>()
                        if (!check(TokenKind.RPAREN)) {
                            do {
                                arguments += parseExpression() ?: return null
                            } while (match(TokenKind.COMMA))
                        }
                        val end = consume(TokenKind.RPAREN, "Expected `)` after arguments.") ?: return null
                        CallExpression(
                            expression,
                            arguments,
                            SourceRange(expression.range.start, end.range.end),
                        )
                    }

                    match(TokenKind.DOT) -> {
                        val member = consume(TokenKind.IDENTIFIER, "Expected member name after `.`.") ?: return null
                        MemberAccessExpression(
                            expression,
                            member.text,
                            SourceRange(expression.range.start, member.range.end),
                        )
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parsePrimary(): Expression? {
        val token = advance()
        return when (token.kind) {
            TokenKind.TRUE -> {
                LiteralExpression(BoolLiteralValue(true), token.range)
            }

            TokenKind.FALSE -> {
                LiteralExpression(BoolLiteralValue(false), token.range)
            }

            TokenKind.NULL -> {
                LiteralExpression(NullLiteralValue, token.range)
            }

            TokenKind.STRING -> {
                LiteralExpression(StringLiteralValue(token.text), token.range)
            }

            TokenKind.NUMBER -> {
                if (token.text.endsWith("L")) {
                    val raw = token.text.dropLast(1)
                    val value = raw.toLongOrNull()
                    if (value == null) {
                        diagnostics +=
                            FrontendDiagnostic(
                                "Long literal `${token.text}` is out of range.",
                                token.range,
                            )
                        return null
                    }
                    LiteralExpression(LongLiteralValue(value), token.range)
                } else {
                    val value = token.text.toIntOrNull()
                    if (value == null) {
                        val asLong = token.text.toLongOrNull()
                        val hint =
                            if (asLong != null) {
                                "Integer literal `${token.text}` exceeds Int range; append `L` to make it a Long (e.g. `${token.text}L`)."
                            } else {
                                "Integer literal `${token.text}` is out of range."
                            }
                        diagnostics += FrontendDiagnostic(hint, token.range)
                        return null
                    }
                    LiteralExpression(IntLiteralValue(value), token.range)
                }
            }

            TokenKind.IDENTIFIER -> {
                if (check(TokenKind.LBRACE)) {
                    parseRecordConstruction(token)
                } else {
                    NameExpression(token.text, token.range)
                }
            }

            TokenKind.LPAREN -> {
                val expression = parseExpression() ?: return null
                val end = consume(TokenKind.RPAREN, "Expected `)` after expression.") ?: return null
                GroupExpression(expression, SourceRange(token.range.start, end.range.end))
            }

            else -> {
                diagnostics += FrontendDiagnostic("Expected an expression.", token.range)
                null
            }
        }
    }

    private fun parseRecordConstruction(nameToken: Token): Expression? {
        consume(TokenKind.LBRACE, "Expected `{` after struct name.") ?: return null
        val fields = mutableListOf<RecordFieldInitializer>()
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            val fieldName = consume(TokenKind.IDENTIFIER, "Expected struct field name.") ?: return null
            consume(TokenKind.COLON, "Expected `:` after struct field name.") ?: return null
            val expression = parseExpression() ?: return null
            fields += RecordFieldInitializer(fieldName.text, expression, SourceRange(fieldName.range.start, expression.range.end))
            if (!match(TokenKind.COMMA)) break
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after struct construction.") ?: return null
        return RecordConstructionExpression(nameToken.text, fields, SourceRange(nameToken.range.start, end.range.end))
    }

    private fun consume(
        kind: TokenKind,
        message: String,
    ): Token? {
        if (check(kind)) return advance()
        diagnostics += FrontendDiagnostic(message, peek().range)
        return null
    }

    private fun consumeOptional(kind: TokenKind) {
        if (check(kind)) advance()
    }

    private fun match(kind: TokenKind): Boolean =
        if (check(kind)) {
            advance()
            true
        } else {
            false
        }

    private fun check(kind: TokenKind): Boolean = !isAtEnd() && peek().kind == kind

    private fun peekAhead(offset: Int): Token? {
        val target = index + offset
        if (target < 0 || target >= tokens.size) return null
        return tokens[target]
    }

    private fun advance(): Token = tokens[index++]

    private fun previous(): Token = tokens[index - 1]

    private fun peek(): Token = tokens[index]

    private fun isAtEnd(): Boolean = index >= tokens.size || tokens[index].kind == TokenKind.EOF

    private companion object {
        val COMPOUND_ASSIGN_KINDS =
            setOf(
                TokenKind.EQUAL,
                TokenKind.PLUS_EQUAL,
                TokenKind.MINUS_EQUAL,
                TokenKind.STAR_EQUAL,
                TokenKind.SLASH_EQUAL,
            )
    }
}
