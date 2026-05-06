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
import ru.lazyhat.compukterkraft.lang.api.BytecodeClass
import ru.lazyhat.compukterkraft.lang.api.BytecodeClassField
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeLocal
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.BytecodeRecord
import ru.lazyhat.compukterkraft.lang.api.CallArgument
import ru.lazyhat.compukterkraft.lang.api.CallExpression
import ru.lazyhat.compukterkraft.lang.api.ClassConstructorParameter
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
import ru.lazyhat.compukterkraft.lang.api.ImportItem
import ru.lazyhat.compukterkraft.lang.api.ImportMode
import ru.lazyhat.compukterkraft.lang.api.ImportSource
import ru.lazyhat.compukterkraft.lang.api.IndexAccessExpression
import ru.lazyhat.compukterkraft.lang.api.IndexAssignmentStatement
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.IntLiteralValue
import ru.lazyhat.compukterkraft.lang.api.LegacyRecordConstructionExpression
import ru.lazyhat.compukterkraft.lang.api.ListLiteralExpression
import ru.lazyhat.compukterkraft.lang.api.LiteralExpression
import ru.lazyhat.compukterkraft.lang.api.LongLiteralValue
import ru.lazyhat.compukterkraft.lang.api.MapEntryExpression
import ru.lazyhat.compukterkraft.lang.api.MapLiteralExpression
import ru.lazyhat.compukterkraft.lang.api.MemberAccessExpression
import ru.lazyhat.compukterkraft.lang.api.MemberAssignmentStatement
import ru.lazyhat.compukterkraft.lang.api.NameExpression
import ru.lazyhat.compukterkraft.lang.api.NamedCallArgument
import ru.lazyhat.compukterkraft.lang.api.NullLiteralValue
import ru.lazyhat.compukterkraft.lang.api.ParameterDeclaration
import ru.lazyhat.compukterkraft.lang.api.PositionalCallArgument
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
import ru.lazyhat.compukterkraft.lang.api.ThisExpression
import ru.lazyhat.compukterkraft.lang.api.Token
import ru.lazyhat.compukterkraft.lang.api.TokenKind
import ru.lazyhat.compukterkraft.lang.api.TopLevelDeclaration
import ru.lazyhat.compukterkraft.lang.api.TypeApplicationExpression
import ru.lazyhat.compukterkraft.lang.api.TypeParameterDeclaration
import ru.lazyhat.compukterkraft.lang.api.TypeSyntax
import ru.lazyhat.compukterkraft.lang.api.UnaryExpression
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import ru.lazyhat.compukterkraft.lang.api.VariableDeclarationStatement
import ru.lazyhat.compukterkraft.lang.api.Visibility
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
    ): CompilationArtifact = compile(name, source, NoOpSourceLoader)

    fun compile(
        name: String,
        source: String,
        loader: SourceLoader,
    ): CompilationArtifact = compiler.compile(name, source, loader)
}

internal data class TypeRef(
    val name: String,
    val nullable: Boolean = false,
    val arguments: List<TypeRef> = emptyList(),
    val typeParameter: Boolean = false,
) {
    val displayName: String
        get() {
            val argumentList = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.displayName }
            return if (nullable) "$name$argumentList?" else "$name$argumentList"
        }
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

internal data class ClassFieldBinding(
    val name: String,
    val type: TypeRef,
    val mutable: Boolean,
    val visibility: Visibility,
    val symbol: SymbolInfo,
)

internal data class ClassMethodBinding(
    val name: String,
    val function: FunctionDeclaration,
    val parameterTypes: List<TypeRef>,
    val returnType: TypeRef,
    val static: Boolean,
    val visibility: Visibility,
    val symbol: SymbolInfo,
)

internal data class ClassBinding(
    override val symbol: SymbolInfo,
    val declaration: ClassDeclaration,
    val constructorParameters: List<ClassConstructorParameter>,
    val fields: Map<String, ClassFieldBinding>,
    val instanceMethods: Map<String, ClassMethodBinding>,
    val staticMethods: Map<String, ClassMethodBinding>,
) : Binding

internal data class ModuleBinding(
    override val symbol: SymbolInfo,
    val module: BuiltinModule,
) : Binding

internal data class ImportAliasBinding(
    override val symbol: SymbolInfo,
    val exports: ModuleExports,
) : Binding

internal data class MemberBinding(
    override val symbol: SymbolInfo,
    val ownerType: TypeRef,
    val type: TypeRef,
) : Binding

internal data class ModuleExports(
    val canonical: String,
    val functions: Map<String, FunctionDeclaration>,
    val structs: Map<String, StructDeclaration>,
    val classes: Map<String, ClassDeclaration>,
) {
    constructor(canonical: String, program: Program) : this(
        canonical = canonical,
        functions =
            program.declarations
                .filterIsInstance<FunctionDeclaration>()
                .filter {
                    it.visibility == Visibility.PUBLIC
                }.associateBy { it.name },
        structs =
            program.declarations
                .filterIsInstance<StructDeclaration>()
                .filter { it.visibility == Visibility.PUBLIC }
                .associateBy { it.name },
        classes =
            program.declarations
                .filterIsInstance<ClassDeclaration>()
                .filter { it.visibility == Visibility.PUBLIC }
                .associateBy { it.name },
    )
}

internal data class SemanticResult(
    val sourceName: String,
    val diagnostics: List<FrontendDiagnostic>,
    val symbols: List<SymbolInfo>,
    val references: List<ReferenceInfo>,
    val functionBindings: Map<FunctionDeclaration, FunctionBinding>,
    val recordBindings: Map<StructDeclaration, RecordBinding>,
    val classBindings: Map<ClassDeclaration, ClassBinding>,
    val localBindings: IdentityHashMap<Expression, Binding>,
    val expressionTypes: IdentityHashMap<Expression, TypeRef>,
    val callBindings: IdentityHashMap<CallExpression, FunctionBinding>,
    val recordConstructorBindings: IdentityHashMap<CallExpression, RecordBinding>,
    val classConstructorBindings: IdentityHashMap<CallExpression, ClassBinding>,
    val methodCallBindings: IdentityHashMap<CallExpression, ClassMethodBinding>,
    val memberBindings: IdentityHashMap<MemberAccessExpression, Binding>,
    val program: Program,
)

internal class SemanticAnalyzer(
    private val registry: BuiltinRegistry,
    private val sourceName: String,
    private val resolveImport: (String) -> String? = { null },
    private val lookupExports: (String) -> ModuleExports? = { null },
) {
    private val diagnostics = mutableListOf<FrontendDiagnostic>()
    private val symbols = mutableListOf<SymbolInfo>()
    private val references = mutableListOf<ReferenceInfo>()
    private val functionBindings = mutableMapOf<FunctionDeclaration, FunctionBinding>()
    private val recordBindings = mutableMapOf<StructDeclaration, RecordBinding>()
    private val classBindings = mutableMapOf<ClassDeclaration, ClassBinding>()
    private val localBindings = IdentityHashMap<Expression, Binding>()
    private val expressionTypes = IdentityHashMap<Expression, TypeRef>()
    private val callBindings = IdentityHashMap<CallExpression, FunctionBinding>()
    private val recordConstructorBindings = IdentityHashMap<CallExpression, RecordBinding>()
    private val classConstructorBindings = IdentityHashMap<CallExpression, ClassBinding>()
    private val methodCallBindings = IdentityHashMap<CallExpression, ClassMethodBinding>()
    private val memberBindings = IdentityHashMap<MemberAccessExpression, Binding>()
    private val builtinModules = registry.modules.associateBy { it.name }
    private val typeParameterScopes = ArrayDeque<Set<String>>()

    private val typeNames: MutableMap<String, TypeRef> =
        registry.builtinTypes
            .associate {
                it.name to
                    TypeRef(
                        it.name,
                    )
            }.toMutableMap()

    private val importedModules = mutableMapOf<String, ModuleBinding>()
    private val importAliases = mutableMapOf<String, ImportAliasBinding>()
    private val pendingImports = mutableListOf<ImportDeclaration>()
    private val userFunctionsByName = mutableMapOf<String, FunctionBinding>()
    private val userRecordsByName = mutableMapOf<String, RecordBinding>()
    private val userClassesByName = mutableMapOf<String, ClassBinding>()
    private var currentClass: ClassBinding? = null
    private var currentStaticMethod: Boolean = false
    private var inConstruction: Boolean = false

    private fun <T> withTypeParameters(
        parameters: List<TypeParameterDeclaration>,
        action: () -> T,
    ): T {
        typeParameterScopes.addLast(parameters.map { it.name }.toSet())
        try {
            return action()
        } finally {
            typeParameterScopes.removeLast()
        }
    }

    private fun <T> withResolvedClassTypeParameters(
        declaration: ClassDeclaration,
        action: () -> T,
    ): T = withTypeParameters(declaration.typeParameters, action)

    private fun isTypeParameter(name: String): Boolean = typeParameterScopes.asReversed().any { name in it }

    private fun substitute(
        type: TypeRef,
        substitutions: Map<String, TypeRef>,
    ): TypeRef {
        if (type.typeParameter) {
            val replacement = substitutions[type.name] ?: return type
            return if (type.nullable) replacement.copy(nullable = true) else replacement
        }
        return type.copy(arguments = type.arguments.map { substitute(it, substitutions) })
    }

    private fun substitutionsFor(
        parameters: List<TypeParameterDeclaration>,
        arguments: List<TypeRef>,
    ): Map<String, TypeRef> = parameters.zip(arguments).associate { (parameter, argument) -> parameter.name to argument }

    private fun inferSubstitutions(
        parameters: List<TypeRef>,
        arguments: List<TypeRef>,
        initial: Map<String, TypeRef> = emptyMap(),
    ): Map<String, TypeRef>? {
        val result = initial.toMutableMap()

        fun collect(
            expected: TypeRef,
            actual: TypeRef,
        ): Boolean {
            if (expected.typeParameter) {
                val replacement = if (expected.nullable) actual.copy(nullable = false) else actual
                val previous = result[expected.name]
                if (previous != null && previous != replacement) return false
                result[expected.name] = replacement
                return true
            }
            if (expected.name != actual.name || expected.arguments.size != actual.arguments.size) return false
            return expected.arguments.zip(actual.arguments).all { (expectedArgument, actualArgument) -> collect(expectedArgument, actualArgument) }
        }

        return if (parameters.zip(arguments).all { (parameter, argument) -> collect(parameter, argument) }) result else null
    }

    private fun collectionType(
        name: String,
        vararg arguments: TypeRef,
    ): TypeRef = TypeRef(name, arguments = arguments.toList())

    private fun TypeRef.elementType(): TypeRef? = if (name in setOf("Array", "List") && arguments.size == 1) arguments[0] else null

    private fun TypeRef.mapKeyType(): TypeRef? = if (name == "Map" && arguments.size == 2) arguments[0] else null

    private fun TypeRef.mapValueType(): TypeRef? = if (name == "Map" && arguments.size == 2) arguments[1] else null

    fun analyze(program: Program): SemanticResult {
        registerAmbientBuiltins()
        registerImports(program.imports)
        registerTopLevel(program.declarations)
        for (declaration in program.declarations) {
            when (declaration) {
                is ClassDeclaration -> analyzeClass(declaration)
                is FunctionDeclaration -> analyzeFunction(declaration)
                is StructDeclaration -> Unit
            }
        }
        return SemanticResult(
            sourceName = sourceName,
            diagnostics = diagnostics.toList(),
            symbols = symbols.toList(),
            references = references.toList(),
            functionBindings = functionBindings,
            recordBindings = recordBindings,
            classBindings = classBindings,
            localBindings = localBindings,
            expressionTypes = expressionTypes,
            callBindings = callBindings,
            recordConstructorBindings = recordConstructorBindings,
            classConstructorBindings = classConstructorBindings,
            methodCallBindings = methodCallBindings,
            memberBindings = memberBindings,
            program = program,
        )
    }

    private fun registerAmbientBuiltins() {
        builtinModules.values.forEach { module ->
            val symbol =
                SymbolInfo(
                    name = module.name,
                    kind = SymbolKind.MODULE,
                    range = SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0)),
                    detail = "module ${module.name}",
                    documentation = module.documentation,
                )
            symbols += symbol
            importedModules[module.name] = ModuleBinding(symbol, module)
        }
    }

    private fun registerImports(imports: List<ImportDeclaration>) {
        val seen = mutableSetOf<String>()
        imports.forEach { declaration ->
            when (val source = declaration.source) {
                is ImportSource.BuiltinNamespace -> registerBuiltinImport(declaration, source)
                is ImportSource.FilePath -> registerFileImport(declaration, source, seen)
            }
        }
    }

    private fun registerBuiltinImport(
        declaration: ImportDeclaration,
        source: ImportSource.BuiltinNamespace,
    ) {
        when (val mode = declaration.mode) {
            is ImportMode.Invalid -> diagnostics += FrontendDiagnostic(mode.message, mode.range)
            is ImportMode.Namespace -> diagnostics += FrontendDiagnostic("Use `import ${source.name} { name }`.", mode.aliasRange)
            is ImportMode.Selective -> registerBuiltinSelectiveImport(source, mode)
        }
    }

    private fun registerFileImport(
        declaration: ImportDeclaration,
        source: ImportSource.FilePath,
        seen: MutableSet<String>,
    ) {
        if (declaration.mode is ImportMode.Invalid) {
            diagnostics += FrontendDiagnostic(declaration.mode.message, declaration.mode.range)
            return
        }
        val canonical = resolveImport(source.path) ?: return
        if (!seen.add(canonical)) {
            diagnostics += FrontendDiagnostic("Duplicate import of `${source.path}`.", declaration.range)
            return
        }
        val exports = lookupExports(canonical) ?: return
        pendingImports += declaration
        when (val mode = declaration.mode) {
            is ImportMode.Invalid -> Unit
            is ImportMode.Namespace -> registerImportAlias(source, mode, exports)
            is ImportMode.Selective -> registerFileSelectiveImport(source, mode, exports)
        }
    }

    private fun registerBuiltinSelectiveImport(
        source: ImportSource.BuiltinNamespace,
        mode: ImportMode.Selective,
    ) {
        val module = registry.module(source.name)
        if (module == null) {
            diagnostics += FrontendDiagnostic("Unknown namespace `${source.name}`.", source.range)
            return
        }
        val seenItems = mutableSetOf<String>()
        mode.items.forEach { item ->
            if (!seenItems.add(item.name)) {
                diagnostics += FrontendDiagnostic("Duplicate import of `${item.name}`.", item.range)
                return@forEach
            }
            val function = module.functions.firstOrNull { it.name == item.name }
            if (function == null) {
                diagnostics += FrontendDiagnostic("Namespace `${source.name}` has no member `${item.name}`.", item.range)
                return@forEach
            }
            if (importedModules.containsKey(
                    item.name,
                ) || importAliases.containsKey(item.name) || userFunctionsByName.containsKey(item.name) ||
                userRecordsByName.containsKey(item.name) ||
                userClassesByName.containsKey(item.name)
            ) {
                diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
                return@forEach
            }
            val parameterTypes = function.parameterTypes.map { TypeRef(it) }
            val returnType = TypeRef(function.returnType)
            val symbol =
                SymbolInfo(
                    name = item.name,
                    kind = SymbolKind.BUILTIN_FUNCTION,
                    range = item.range,
                    detail = "${source.name}::${function.name}(${
                        parameterTypes.joinToString {
                            it.displayName
                        }
                    }): ${returnType.displayName}",
                    documentation = function.documentation,
                )
            symbols += symbol
            userFunctionsByName[item.name] =
                FunctionBinding(
                    symbol = symbol,
                    declaration = null,
                    parameterTypes = parameterTypes,
                    returnType = returnType,
                    builtinModuleName = source.name,
                )
        }
    }

    private fun registerImportAlias(
        source: ImportSource.FilePath,
        mode: ImportMode.Namespace,
        exports: ModuleExports,
    ) {
        val alias = mode.alias
        val range = mode.aliasRange
        if (importedModules.containsKey(alias) || importAliases.containsKey(alias) ||
            userFunctionsByName.containsKey(
                alias,
            ) ||
            userRecordsByName.containsKey(alias) ||
            userClassesByName.containsKey(alias)
        ) {
            diagnostics += FrontendDiagnostic("Redeclaration of `$alias`.", range)
            return
        }
        val symbol =
            SymbolInfo(
                name = alias,
                kind = SymbolKind.MODULE,
                range = range,
                detail = "import ${source.path} as $alias",
            )
        symbols += symbol
        importAliases[alias] = ImportAliasBinding(symbol, exports)
    }

    private fun registerFileSelectiveImport(
        source: ImportSource.FilePath,
        mode: ImportMode.Selective,
        exports: ModuleExports,
    ) {
        val seenItems = mutableSetOf<String>()
        mode.items.forEach { item ->
            if (!seenItems.add(item.name)) {
                diagnostics += FrontendDiagnostic("Duplicate import of `${item.name}`.", item.range)
                return@forEach
            }
            val struct = exports.structs[item.name]
            val function = exports.functions[item.name]
            val klass = exports.classes[item.name]
            if (struct == null && function == null && klass == null) {
                diagnostics += FrontendDiagnostic("File `${source.path}` has no public export `${item.name}`.", item.range)
                return@forEach
            }
            if (struct != null) registerSelectedRecord(item, struct, exports)
            if (function != null) registerSelectedFunction(item, function, exports)
            if (klass != null) registerSelectedClass(item, klass, exports)
        }
    }

    private fun registerSelectedRecord(
        item: ImportItem,
        struct: StructDeclaration,
        exports: ModuleExports,
    ) {
        if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) || typeNames.containsKey(item.name) ||
            userRecordsByName.containsKey(item.name) ||
            userClassesByName.containsKey(item.name)
        ) {
            diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
            return
        }
        val binding = recordBindingForExport(item.name, struct, exports, qualifier = null, item.range)
        typeNames[item.name] = TypeRef(item.name)
        userRecordsByName[item.name] = binding
        symbols += binding.symbol
    }

    private fun registerSelectedFunction(
        item: ImportItem,
        function: FunctionDeclaration,
        exports: ModuleExports,
    ) {
        if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) ||
            userFunctionsByName.containsKey(
                item.name,
            ) ||
            userRecordsByName.containsKey(item.name) ||
            userClassesByName.containsKey(item.name)
        ) {
            diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
            return
        }
        val binding = functionBindingForExport(item.name, function, exports, qualifier = null, item.range)
        userFunctionsByName[item.name] = binding
        symbols += binding.symbol
    }

    private fun registerSelectedClass(
        item: ImportItem,
        klass: ClassDeclaration,
        exports: ModuleExports,
    ) {
        if (importedModules.containsKey(item.name) || importAliases.containsKey(item.name) || typeNames.containsKey(item.name) ||
            userRecordsByName.containsKey(item.name) ||
            userClassesByName.containsKey(item.name)
        ) {
            diagnostics += FrontendDiagnostic("Redeclaration of `${item.name}`.", item.range)
            return
        }
        val binding = classBindingForExport(item.name, klass, exports, qualifier = null, item.range)
        typeNames[item.name] = TypeRef(item.name)
        userClassesByName[item.name] = binding
        classBindings[klass] = binding
        symbols += binding.symbol
    }

    private fun functionBindingForExport(
        visibleName: String,
        function: FunctionDeclaration,
        exports: ModuleExports,
        qualifier: String?,
        range: SourceRange,
    ): FunctionBinding {
        val parameterTypes = function.parameters.map { exportTypeRef(it.type, exports, qualifier) }
        val returnType = function.returnType?.let { exportTypeRef(it, exports, qualifier) } ?: TypeRef("Unit")
        val symbol =
            SymbolInfo(
                name = visibleName,
                kind = SymbolKind.FUNCTION,
                range = range,
                detail = "fun $visibleName(${parameterTypes.joinToString { it.displayName }}) : ${returnType.displayName}",
            )
        return FunctionBinding(
            symbol = symbol,
            declaration = function,
            parameterTypes = parameterTypes,
            returnType = returnType,
        )
    }

    private fun recordBindingForExport(
        visibleName: String,
        struct: StructDeclaration,
        exports: ModuleExports,
        qualifier: String?,
        range: SourceRange,
    ): RecordBinding {
        val fields = struct.fields.associate { it.name to exportTypeRef(it.type, exports, qualifier) }
        val symbol =
            SymbolInfo(
                name = visibleName,
                kind = SymbolKind.RECORD,
                range = range,
                detail = "struct $visibleName",
            )
        return RecordBinding(symbol, struct, fields)
    }

    private fun classBindingForExport(
        visibleName: String,
        klass: ClassDeclaration,
        exports: ModuleExports,
        qualifier: String?,
        range: SourceRange,
    ): ClassBinding {
        val fields = linkedMapOf<String, ClassFieldBinding>()
        klass.constructorParameters.forEach { parameter ->
            val mutability = parameter.fieldMutability ?: return@forEach
            val type = exportTypeRef(parameter.type, exports, qualifier)
            val symbol =
                SymbolInfo(
                    name = parameter.name,
                    kind = SymbolKind.FIELD,
                    range = parameter.range,
                    detail = "$visibleName.${parameter.name}: ${type.displayName}",
                )
            fields[parameter.name] =
                ClassFieldBinding(parameter.name, type, mutability == FieldMutability.VAR, parameter.visibility, symbol)
        }
        klass.members.filterIsInstance<ClassFieldDeclaration>().forEach { field ->
            val type = field.type?.let { exportTypeRef(it, exports, qualifier) } ?: TypeRef("Unit")
            val symbol =
                SymbolInfo(
                    name = field.name,
                    kind = SymbolKind.FIELD,
                    range = field.range,
                    detail = "$visibleName.${field.name}: ${type.displayName}",
                )
            fields[field.name] = ClassFieldBinding(field.name, type, field.mutable, field.visibility, symbol)
        }

        fun methodBinding(member: ClassMethodDeclaration): ClassMethodBinding {
            val function = member.function
            val parameterTypes = function.parameters.map { exportTypeRef(it.type, exports, qualifier) }
            val returnType = function.returnType?.let { exportTypeRef(it, exports, qualifier) } ?: TypeRef("Unit")
            val symbol =
                SymbolInfo(
                    name = function.name,
                    kind = SymbolKind.METHOD,
                    range = function.range,
                    detail = "fun $visibleName.${function.name}(${
                        parameterTypes.joinToString {
                            it.displayName
                        }
                    }) : ${returnType.displayName}",
                )
            return ClassMethodBinding(function.name, function, parameterTypes, returnType, member.static, member.visibility, symbol)
        }

        val instanceMethods = linkedMapOf<String, ClassMethodBinding>()
        val staticMethods = linkedMapOf<String, ClassMethodBinding>()
        klass.members.filterIsInstance<ClassMethodDeclaration>().forEach { member ->
            val binding = methodBinding(member)
            if (member.static) staticMethods[binding.name] = binding else instanceMethods[binding.name] = binding
        }
        val symbol =
            SymbolInfo(
                name = visibleName,
                kind = SymbolKind.CLASS,
                range = range,
                detail = "class $visibleName",
            )
        return ClassBinding(symbol, klass, klass.constructorParameters, fields, instanceMethods, staticMethods)
    }

    private fun exportTypeRef(
        syntax: TypeSyntax,
        exports: ModuleExports,
        qualifier: String?,
    ): TypeRef {
        val exportedUserType =
            syntax.qualifier == null &&
                qualifier != null &&
                (exports.structs.containsKey(syntax.name) || exports.classes.containsKey(syntax.name))
        val typeName =
            if (exportedUserType) {
                "$qualifier::${syntax.name}"
            } else {
                syntax.name
            }
        return TypeRef(
            typeName,
            syntax.nullable,
            syntax.arguments.map { exportTypeRef(it, exports, qualifier) },
            typeParameter = syntax.qualifier == null && !exportedUserType && !typeNames.containsKey(syntax.name),
        )
    }

    private fun registerTopLevel(declarations: List<TopLevelDeclaration>) {
        declarations.filterIsInstance<StructDeclaration>().forEach { declaration ->
            if (typeNames.containsKey(declaration.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Redeclaration of type `${declaration.name}`.",
                        declaration.range,
                    )
                return@forEach
            }
            val fields =
                buildMap {
                    withTypeParameters(declaration.typeParameters) {
                        declaration.fields.forEach { field ->
                            put(
                                field.name,
                                resolveType(field.type, field.range) ?: TypeRef(
                                    "Unit",
                                ),
                            )
                        }
                    }
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
        declarations.filterIsInstance<ClassDeclaration>().forEach { declaration ->
            if (typeNames.containsKey(declaration.name) || userFunctionsByName.containsKey(declaration.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Redeclaration of type `${declaration.name}`.",
                        declaration.range,
                    )
                return@forEach
            }
            val symbol =
                SymbolInfo(
                    name = declaration.name,
                    kind = SymbolKind.CLASS,
                    range = declaration.range,
                    detail = "class ${declaration.name}",
                )
            typeNames[declaration.name] = TypeRef(declaration.name)
            symbols += symbol

            val fields = linkedMapOf<String, ClassFieldBinding>()
            declaration.constructorParameters.forEach { parameter ->
                val parameterType = withResolvedClassTypeParameters(declaration) { resolveType(parameter.type, parameter.range) ?: TypeRef("Unit") }
                val mutability = parameter.fieldMutability
                if (mutability != null) {
                    if (fields.containsKey(parameter.name)) {
                        diagnostics += FrontendDiagnostic("Redeclaration of field `${parameter.name}`.", parameter.range)
                    } else {
                        val fieldSymbol =
                            SymbolInfo(
                                name = parameter.name,
                                kind = SymbolKind.FIELD,
                                range = parameter.range,
                                detail = "${declaration.name}.${parameter.name}: ${parameterType.displayName}",
                            )
                        symbols += fieldSymbol
                        fields[parameter.name] =
                            ClassFieldBinding(
                                name = parameter.name,
                                type = parameterType,
                                mutable = mutability == FieldMutability.VAR,
                                visibility = parameter.visibility,
                                symbol = fieldSymbol,
                            )
                    }
                }
            }
            declaration.members.filterIsInstance<ClassFieldDeclaration>().forEach { field ->
                if (fields.containsKey(field.name)) {
                    diagnostics += FrontendDiagnostic("Redeclaration of field `${field.name}`.", field.range)
                    return@forEach
                }
                if (field.type == null && field.initializer == null) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Field `${field.name}` requires a type or initializer.",
                            field.range,
                        )
                }
                val fieldType = withResolvedClassTypeParameters(declaration) { field.type?.let { resolveType(it, it.range) } ?: TypeRef("Unit") }
                val fieldSymbol =
                    SymbolInfo(
                        name = field.name,
                        kind = SymbolKind.FIELD,
                        range = field.range,
                        detail = "${declaration.name}.${field.name}: ${fieldType.displayName}",
                    )
                symbols += fieldSymbol
                fields[field.name] =
                    ClassFieldBinding(
                        name = field.name,
                        type = fieldType,
                        mutable = field.mutable,
                        visibility = field.visibility,
                        symbol = fieldSymbol,
                    )
            }

            fun methodBinding(member: ClassMethodDeclaration): ClassMethodBinding {
                val function = member.function
                val parameterTypes =
                    withResolvedClassTypeParameters(declaration) {
                        withTypeParameters(function.typeParameters) {
                            function.parameters.map { resolveType(it.type, it.range) ?: TypeRef("Unit") }
                        }
                    }
                val returnType =
                    withResolvedClassTypeParameters(declaration) {
                        withTypeParameters(function.typeParameters) {
                            function.returnType?.let { resolveType(it, it.range) } ?: TypeRef("Unit")
                        }
                    }
                val methodSymbol =
                    SymbolInfo(
                        name = function.name,
                        kind = SymbolKind.METHOD,
                        range = function.range,
                        detail = "fun ${declaration.name}.${function.name}(${
                            parameterTypes.joinToString {
                                it.displayName
                            }
                        }) : ${returnType.displayName}",
                    )
                symbols += methodSymbol
                return ClassMethodBinding(
                    function.name,
                    function,
                    parameterTypes,
                    returnType,
                    member.static,
                    member.visibility,
                    methodSymbol,
                )
            }

            val methods = declaration.members.filterIsInstance<ClassMethodDeclaration>()
            val instanceMethods = linkedMapOf<String, ClassMethodBinding>()
            val staticMethods = linkedMapOf<String, ClassMethodBinding>()
            methods.forEach { member ->
                val target = if (member.static) staticMethods else instanceMethods
                if (target.containsKey(member.function.name)) {
                    diagnostics += FrontendDiagnostic("Redeclaration of method `${member.function.name}`.", member.range)
                } else {
                    target[member.function.name] = methodBinding(member)
                }
            }

            val binding = ClassBinding(symbol, declaration, declaration.constructorParameters, fields, instanceMethods, staticMethods)
            classBindings[declaration] = binding
            userClassesByName[declaration.name] = binding
        }
        declarations.filterIsInstance<FunctionDeclaration>().forEach { declaration ->
            if (userFunctionsByName.containsKey(declaration.name) || typeNames.containsKey(declaration.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Redeclaration of function `${declaration.name}`.",
                        declaration.range,
                    )
                return@forEach
            }
            val parameterTypes =
                withTypeParameters(declaration.typeParameters) {
                    declaration.parameters.map {
                        resolveType(it.type, it.range)
                            ?: TypeRef(
                                "Unit",
                            )
                    }
                }
            val returnType =
                withTypeParameters(declaration.typeParameters) {
                    declaration.returnType?.let { resolveType(it, it.range) }
                        ?: TypeRef(
                            "Unit",
                        )
                }
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

    private fun analyzeClass(declaration: ClassDeclaration) {
        val binding = classBindings[declaration] ?: return
        val constructorScope = Scope(null)
        withTypeParameters(declaration.typeParameters) {
            declaration.constructorParameters.forEach { parameter ->
                val type = resolveType(parameter.type, parameter.range) ?: TypeRef("Unit")
                val symbol =
                    SymbolInfo(
                        name = parameter.name,
                        kind = SymbolKind.PARAMETER,
                        range = parameter.range,
                        detail = "${parameter.name}: ${type.displayName}",
                    )
                symbols += symbol
                constructorScope.define(parameter.name, VariableBinding(symbol, type, mutable = false))
            }
        }

        declaration.members.forEach { member ->
            when (member) {
                is ClassFieldDeclaration -> {
                    withClassContext(binding, staticMethod = false, construction = true) {
                        val initializer = member.initializer
                        if (initializer != null) {
                            val expected = binding.fields[member.name]?.type
                            val actual = analyzeExpression(initializer, constructorScope)
                            if (expected != null) {
                                expectAssignable(
                                    actual,
                                    expected,
                                    initializer.range,
                                    "Field initializer type mismatch.",
                                )
                            }
                        }
                    }
                }

                is ClassInitBlock -> {
                    withClassContext(binding, staticMethod = false, construction = true) {
                        analyzeBlock(member.body, constructorScope, declaration.range, TypeRef("Unit"))
                    }
                }

                is ClassMethodDeclaration -> {
                    analyzeClassMethod(binding, member)
                }
            }
        }
        checkClassBodyFieldInitialization(declaration)
    }

    private fun checkClassBodyFieldInitialization(declaration: ClassDeclaration) {
        val bodyFields = declaration.members.filterIsInstance<ClassFieldDeclaration>()
        val requiredFields = bodyFields.filter { it.initializer == null }.associateBy { it.name }
        if (requiredFields.isEmpty()) return

        var initialized = bodyFields.filter { it.initializer != null }.mapTo(mutableSetOf()) { it.name }
        declaration.members.forEach { member ->
            when (member) {
                is ClassFieldDeclaration -> {
                    if (member.initializer != null) initialized += member.name
                }

                is ClassInitBlock -> {
                    initialized = definitelyInitializedAfter(member.body, initialized, requiredFields.keys).toMutableSet()
                }

                is ClassMethodDeclaration -> {
                    Unit
                }
            }
        }

        requiredFields.values.forEach { field ->
            if (field.name !in initialized) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Field `${field.name}` must be initialized by an initializer or on every construction path in an init block.",
                        field.range,
                    )
            }
        }
    }

    private fun definitelyInitializedAfter(
        block: BlockStatement,
        before: Set<String>,
        candidateFields: Set<String>,
    ): Set<String> =
        block.statements.fold(before) { initialized, statement ->
            definitelyInitializedAfter(
                statement,
                initialized,
                candidateFields,
            )
        }

    private fun definitelyInitializedAfter(
        statement: Statement,
        before: Set<String>,
        candidateFields: Set<String>,
    ): Set<String> =
        when (statement) {
            is BlockStatement -> {
                definitelyInitializedAfter(statement, before, candidateFields)
            }

            is MemberAssignmentStatement -> {
                if (statement.receiver is ThisExpression && statement.memberName in candidateFields) {
                    before.toMutableSet().also { it += statement.memberName }
                } else {
                    before
                }
            }

            is IfStatement -> {
                val elseBranch = statement.elseBranch
                if (elseBranch == null) {
                    before
                } else {
                    val thenInitialized = definitelyInitializedAfter(statement.thenBranch, before, candidateFields)
                    val elseInitialized = definitelyInitializedAfter(elseBranch, before, candidateFields)
                    intersectInitialized(thenInitialized, elseInitialized)
                }
            }

            is WhenStatement -> {
                val elseBranch = statement.elseBranch
                if (elseBranch == null) {
                    before
                } else {
                    val branchResults = statement.branches.map { definitelyInitializedAfter(it.body, before, candidateFields) }
                    val elseInitialized = definitelyInitializedAfter(elseBranch, before, candidateFields)
                    (branchResults + listOf(elseInitialized)).reduce(::intersectInitialized)
                }
            }

            is WhileStatement -> {
                before
            }

            is AssignmentStatement,
            is IndexAssignmentStatement,
            is ExpressionStatement,
            is ReturnStatement,
            is VariableDeclarationStatement,
            -> {
                before
            }
        }

    private fun intersectInitialized(
        left: Set<String>,
        right: Set<String>,
    ): Set<String> = left.filterTo(mutableSetOf()) { it in right }

    private fun analyzeClassMethod(
        owner: ClassBinding,
        member: ClassMethodDeclaration,
    ) {
        val methodBinding =
            if (member.static) {
                owner.staticMethods[member.function.name]
            } else {
                owner.instanceMethods[member.function.name]
            } ?: return
        val scope = Scope(null)
        member.function.parameters.forEachIndexed { index, parameter ->
            val type = methodBinding.parameterTypes[index]
            val symbol =
                SymbolInfo(
                    name = parameter.name,
                    kind = SymbolKind.PARAMETER,
                    range = parameter.range,
                    detail = "${parameter.name}: ${type.displayName}",
                    ownerFunctionRange = member.function.range,
                )
            symbols += symbol
            scope.define(parameter.name, VariableBinding(symbol, type, mutable = false))
        }
        withTypeParameters(owner.declaration.typeParameters) {
            withTypeParameters(member.function.typeParameters) {
                withClassContext(owner, staticMethod = member.static, construction = false) {
                    analyzeBlock(member.function.body, scope, member.function.range, methodBinding.returnType)
                }
            }
        }
    }

    private fun withClassContext(
        owner: ClassBinding,
        staticMethod: Boolean,
        construction: Boolean,
        action: () -> Unit,
    ) {
        val previousClass = currentClass
        val previousStatic = currentStaticMethod
        val previousConstruction = inConstruction
        currentClass = owner
        currentStaticMethod = staticMethod
        inConstruction = construction
        try {
            action()
        } finally {
            currentClass = previousClass
            currentStaticMethod = previousStatic
            inConstruction = previousConstruction
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
        withTypeParameters(declaration.typeParameters) {
            analyzeBlock(declaration.body, scope, declaration.range, binding.returnType)
        }
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
                    statement.expression?.let { analyzeExpression(it, scope, expectedReturnType) }
                        ?: TypeRef(
                            "Unit",
                        )
                expectAssignable(actual, expectedReturnType, statement.range, "Return type mismatch.")
            }

            is VariableDeclarationStatement -> {
                val declaredType =
                    statement.type?.let { resolveType(it, statement.range) }
                val valueType = analyzeExpression(statement.initializer, scope, declaredType)
                val variableType = declaredType ?: valueType
                expectAssignable(valueType, variableType, statement.range, "Initializer type mismatch.")
                val symbol =
                    SymbolInfo(
                        name = statement.name,
                        kind = SymbolKind.VARIABLE,
                        range = statement.range,
                        detail = "${if (statement.mutable) "var" else "val"} ${statement.name}: ${variableType.displayName}",
                        ownerFunctionRange = functionRange,
                    )
                symbols += symbol
                scope.define(
                    statement.name,
                    VariableBinding(symbol, variableType, mutable = statement.mutable),
                )
            }

            is AssignmentStatement -> {
                analyzeAssignment(statement, scope)
            }

            is MemberAssignmentStatement -> {
                analyzeMemberAssignment(statement, scope)
            }

            is IndexAssignmentStatement -> {
                analyzeIndexAssignment(statement, scope)
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
        expectedType: TypeRef? = null,
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

                is LegacyRecordConstructionExpression -> {
                    analyzeLegacyRecordConstruction(expression, scope)
                }

                is ListLiteralExpression -> {
                    analyzeListLiteral(expression, scope, expectedType)
                }

                is MapLiteralExpression -> {
                    analyzeMapLiteral(expression, scope, expectedType)
                }

                is IndexAccessExpression -> {
                    analyzeIndexAccess(expression, scope)
                }

                is TypeApplicationExpression -> {
                    resolveType(TypeSyntax(expression.name, range = expression.range, arguments = expression.arguments), expression.range)
                        ?: TypeRef("Unit")
                }

                is ScopeAccessExpression -> {
                    analyzeScope(expression).second
                }

                is ThisExpression -> {
                    analyzeThis(expression)
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

    private fun analyzeMemberAssignment(
        statement: MemberAssignmentStatement,
        scope: Scope,
    ) {
        val receiverType = analyzeExpression(statement.receiver, scope)
        val classBinding = userClassesByName[receiverType.name]
        if (classBinding == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Type `${receiverType.displayName}` has no mutable fields.",
                    statement.memberRange,
                )
            analyzeExpression(statement.expression, scope)
            return
        }
        val field = classBinding.fields[statement.memberName]
        if (field == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Class `${classBinding.symbol.name}` has no field `${statement.memberName}`.",
                    statement.memberRange,
                )
            analyzeExpression(statement.expression, scope)
            return
        }
        if (!canAccessClassMember(classBinding, field.visibility)) {
            diagnostics += privateMemberDiagnostic(classBinding, statement.memberName, statement.memberRange)
        }
        val constructionAssignment = inConstruction && statement.receiver is ThisExpression
        if (!field.mutable && !constructionAssignment) {
            diagnostics +=
                FrontendDiagnostic(
                    "Cannot assign to val field `${statement.memberName}`.",
                    statement.memberRange,
                )
        }
        val actual = analyzeExpression(statement.expression, scope)
        val substitutions = substitutionsFor(classBinding.declaration.typeParameters, receiverType.arguments)
        val fieldType = substitute(field.type, substitutions)
        expectAssignable(actual, fieldType, statement.expression.range, "Field assignment type mismatch.")
        references += ReferenceInfo(statement.memberName, statement.memberRange, field.symbol, fieldType.displayName)
    }

    private fun analyzeIndexAssignment(
        statement: IndexAssignmentStatement,
        scope: Scope,
    ) {
        val receiverType = analyzeExpression(statement.receiver, scope)
        val indexType = analyzeExpression(statement.index, scope)
        when {
            receiverType.elementType() != null -> {
                expectAssignable(indexType, TypeRef("Int"), statement.index.range, "Index type mismatch.")
                val valueType = analyzeExpression(statement.expression, scope, receiverType.elementType())
                expectAssignable(valueType, receiverType.elementType() ?: TypeRef("Unit"), statement.expression.range, "Index assignment type mismatch.")
            }

            receiverType.mapKeyType() != null && receiverType.mapValueType() != null -> {
                expectAssignable(indexType, receiverType.mapKeyType() ?: TypeRef("Unit"), statement.index.range, "Index type mismatch.")
                val valueType = analyzeExpression(statement.expression, scope, receiverType.mapValueType())
                expectAssignable(valueType, receiverType.mapValueType() ?: TypeRef("Unit"), statement.expression.range, "Index assignment type mismatch.")
            }

            else -> {
                diagnostics += FrontendDiagnostic("Type `${receiverType.displayName}` is not index-assignable.", statement.range)
                analyzeExpression(statement.expression, scope)
            }
        }
    }

    private fun analyzeThis(expression: ThisExpression): TypeRef {
        val owner = currentClass
        if (owner == null) {
            diagnostics += FrontendDiagnostic("`this` is only available inside classes.", expression.range)
            return TypeRef("Unit")
        }
        if (currentStaticMethod) {
            diagnostics += FrontendDiagnostic("Static method cannot access `this`.", expression.range)
            return TypeRef(owner.symbol.name)
        }
        return TypeRef(owner.symbol.name)
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
        if (receiverName != null && importedModules.containsKey(receiverName.name)) {
            diagnostics +=
                FrontendDiagnostic(
                    "Use `::` for module access (try `${receiverName.name}::${expression.memberName}`).",
                    expression.range,
                )
            return errorBinding(expression.range, "invalid module access") to TypeRef("Unit")
        }
        val receiverType = analyzeExpression(expression.receiver, scope)
        val recordBinding = userRecordsByName[receiverType.name]
        val classBinding = userClassesByName[receiverType.name]
        val builtinType = registry.builtinType(receiverType.name)
        val classField = classBinding?.fields?.get(expression.memberName)
        val substitutions =
            when {
                recordBinding != null -> substitutionsFor(recordBinding.declaration?.typeParameters.orEmpty(), receiverType.arguments)
                classBinding != null -> substitutionsFor(classBinding.declaration.typeParameters, receiverType.arguments)
                else -> emptyMap()
            }
        val fieldType =
            recordBinding?.fields?.get(expression.memberName)?.let { substitute(it, substitutions) }
                ?: classField?.type?.let { substitute(it, substitutions) }
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
        if (classBinding != null && classField != null && !canAccessClassMember(classBinding, classField.visibility)) {
            diagnostics += privateMemberDiagnostic(classBinding, expression.memberName, expression.range)
        }
        val symbol =
            classField?.symbol ?: SymbolInfo(
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
        analyzeCollectionConstructorCall(expression, scope)?.let { return it }
        analyzeClassConstructorCall(expression, scope)?.let { return it }
        analyzeRecordConstructorCall(expression, scope)?.let { return it }
        analyzeCollectionMethodCall(expression, scope)?.let { return it }
        analyzeClassMethodCall(expression, scope)?.let { return it }
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
                    analyzeMember(callee, scope).first as? FunctionBinding
                }

                is ScopeAccessExpression -> {
                    analyzeScopeCall(callee, expression.arguments.size)
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
        expression.arguments.filterIsInstance<NamedCallArgument>().forEach { argument ->
            diagnostics +=
                FrontendDiagnostic(
                    "Named arguments are only supported for constructors.",
                    argument.range,
                )
        }
        if (binding.parameterTypes.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Expected ${binding.parameterTypes.size} arguments but got ${expression.arguments.size}.",
                    expression.range,
                )
        }
        val argumentTypes = expression.arguments.map { analyzeExpression(it.expression, scope) }
        val substitutions =
            binding.declaration?.typeParameters?.takeIf { it.isNotEmpty() }?.let {
                inferSubstitutions(binding.parameterTypes, argumentTypes)
                    ?: run {
                        diagnostics += FrontendDiagnostic("Cannot infer generic type arguments for `${binding.symbol.name}`.", expression.range)
                        emptyMap()
                    }
            } ?: emptyMap()
        val parameterTypes = binding.parameterTypes.map { substitute(it, substitutions) }
        val returnType = substitute(binding.returnType, substitutions)
        expression.arguments.forEachIndexed { index, argument ->
            val actual = argumentTypes[index]
            val expected = parameterTypes.getOrNull(index) ?: return@forEachIndexed
            expectAssignable(actual, expected, argument.expression.range, "Argument type mismatch.")
        }
        callBindings[expression] = binding
        references +=
            ReferenceInfo(
                binding.symbol.name,
                expression.callee.range,
                binding.symbol,
                returnType.displayName,
            )
        return returnType
    }

    private fun analyzeCollectionConstructorCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef? {
        val callee = expression.callee as? TypeApplicationExpression ?: return null
        if (callee.name != "Array") return null
        val type = resolveType(TypeSyntax(callee.name, range = callee.range, arguments = callee.arguments), callee.range) ?: TypeRef("Unit")
        val elementType = type.elementType() ?: TypeRef("Unit")
        val namedArguments = expression.arguments.filterIsInstance<NamedCallArgument>()
        if (namedArguments.size != expression.arguments.size) {
            diagnostics += FrontendDiagnostic("Array constructor arguments must be named.", expression.range)
            expression.arguments.forEach { analyzeExpression(it.expression, scope) }
            return type
        }
        val argumentsByName = namedArguments.associateBy { it.name }
        val size = argumentsByName["size"]
        val default = argumentsByName["default"]
        if (size == null) {
            diagnostics += FrontendDiagnostic("Missing constructor argument `size` for Array.", expression.range)
        } else {
            val actual = analyzeExpression(size.expression, scope, TypeRef("Int"))
            expectAssignable(actual, TypeRef("Int"), size.expression.range, "Constructor argument type mismatch.")
        }
        if (default == null) {
            diagnostics += FrontendDiagnostic("Missing constructor argument `default` for Array.", expression.range)
        } else {
            val actual = analyzeExpression(default.expression, scope, elementType)
            expectAssignable(actual, elementType, default.expression.range, "Constructor argument type mismatch.")
        }
        namedArguments.filter { it.name !in setOf("size", "default") }.forEach { argument ->
            diagnostics += FrontendDiagnostic("Unknown constructor parameter `${argument.name}` for Array.", argument.nameRange)
            analyzeExpression(argument.expression, scope)
        }
        return type
    }

    private fun analyzeCollectionMethodCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef? {
        val callee = expression.callee as? MemberAccessExpression ?: return null
        val receiverName = callee.receiver as? NameExpression
        if (receiverName != null && userClassesByName.containsKey(receiverName.name)) return null
        val receiverType = analyzeExpression(callee.receiver, scope)
        val signature = collectionMethodSignature(receiverType, callee.memberName) ?: return null
        expression.arguments.filterIsInstance<NamedCallArgument>().forEach { argument ->
            diagnostics += FrontendDiagnostic("Named arguments are not supported for collection methods.", argument.range)
        }
        if (signature.parameterTypes.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Expected ${signature.parameterTypes.size} arguments but got ${expression.arguments.size}.",
                    expression.range,
                )
        }
        expression.arguments.forEachIndexed { index, argument ->
            val expected = signature.parameterTypes.getOrNull(index)
            val actual = analyzeExpression(argument.expression, scope, expected)
            if (expected != null) {
                expectAssignable(actual, expected, argument.expression.range, "Argument type mismatch.")
            }
        }
        references +=
            ReferenceInfo(
                callee.memberName,
                callee.range,
                SymbolInfo(
                    name = callee.memberName,
                    kind = SymbolKind.METHOD,
                    range = callee.range,
                    detail = "${receiverType.displayName}.${callee.memberName}(${signature.parameterTypes.joinToString { it.displayName }}) : ${signature.returnType.displayName}",
                ),
                signature.returnType.displayName,
            )
        return signature.returnType
    }

    private data class CollectionMethodSignature(
        val parameterTypes: List<TypeRef>,
        val returnType: TypeRef,
    )

    private fun collectionMethodSignature(
        receiverType: TypeRef,
        methodName: String,
    ): CollectionMethodSignature? {
        receiverType.elementType()?.let { elementType ->
            return when (receiverType.name) {
                "Array" ->
                    when (methodName) {
                        "size" -> CollectionMethodSignature(emptyList(), TypeRef("Int"))
                        "get" -> CollectionMethodSignature(listOf(TypeRef("Int")), elementType)
                        "set" -> CollectionMethodSignature(listOf(TypeRef("Int"), elementType), TypeRef("Unit"))
                        "getOrNull" -> CollectionMethodSignature(listOf(TypeRef("Int")), elementType.copy(nullable = true))
                        else -> null
                    }

                "List" ->
                    when (methodName) {
                        "size" -> CollectionMethodSignature(emptyList(), TypeRef("Int"))
                        "isEmpty" -> CollectionMethodSignature(emptyList(), TypeRef("Bool"))
                        "get" -> CollectionMethodSignature(listOf(TypeRef("Int")), elementType)
                        "set" -> CollectionMethodSignature(listOf(TypeRef("Int"), elementType), TypeRef("Unit"))
                        "getOrNull" -> CollectionMethodSignature(listOf(TypeRef("Int")), elementType.copy(nullable = true))
                        "add" -> CollectionMethodSignature(listOf(elementType), TypeRef("Unit"))
                        "insert" -> CollectionMethodSignature(listOf(TypeRef("Int"), elementType), TypeRef("Unit"))
                        "removeAt" -> CollectionMethodSignature(listOf(TypeRef("Int")), elementType)
                        "clear" -> CollectionMethodSignature(emptyList(), TypeRef("Unit"))
                        else -> null
                    }

                else -> null
            }
        }
        val keyType = receiverType.mapKeyType()
        val valueType = receiverType.mapValueType()
        if (keyType != null && valueType != null) {
            return when (methodName) {
                "size" -> CollectionMethodSignature(emptyList(), TypeRef("Int"))
                "isEmpty" -> CollectionMethodSignature(emptyList(), TypeRef("Bool"))
                "containsKey" -> CollectionMethodSignature(listOf(keyType), TypeRef("Bool"))
                "get" -> CollectionMethodSignature(listOf(keyType), valueType.copy(nullable = true))
                "getOrDefault" -> CollectionMethodSignature(listOf(keyType, valueType), valueType)
                "set" -> CollectionMethodSignature(listOf(keyType, valueType), TypeRef("Unit"))
                "remove" -> CollectionMethodSignature(listOf(keyType), valueType.copy(nullable = true))
                "clear" -> CollectionMethodSignature(emptyList(), TypeRef("Unit"))
                "keys" -> CollectionMethodSignature(emptyList(), collectionType("List", keyType))
                "values" -> CollectionMethodSignature(emptyList(), collectionType("List", valueType))
                else -> null
            }
        }
        return null
    }

    private fun analyzeClassMethodCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef? {
        val callee = expression.callee as? MemberAccessExpression ?: return null
        val staticClass = (callee.receiver as? NameExpression)?.let { userClassesByName[it.name] }
        if (staticClass != null) {
            val method = staticClass.staticMethods[callee.memberName] ?: return null
            if (!canAccessClassMember(staticClass, method.visibility)) {
                diagnostics += privateMemberDiagnostic(staticClass, callee.memberName, callee.range)
            }
            return analyzeClassMethodArguments(expression, staticClass, TypeRef(staticClass.symbol.name), method, callee, scope)
        }
        val receiverType = analyzeExpression(callee.receiver, scope)
        val classBinding = userClassesByName[receiverType.name] ?: return null
        val method = classBinding.instanceMethods[callee.memberName] ?: return null
        if (!canAccessClassMember(classBinding, method.visibility)) {
            diagnostics += privateMemberDiagnostic(classBinding, callee.memberName, callee.range)
        }
        return analyzeClassMethodArguments(expression, classBinding, receiverType, method, callee, scope)
    }

    private fun canAccessClassMember(
        owner: ClassBinding,
        visibility: Visibility,
    ): Boolean = visibility == Visibility.PUBLIC || currentClass?.declaration == owner.declaration

    private fun privateMemberDiagnostic(
        owner: ClassBinding,
        memberName: String,
        range: SourceRange,
    ): FrontendDiagnostic = FrontendDiagnostic("Member `$memberName` of class `${owner.symbol.name}` is private.", range)

    private fun analyzeClassMethodArguments(
        expression: CallExpression,
        owner: ClassBinding,
        receiverType: TypeRef,
        method: ClassMethodBinding,
        callee: MemberAccessExpression,
        scope: Scope,
    ): TypeRef {
        expression.arguments.filterIsInstance<NamedCallArgument>().forEach { argument ->
            diagnostics += FrontendDiagnostic("Named arguments are only supported for constructors.", argument.range)
        }
        if (method.parameterTypes.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Expected ${method.parameterTypes.size} arguments but got ${expression.arguments.size}.",
                    expression.range,
                )
        }
        val argumentTypes = expression.arguments.map { analyzeExpression(it.expression, scope) }
        val ownerSubstitutions = substitutionsFor(owner.declaration.typeParameters, receiverType.arguments)
        val ownerParameterTypes = method.parameterTypes.map { substitute(it, ownerSubstitutions) }
        val inferredSubstitutions =
            method.function.typeParameters.takeIf { it.isNotEmpty() }?.let {
                inferSubstitutions(ownerParameterTypes, argumentTypes, ownerSubstitutions)
                    ?: run {
                        diagnostics += FrontendDiagnostic("Cannot infer generic type arguments for `${method.name}`.", expression.range)
                        ownerSubstitutions
                    }
            } ?: ownerSubstitutions
        val parameterTypes = method.parameterTypes.map { substitute(it, inferredSubstitutions) }
        val returnType = substitute(method.returnType, inferredSubstitutions)
        expression.arguments.forEachIndexed { index, argument ->
            val actual = argumentTypes[index]
            val expected = parameterTypes.getOrNull(index) ?: return@forEachIndexed
            expectAssignable(actual, expected, argument.expression.range, "Argument type mismatch.")
        }
        methodCallBindings[expression] = method
        references += ReferenceInfo(callee.memberName, callee.range, method.symbol, returnType.displayName)
        return returnType
    }

    private fun analyzeClassConstructorCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef? {
        val callee = expression.callee as? NameExpression ?: return null
        val binding = userClassesByName[callee.name] ?: return null
        val namedArguments = expression.arguments.filterIsInstance<NamedCallArgument>()
        if (namedArguments.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Constructor arguments must be named.",
                    expression.range,
                )
            expression.arguments.forEach { analyzeExpression(it.expression, scope) }
            return TypeRef(binding.symbol.name)
        }
        val parameters = binding.constructorParameters.associateBy { it.name }
        val parameterTypes =
            parameters.mapValues { (_, parameter) ->
                withResolvedClassTypeParameters(binding.declaration) { resolveType(parameter.type, parameter.range) ?: TypeRef("Unit") }
            }
        val seen = mutableSetOf<String>()
        val argumentTypes = mutableMapOf<String, TypeRef>()
        namedArguments.forEach { argument ->
            if (!seen.add(argument.name)) {
                diagnostics += FrontendDiagnostic("Duplicate constructor argument `${argument.name}`.", argument.nameRange)
            }
            val parameter = parameters[argument.name]
            if (parameter == null) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Unknown constructor parameter `${argument.name}` for class `${binding.symbol.name}`.",
                        argument.nameRange,
                    )
                analyzeExpression(argument.expression, scope)
            } else {
                val actual = analyzeExpression(argument.expression, scope)
                argumentTypes[argument.name] = actual
            }
        }
        val substitutions =
            inferSubstitutions(
                parameters.keys.mapNotNull { parameterTypes[it] },
                parameters.keys.mapNotNull { argumentTypes[it] },
            ) ?: emptyMap()
        namedArguments.forEach { argument ->
            val expected = parameterTypes[argument.name]?.let { substitute(it, substitutions) }
            val actual = argumentTypes[argument.name]
            if (expected != null && actual != null) {
                expectAssignable(actual, expected, argument.expression.range, "Constructor argument type mismatch.")
            }
        }
        parameters.keys.filterNot(seen::contains).forEach { missing ->
            diagnostics +=
                FrontendDiagnostic(
                    "Missing constructor argument `$missing` for class `${binding.symbol.name}`.",
                    expression.range,
                )
        }
        classConstructorBindings[expression] = binding
        val constructedType = TypeRef(binding.symbol.name, arguments = binding.declaration.typeParameters.mapNotNull { substitutions[it.name] })
        references +=
            ReferenceInfo(
                binding.symbol.name,
                callee.range,
                binding.symbol,
                constructedType.displayName,
            )
        return constructedType
    }

    private fun analyzeRecordConstructorCall(
        expression: CallExpression,
        scope: Scope,
    ): TypeRef? {
        val namedArguments = expression.arguments.filterIsInstance<NamedCallArgument>()
        if (namedArguments.isEmpty()) return null
        if (namedArguments.size != expression.arguments.size) {
            diagnostics +=
                FrontendDiagnostic(
                    "Constructor arguments must be named.",
                    expression.range,
                )
            expression.arguments.forEach { analyzeExpression(it.expression, scope) }
            return TypeRef("Unit")
        }
        val binding =
            when (val callee = expression.callee) {
                is NameExpression -> {
                    userRecordsByName[callee.name]
                }

                is ScopeAccessExpression -> {
                    val alias = importAliases[callee.qualifier] ?: return null
                    val struct = alias.exports.structs[callee.name]
                    if (struct == null) return null
                    recordBindingForExport(
                        visibleName = "${callee.qualifier}::${callee.name}",
                        struct = struct,
                        exports = alias.exports,
                        qualifier = callee.qualifier,
                        range = callee.range,
                    ).also { userRecordsByName[it.symbol.name] = it }
                }

                else -> {
                    return null
                }
            } ?: return null

        val seen = mutableSetOf<String>()
        val argumentTypes = mutableMapOf<String, TypeRef>()
        namedArguments.forEach { argument ->
            if (!seen.add(argument.name)) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Duplicate constructor argument `${argument.name}`.",
                        argument.nameRange,
                    )
            }
            val expected = binding.fields[argument.name]
            if (expected == null) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Unknown constructor parameter `${argument.name}` for struct `${binding.symbol.name}`.",
                        argument.nameRange,
                    )
                analyzeExpression(argument.expression, scope)
            } else {
                val actual = analyzeExpression(argument.expression, scope)
                argumentTypes[argument.name] = actual
            }
        }
        val substitutions =
            inferSubstitutions(
                binding.fields.keys.mapNotNull { binding.fields[it] },
                binding.fields.keys.mapNotNull { argumentTypes[it] },
            ) ?: emptyMap()
        namedArguments.forEach { argument ->
            val expected = binding.fields[argument.name]?.let { substitute(it, substitutions) }
            val actual = argumentTypes[argument.name]
            if (expected != null && actual != null) {
                expectAssignable(actual, expected, argument.expression.range, "Struct field type mismatch.")
            }
        }
        binding.fields.keys.filterNot(seen::contains).forEach { missing ->
            diagnostics +=
                FrontendDiagnostic(
                    "Missing constructor argument `$missing` for struct `${binding.symbol.name}`.",
                    expression.range,
                )
        }
        recordConstructorBindings[expression] = binding
        val constructedType = TypeRef(binding.symbol.name, arguments = binding.declaration?.typeParameters.orEmpty().mapNotNull { substitutions[it.name] })
        references +=
            ReferenceInfo(
                binding.symbol.name,
                expression.callee.range,
                binding.symbol,
                constructedType.displayName,
            )
        return constructedType
    }

    private fun analyzeScopeCall(
        expression: ScopeAccessExpression,
        argumentCount: Int,
    ): FunctionBinding? {
        val module = importedModules[expression.qualifier]
        if (module == null) {
            val alias = importAliases[expression.qualifier]
            if (alias != null) {
                val function = alias.exports.functions[expression.name]
                if (function == null) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Namespace `${expression.qualifier}` has no member `${expression.name}`.",
                            expression.range,
                        )
                    return null
                }
                return functionBindingForExport(
                    visibleName = expression.name,
                    function = function,
                    exports = alias.exports,
                    qualifier = expression.qualifier,
                    range = expression.range,
                )
            }
            diagnostics +=
                FrontendDiagnostic(
                    "Unknown namespace `${expression.qualifier}`.",
                    expression.qualifierRange,
                )
            return null
        }
        val builtin =
            module.module.functions.firstOrNull {
                it.name == expression.name && it.parameterTypes.size == argumentCount
            }
        if (builtin == null) {
            diagnostics +=
                FrontendDiagnostic(
                    "Namespace `${expression.qualifier}` has no member `${expression.name}` with $argumentCount arguments.",
                    expression.range,
                )
            return null
        }
        val symbol =
            SymbolInfo(
                name = builtin.name,
                kind = SymbolKind.BUILTIN_FUNCTION,
                range = expression.range,
                detail = "${module.module.name}::${builtin.name}(${builtin.parameterTypes.joinToString()}) : ${builtin.returnType}",
                documentation = builtin.documentation,
            )
        references +=
            ReferenceInfo(
                expression.name,
                expression.range,
                symbol,
                builtin.returnType,
            )
        return FunctionBinding(
            symbol = symbol,
            declaration = null,
            parameterTypes = builtin.parameterTypes.map(::TypeRef),
            returnType = TypeRef(builtin.returnType),
            builtinModuleName = module.module.name,
        )
    }

    private fun analyzeScope(expression: ScopeAccessExpression): Pair<Binding, TypeRef> {
        val binding = analyzeScopeCall(expression, argumentCount = 0)
        return if (binding != null) {
            binding to binding.returnType
        } else {
            errorBinding(expression.range, "unknown namespace member") to TypeRef("Unit")
        }
    }

    private fun analyzeRecordConstruction(
        expression: RecordConstructionExpression,
        scope: Scope,
    ): TypeRef {
        if (expression.qualifier != null) {
            val alias = importAliases[expression.qualifier]
            if (alias != null) {
                val struct = alias.exports.structs[expression.typeName]
                if (struct == null) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Namespace `${expression.qualifier}` has no member `${expression.typeName}`.",
                            expression.range,
                        )
                    return TypeRef("Unit")
                }
                val binding =
                    recordBindingForExport(
                        visibleName = "${expression.qualifier}::${expression.typeName}",
                        struct = struct,
                        exports = alias.exports,
                        qualifier = expression.qualifier,
                        range = expression.range,
                    )
                userRecordsByName[binding.symbol.name] = binding
                expression.fields.forEach { field ->
                    val expected = binding.fields[field.name]
                    if (expected == null) {
                        diagnostics += FrontendDiagnostic("Struct `${binding.symbol.name}` has no field `${field.name}`.", field.range)
                    } else {
                        val actual = analyzeExpression(field.expression, scope)
                        expectAssignable(actual, expected, field.range, "Struct field type mismatch.")
                    }
                }
                return TypeRef(binding.symbol.name)
            }
            diagnostics +=
                FrontendDiagnostic(
                    "Qualified record construction is not yet supported.",
                    expression.range,
                )
            return TypeRef(expression.typeName)
        }
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

    private fun analyzeLegacyRecordConstruction(
        expression: LegacyRecordConstructionExpression,
        scope: Scope,
    ): TypeRef {
        diagnostics +=
            FrontendDiagnostic(
                "Old record construction syntax is no longer valid. Use `${expression.typeName}(x = value)` instead.",
                expression.range,
            )
        expression.fields.forEach { analyzeExpression(it.expression, scope) }
        return TypeRef("Unit")
    }

    private fun analyzeListLiteral(
        expression: ListLiteralExpression,
        scope: Scope,
        expectedType: TypeRef?,
    ): TypeRef {
        val expectedElement = expectedType?.takeIf { it.name == "List" && it.arguments.size == 1 }?.arguments?.single()
        val elementTypes = expression.elements.map { analyzeExpression(it, scope, expectedElement) }
        if (expectedElement != null) {
            elementTypes.zip(expression.elements).forEach { (actual, element) ->
                expectAssignable(actual, expectedElement, element.range, "List literal element type mismatch.")
            }
            return collectionType("List", expectedElement)
        }
        if (elementTypes.isEmpty()) {
            diagnostics += FrontendDiagnostic("Empty list literal requires an expected List<T> type.", expression.range)
            return collectionType("List", TypeRef("Unit"))
        }
        val elementType = elementTypes.first()
        elementTypes.drop(1).zip(expression.elements.drop(1)).forEach { (actual, element) ->
            expectAssignable(actual, elementType, element.range, "List literal element type mismatch.")
        }
        return collectionType("List", elementType)
    }

    private fun analyzeMapLiteral(
        expression: MapLiteralExpression,
        scope: Scope,
        expectedType: TypeRef?,
    ): TypeRef {
        val expectedMap = expectedType?.takeIf { it.name == "Map" && it.arguments.size == 2 }
        val expectedKey = expectedMap?.arguments?.get(0)
        val expectedValue = expectedMap?.arguments?.get(1)
        val keyTypes = mutableListOf<TypeRef>()
        val valueTypes = mutableListOf<TypeRef>()
        expression.entries.forEach { entry ->
            keyTypes += analyzeExpression(entry.key, scope, expectedKey)
            valueTypes += analyzeExpression(entry.value, scope, expectedValue)
        }
        if (expectedKey != null && expectedValue != null) {
            expression.entries.forEachIndexed { index, entry ->
                expectAssignable(keyTypes[index], expectedKey, entry.key.range, "Map literal key type mismatch.")
                expectAssignable(valueTypes[index], expectedValue, entry.value.range, "Map literal value type mismatch.")
            }
            return collectionType("Map", expectedKey, expectedValue)
        }
        if (expression.entries.isEmpty()) {
            diagnostics += FrontendDiagnostic("Empty map literal requires an expected Map<K, V> type.", expression.range)
            return collectionType("Map", TypeRef("Unit"), TypeRef("Unit"))
        }
        val keyType = keyTypes.first()
        val valueType = valueTypes.first()
        expression.entries.drop(1).forEachIndexed { index, entry ->
            expectAssignable(keyTypes[index + 1], keyType, entry.key.range, "Map literal key type mismatch.")
            expectAssignable(valueTypes[index + 1], valueType, entry.value.range, "Map literal value type mismatch.")
        }
        return collectionType("Map", keyType, valueType)
    }

    private fun analyzeIndexAccess(
        expression: IndexAccessExpression,
        scope: Scope,
    ): TypeRef {
        val receiverType = analyzeExpression(expression.receiver, scope)
        val indexType = analyzeExpression(expression.index, scope)
        receiverType.elementType()?.let { elementType ->
            expectAssignable(indexType, TypeRef("Int"), expression.index.range, "Index type mismatch.")
            return elementType
        }
        val keyType = receiverType.mapKeyType()
        val valueType = receiverType.mapValueType()
        if (keyType != null && valueType != null) {
            expectAssignable(indexType, keyType, expression.index.range, "Index type mismatch.")
            return valueType.copy(nullable = true)
        }
        diagnostics += FrontendDiagnostic("Type `${receiverType.displayName}` is not indexable.", expression.range)
        return TypeRef("Unit")
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
        val arguments = syntax.arguments.mapNotNull { resolveType(it, it.range) }
        if (syntax.qualifier != null) {
            val alias = importAliases[syntax.qualifier]
            if (alias != null) {
                val struct = alias.exports.structs[syntax.name]
                if (struct != null) {
                    val visibleName = "${syntax.qualifier}::${syntax.name}"
                    userRecordsByName.getOrPut(visibleName) {
                        recordBindingForExport(visibleName, struct, alias.exports, syntax.qualifier, syntax.range)
                    }
                    return TypeRef(visibleName, nullable = syntax.nullable, arguments = arguments)
                }
                diagnostics +=
                    FrontendDiagnostic(
                        "Namespace `${syntax.qualifier}` has no type `${syntax.name}`.",
                        syntax.range,
                    )
                return TypeRef(syntax.name, nullable = syntax.nullable, arguments = arguments)
            }
            diagnostics +=
                FrontendDiagnostic(
                    "Qualified types are not yet supported. " +
                        "User-file imports introducing namespaces will land in the next version.",
                    syntax.range,
                )
            return TypeRef(syntax.name, nullable = syntax.nullable, arguments = arguments)
        }
        if (isTypeParameter(syntax.name)) {
            if (arguments.isNotEmpty()) {
                diagnostics +=
                    FrontendDiagnostic(
                        "Type parameter `${syntax.name}` cannot have type arguments.",
                        syntax.range,
                    )
            }
            return TypeRef(syntax.name, nullable = syntax.nullable, typeParameter = true)
        }
        val type =
            typeNames[syntax.name] ?: run {
                diagnostics +=
                    FrontendDiagnostic(
                        "Unknown type `${syntax.displayName}`.",
                        range,
                    )
                return null
            }
            registry.builtinType(syntax.name)?.let { builtinType ->
                if (builtinType.typeParameterCount != arguments.size) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Type `${syntax.name}` expects ${builtinType.typeParameterCount} type argument(s), got ${arguments.size}.",
                            syntax.range,
                        )
                }
            }
            return type.copy(nullable = syntax.nullable, arguments = arguments)
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
            (actual.nullable && expected.nullable && actual.name == expected.name && actual.arguments == expected.arguments)

    private fun errorBinding(
        range: SourceRange,
        detail: String,
    ): VariableBinding =
        VariableBinding(
            SymbolInfo(
                "<error>",
                SymbolKind.VARIABLE,
                range,
                detail,
            ),
            TypeRef("Unit"),
        )

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
    allSemantics: List<SemanticResult> = listOf(semantic),
) {
    private val semantics = allSemantics.distinctBy { it.program }

    private data class FunctionTarget(
        val semantic: SemanticResult,
        val declaration: FunctionDeclaration,
        val ownerClass: ClassBinding? = null,
        val static: Boolean = false,
        val returnType: TypeRef? = null,
    )

    private val functionTargets =
        semantics.flatMap { result ->
            val topLevel =
                result.program.declarations.filterIsInstance<FunctionDeclaration>().map { declaration ->
                    FunctionTarget(result, declaration, returnType = result.functionBindings[declaration]?.returnType)
                }
            val methods =
                result.classBindings.values.flatMap { owner ->
                    (owner.instanceMethods.values + owner.staticMethods.values).map { method ->
                        FunctionTarget(
                            result,
                            method.function,
                            ownerClass = owner,
                            static = method.static,
                            returnType = method.returnType,
                        )
                    }
                }
            topLevel + methods
        }
    private val functionIndices =
        functionTargets.mapIndexed { index, target -> target.declaration to index }.toMap()

    fun compile(name: String): BytecodeModule {
        val functions =
            functionTargets.map(::compileFunction)
        val records =
            semantics.flatMap { result ->
                result.program.declarations.filterIsInstance<StructDeclaration>().map { declaration ->
                    BytecodeRecord(
                        name = mangle(result.sourceName, declaration.name),
                        fields =
                            declaration.fields.map { field ->
                                RecordFieldDefinition(
                                    name = field.name,
                                    typeName = field.type.displayName.removeSuffix("?"),
                                )
                            },
                    )
                }
            }
        val classes =
            semantics.flatMap { result ->
                result.classBindings.values.map { binding ->
                    BytecodeClass(
                        name = binding.symbol.name,
                        fields =
                            binding.fields.values.map { field ->
                                BytecodeClassField(field.name, field.type.name, field.mutable)
                            },
                        initFunctionIndex = null,
                        instanceMethods = binding.instanceMethods.mapValues { (_, method) -> functionIndices[method.function] ?: 0 },
                        staticMethods = binding.staticMethods.mapValues { (_, method) -> functionIndices[method.function] ?: 0 },
                    )
                }
            }
        val entryIndex =
            functionTargets
                .indexOfFirst { target -> target.semantic == semantic && target.ownerClass == null && target.declaration.name == "main" }
        return BytecodeModule(
            name = name,
            functions = functions,
            records = records,
            entryFunctionIndex = entryIndex.coerceAtLeast(0),
            registry = registry,
            classes = classes,
        )
    }

    private fun compileFunction(target: FunctionTarget): BytecodeFunction {
        val semantic = target.semantic
        val declaration = target.declaration
        val parameters =
            buildList {
                if (target.ownerClass != null && !target.static) {
                    add(BytecodeLocal("this", target.ownerClass.symbol.name))
                }
                addAll(
                    declaration.parameters.map { parameter ->
                        BytecodeLocal(parameter.name, parameter.type.displayName.removeSuffix("?"))
                    },
                )
            }
        val compiler = FunctionCompiler(semantic, declaration, parameters, hasThis = target.ownerClass != null && !target.static)
        compiler.compileBlock(declaration.body)
        compiler.instructions += Instruction.PushUnit
        compiler.instructions += Instruction.Return
        return BytecodeFunction(
            name =
                target.ownerClass?.let { "${it.symbol.name}.${if (target.static) "static." else ""}${declaration.name}" }
                    ?: mangle(semantic.sourceName, declaration.name),
            parameters = parameters,
            locals = compiler.locals,
            returnType = target.returnType?.name ?: "Unit",
            instructions = compiler.instructions,
            sourceRange = declaration.range,
        )
    }

    private fun mangle(
        canonical: String,
        name: String,
    ): String = "$canonical#$name"

    private inner class FunctionCompiler(
        private val semantic: SemanticResult,
        private val declaration: FunctionDeclaration,
        parameters: List<BytecodeLocal>,
        private val hasThis: Boolean = false,
    ) {
        val instructions = mutableListOf<Instruction>()
        val locals = parameters.toMutableList()
        private val scopes = ArrayDeque<MutableMap<String, Int>>()
        private var temporaryThisSlot: Int? = null

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

                is MemberAssignmentStatement -> {
                    compileExpression(statement.receiver)
                    compileExpression(statement.expression)
                    instructions += Instruction.SetField(statement.memberName)
                    instructions += Instruction.Pop
                }

                is IndexAssignmentStatement -> {
                    compileExpression(statement.receiver)
                    compileExpression(statement.index)
                    compileExpression(statement.expression)
                    instructions += Instruction.Pop
                    instructions += Instruction.Pop
                    instructions += Instruction.Pop
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
                    val method = semantic.methodCallBindings[expression]
                    if (method != null && expression.callee is MemberAccessExpression) {
                        if (!method.static) {
                            compileExpression(expression.callee.receiver)
                        }
                        expression.arguments.forEach { compileExpression(it.expression) }
                        val index = functionIndices[method.function] ?: 0
                        instructions += Instruction.CallFunction(index, expression.arguments.size + if (method.static) 0 else 1)
                        return
                    }
                    if (semantic.classConstructorBindings.containsKey(expression)) {
                        compileClassConstructor(expression, semantic.classConstructorBindings.getValue(expression))
                        return
                    }
                    val recordConstructor = semantic.recordConstructorBindings[expression]
                    if (recordConstructor != null) {
                        val namedArguments = expression.arguments.filterIsInstance<NamedCallArgument>().associateBy { it.name }
                        recordConstructor.fields.keys.forEach { fieldName ->
                            namedArguments[fieldName]?.let { compileExpression(it.expression) }
                        }
                        instructions += Instruction.ConstructRecord(recordConstructor.symbol.name, recordConstructor.fields.keys.toList())
                        return
                    }
                    expression.arguments.forEach { compileExpression(it.expression) }
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

                is ThisExpression -> {
                    val slot = temporaryThisSlot ?: if (hasThis) 0 else null
                    if (slot != null) instructions += Instruction.LoadLocal(slot) else instructions += Instruction.PushUnit
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

                is LegacyRecordConstructionExpression -> {
                    instructions += Instruction.PushUnit
                }

                is ListLiteralExpression -> {
                    expression.elements.forEach(::compileExpression)
                    repeat(expression.elements.size) {
                        instructions += Instruction.Pop
                    }
                    instructions += Instruction.PushUnit
                }

                is MapLiteralExpression -> {
                    expression.entries.forEach { entry ->
                        compileExpression(entry.key)
                        compileExpression(entry.value)
                    }
                    repeat(expression.entries.size * 2) {
                        instructions += Instruction.Pop
                    }
                    instructions += Instruction.PushUnit
                }

                is IndexAccessExpression -> {
                    compileExpression(expression.receiver)
                    compileExpression(expression.index)
                    instructions += Instruction.Pop
                    instructions += Instruction.Pop
                    instructions += Instruction.PushUnit
                }

                is TypeApplicationExpression -> {
                    instructions += Instruction.PushUnit
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

        private fun compileClassConstructor(
            expression: CallExpression,
            classBinding: ClassBinding,
        ) {
            val namedArguments = expression.arguments.filterIsInstance<NamedCallArgument>().associateBy { it.name }
            val constructorParameterSlots = mutableMapOf<String, Int>()
            classBinding.constructorParameters.forEach { parameter ->
                val argument = namedArguments[parameter.name] ?: return@forEach
                compileExpression(argument.expression)
                val slot = locals.size
                locals += BytecodeLocal("\$${parameter.name}", parameter.type.name)
                instructions += Instruction.StoreLocal(slot)
                constructorParameterSlots[parameter.name] = slot
            }
            val fieldNames = mutableListOf<String>()
            classBinding.constructorParameters.forEach { parameter ->
                if (parameter.fieldMutability != null) {
                    constructorParameterSlots[parameter.name]?.let { slot ->
                        instructions += Instruction.LoadLocal(slot)
                        fieldNames += parameter.name
                    }
                }
            }
            instructions += Instruction.ConstructClass(classBinding.symbol.name, fieldNames)
            if (classBinding.declaration.members.any { it is ClassFieldDeclaration || it is ClassInitBlock }) {
                val objectSlot = locals.size
                locals += BytecodeLocal("\$${classBinding.symbol.name}", classBinding.symbol.name)
                instructions += Instruction.StoreLocal(objectSlot)
                scopes.addLast(constructorParameterSlots.toMutableMap())
                val previousThisSlot = temporaryThisSlot
                temporaryThisSlot = objectSlot
                classBinding.declaration.members.forEach { member ->
                    when (member) {
                        is ClassFieldDeclaration -> {
                            member.initializer?.let { initializer ->
                                instructions += Instruction.LoadLocal(objectSlot)
                                compileExpression(initializer)
                                instructions += Instruction.SetField(member.name)
                                instructions += Instruction.Pop
                            }
                        }

                        is ClassInitBlock -> {
                            compileBlock(member.body)
                        }

                        is ClassMethodDeclaration -> {
                            Unit
                        }
                    }
                }
                temporaryThisSlot = previousThisSlot
                scopes.removeLast()
                instructions += Instruction.LoadLocal(objectSlot)
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
    private val mutableComments = mutableListOf<CommentTrivia>()
    val comments: List<CommentTrivia>
        get() = mutableComments.toList()
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

                '[' -> {
                    addToken(TokenKind.LBRACKET, "[", start)
                }

                ']' -> {
                    addToken(TokenKind.RBRACKET, "]", start)
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
                        lexLineComment(start)
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

    private fun lexLineComment(start: SourceLocation) {
        val textStart = index
        while (!isAtEnd() && peek() != '\n') advance()
        mutableComments +=
            CommentTrivia(
                kind = CommentKind.LINE,
                text = source.substring(textStart, index),
                range = SourceRange(start, location()),
            )
    }

    private fun lexString(start: SourceLocation) {
        val builder = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            val next = advance()
            if (next == '\\' && !isAtEnd()) {
                builder.append(
                    when (val escaped = advance()) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
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
        val textStart = index
        while (!isAtEnd()) {
            if (peek() == '*' && index + 1 < source.length && source[index + 1] == '/') {
                val text = source.substring(textStart, index)
                advance()
                advance()
                mutableComments +=
                    CommentTrivia(
                        kind = CommentKind.BLOCK,
                        text = text,
                        range = SourceRange(start, location()),
                    )
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
                "pub" -> TokenKind.PUB
                "import" -> TokenKind.IMPORT
                "as" -> TokenKind.AS
                "struct" -> TokenKind.STRUCT
                "class" -> TokenKind.CLASS
                "static" -> TokenKind.STATIC
                "init" -> TokenKind.INIT
                "this" -> TokenKind.THIS
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
            val visibility = parseVisibility()
            when {
                visibility == Visibility.PRIVATE && match(TokenKind.IMPORT) -> {
                    val imp = parseImport()
                    if (imp != null) imports += imp else synchronize()
                }

                match(TokenKind.FUN) -> {
                    val decl = parseFunction(visibility)
                    if (decl != null) declarations += decl else synchronize()
                }

                match(TokenKind.STRUCT) -> {
                    val decl = parseStruct(visibility)
                    if (decl != null) declarations += decl else synchronize()
                }

                match(TokenKind.CLASS) -> {
                    val decl = parseClass(visibility)
                    if (decl != null) declarations += decl else synchronize()
                }

                visibility == Visibility.PUBLIC -> {
                    diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
                    synchronize()
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

    private fun parseVisibility(): Visibility = if (match(TokenKind.PUB)) Visibility.PUBLIC else Visibility.PRIVATE

    private fun synchronize() {
        while (!isAtEnd()) {
            if (match(TokenKind.SEMICOLON)) return
            if (check(TokenKind.PUB) || check(TokenKind.FUN) || check(TokenKind.IMPORT) || check(TokenKind.STRUCT) ||
                check(TokenKind.CLASS)
            ) {
                return
            }
            advance()
        }
    }

    private fun parseImport(): ImportDeclaration? {
        val keyword = previous()
        val source =
            when {
                check(TokenKind.STRING) -> {
                    val pathToken = advance()
                    val path = pathToken.text
                    if (!path.endsWith(".ck")) {
                        diagnostics +=
                            FrontendDiagnostic(
                                "Import path must end with `.ck` (got `$path`).",
                                pathToken.range,
                            )
                    }
                    ImportSource.FilePath(path, pathToken.range)
                }

                check(TokenKind.IDENTIFIER) -> {
                    val nameToken = advance()
                    ImportSource.BuiltinNamespace(nameToken.text, nameToken.range)
                }

                else -> {
                    diagnostics += FrontendDiagnostic("Expected import source.", peek().range)
                    return null
                }
            }
        val mode =
            when {
                match(TokenKind.AS) -> {
                    val aliasToken = consume(TokenKind.IDENTIFIER, "Expected alias name after `as`.") ?: return null
                    ImportMode.Namespace(aliasToken.text, aliasToken.range)
                }

                match(TokenKind.LBRACE) -> {
                    parseSelectiveImportMode()
                }

                else -> {
                    ImportMode.Invalid(
                        message =
                            when (source) {
                                is ImportSource.FilePath -> {
                                    "Use `import \"${source.path}\" { name }` or `import \"${source.path}\" as alias`."
                                }

                                is ImportSource.BuiltinNamespace -> {
                                    "Use `import ${source.name} { name }`."
                                }
                            },
                        range = source.range,
                    )
                }
            }
        val end = consumeOptional(TokenKind.SEMICOLON) ?: previous()
        return ImportDeclaration(
            source = source,
            mode = mode,
            range = SourceRange(keyword.range.start, end.range.end),
        )
    }

    private fun parseSelectiveImportMode(): ImportMode.Selective {
        val start = previous().range.start
        val items = mutableListOf<ImportItem>()
        if (!check(TokenKind.RBRACE)) {
            do {
                val itemToken = consume(TokenKind.IDENTIFIER, "Expected imported name.") ?: break
                items += ImportItem(itemToken.text, itemToken.range)
            } while (match(TokenKind.COMMA))
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after import list.")?.range?.end ?: previous().range.end
        return ImportMode.Selective(items, SourceRange(start, end))
    }

    private fun parseFunction(visibility: Visibility = Visibility.PRIVATE): FunctionDeclaration? {
        val name = consume(TokenKind.IDENTIFIER, "Expected function name.") ?: return null
        val typeParameters = parseTypeParameterList()
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
        return FunctionDeclaration(name.text, parameters, returnType, body, visibility, SourceRange(name.range.start, body.range.end), typeParameters)
    }

    private fun parseStruct(visibility: Visibility): StructDeclaration? {
        val name = consume(TokenKind.IDENTIFIER, "Expected struct name.") ?: return null
        val typeParameters = parseTypeParameterList()
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
        return StructDeclaration(name.text, fields, visibility, SourceRange(name.range.start, end.range.end), typeParameters)
    }

    private fun parseClass(visibility: Visibility): ClassDeclaration? {
        val keyword = previous()
        val name = consume(TokenKind.IDENTIFIER, "Expected class name.") ?: return null
        val typeParameters = parseTypeParameterList()
        consume(TokenKind.LPAREN, "Expected `(` after class name.") ?: return null
        val constructorParameters = mutableListOf<ClassConstructorParameter>()
        if (!check(TokenKind.RPAREN)) {
            do {
                constructorParameters += parseClassConstructorParameter() ?: return null
            } while (match(TokenKind.COMMA))
        }
        consume(TokenKind.RPAREN, "Expected `)` after class constructor parameters.") ?: return null
        consume(TokenKind.LBRACE, "Expected `{` after class constructor.") ?: return null
        val members = mutableListOf<ClassMemberDeclaration>()
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            members += parseClassMember() ?: return null
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after class body.") ?: return null
        return ClassDeclaration(
            name = name.text,
            constructorParameters = constructorParameters,
            members = members,
            visibility = visibility,
            range = SourceRange(keyword.range.start, end.range.end),
            typeParameters = typeParameters,
        )
    }

    private fun parseClassConstructorParameter(): ClassConstructorParameter? {
        val visibility = parseVisibility()
        val mutabilityToken =
            when {
                check(TokenKind.VAL) -> advance()
                check(TokenKind.VAR) -> advance()
                else -> null
            }
        if (visibility == Visibility.PUBLIC && mutabilityToken == null) {
            diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
        }
        val name = consume(TokenKind.IDENTIFIER, "Expected constructor parameter name.") ?: return null
        consume(TokenKind.COLON, "Expected `:` after constructor parameter name.") ?: return null
        val type = parseType() ?: return null
        return ClassConstructorParameter(
            name = name.text,
            type = type,
            fieldMutability =
                when (mutabilityToken?.kind) {
                    TokenKind.VAL -> FieldMutability.VAL
                    TokenKind.VAR -> FieldMutability.VAR
                    else -> null
                },
            visibility = visibility,
            range = SourceRange((mutabilityToken ?: name).range.start, type.range.end),
        )
    }

    private fun parseClassMember(): ClassMemberDeclaration? {
        val visibility = parseVisibility()
        return when {
            match(TokenKind.INIT) -> {
                if (visibility == Visibility.PUBLIC) {
                    diagnostics += FrontendDiagnostic("Unexpected `pub` modifier.", previous().range)
                }
                val start = previous().range.start
                val body = parseBlock() ?: return null
                ClassInitBlock(body, SourceRange(start, body.range.end))
            }

            match(TokenKind.STATIC) -> {
                val start = previous().range.start
                consume(TokenKind.FUN, "Expected `fun` after `static`.") ?: return null
                val function = parseFunction(visibility) ?: return null
                ClassMethodDeclaration(function, static = true, visibility = visibility, range = SourceRange(start, function.range.end))
            }

            match(TokenKind.FUN) -> {
                val start = previous().range.start
                val function = parseFunction(visibility) ?: return null
                ClassMethodDeclaration(function, static = false, visibility = visibility, range = SourceRange(start, function.range.end))
            }

            match(TokenKind.VAL) -> {
                parseClassField(mutable = false, visibility = visibility, start = previous())
            }

            match(TokenKind.VAR) -> {
                parseClassField(mutable = true, visibility = visibility, start = previous())
            }

            else -> {
                diagnostics += FrontendDiagnostic("Expected a class member declaration.", peek().range)
                null
            }
        }
    }

    private fun parseClassField(
        mutable: Boolean,
        visibility: Visibility,
        start: Token,
    ): ClassFieldDeclaration? {
        val name = consume(TokenKind.IDENTIFIER, "Expected field name.") ?: return null
        val type = if (match(TokenKind.COLON)) parseType() else null
        val initializer =
            if (match(TokenKind.EQUAL)) {
                parseExpression() ?: return null
            } else {
                null
            }
        consumeOptional(TokenKind.SEMICOLON)
        val end = initializer?.range?.end ?: type?.range?.end ?: name.range.end
        return ClassFieldDeclaration(
            name = name.text,
            type = type,
            mutable = mutable,
            visibility = visibility,
            initializer = initializer,
            range = SourceRange(start.range.start, end),
        )
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
                if ((check(TokenKind.THIS) || check(TokenKind.IDENTIFIER)) &&
                    peekAhead(1)?.kind == TokenKind.DOT &&
                    peekAhead(2)?.kind == TokenKind.IDENTIFIER &&
                    peekAhead(3)?.kind in COMPOUND_ASSIGN_KINDS
                ) {
                    parseMemberAssignment()
                } else if (check(TokenKind.IDENTIFIER) && peekAhead(1)?.kind in COMPOUND_ASSIGN_KINDS) {
                    parseAssignment()
                } else {
                    val expression = parseExpression() ?: return null
                    if (expression is IndexAccessExpression && match(TokenKind.EQUAL)) {
                        val value = parseExpression() ?: return null
                        consumeOptional(TokenKind.SEMICOLON)
                        return IndexAssignmentStatement(
                            receiver = expression.receiver,
                            index = expression.index,
                            expression = value,
                            range = SourceRange(expression.range.start, value.range.end),
                        )
                    }
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
                TokenKind.EQUAL -> {
                    rhs
                }

                TokenKind.PLUS_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.ADD, rhs)
                }

                TokenKind.MINUS_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.SUBTRACT, rhs)
                }

                TokenKind.STAR_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.MULTIPLY, rhs)
                }

                TokenKind.SLASH_EQUAL -> {
                    compoundDesugar(nameTok, BinaryOperator.DIVIDE, rhs)
                }

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

    private fun parseMemberAssignment(): MemberAssignmentStatement? {
        val receiver =
            when {
                match(TokenKind.THIS) -> {
                    ThisExpression(previous().range)
                }

                match(TokenKind.IDENTIFIER) -> {
                    NameExpression(previous().text, previous().range)
                }

                else -> {
                    diagnostics += FrontendDiagnostic("Expected assignment receiver.", peek().range)
                    return null
                }
            }
        consume(TokenKind.DOT, "Expected `.` after `this`.") ?: return null
        val field = consume(TokenKind.IDENTIFIER, "Expected member name after `this`.") ?: return null
        val opTok = advance()
        val rhs = parseExpression() ?: return null
        consumeOptional(TokenKind.SEMICOLON)
        val value: Expression =
            when (opTok.kind) {
                TokenKind.EQUAL -> {
                    rhs
                }

                TokenKind.PLUS_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.ADD, rhs)
                }

                TokenKind.MINUS_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.SUBTRACT, rhs)
                }

                TokenKind.STAR_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.MULTIPLY, rhs)
                }

                TokenKind.SLASH_EQUAL -> {
                    compoundMemberDesugar(receiver, field, BinaryOperator.DIVIDE, rhs)
                }

                else -> {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Expected `=`, `+=`, `-=`, `*=` or `/=` in assignment.",
                            opTok.range,
                        )
                    return null
                }
            }
        return MemberAssignmentStatement(
            receiver = receiver,
            memberName = field.text,
            memberRange = field.range,
            expression = value,
            range = SourceRange(receiver.range.start, value.range.end),
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

    private fun compoundMemberDesugar(
        receiver: Expression,
        field: Token,
        operator: BinaryOperator,
        rhs: Expression,
    ): Expression =
        BinaryExpression(
            left = MemberAccessExpression(receiver, field.text, SourceRange(receiver.range.start, field.range.end)),
            operator = operator,
            right = rhs,
            range = SourceRange(receiver.range.start, rhs.range.end),
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

    private fun parseTypeParameterList(): List<TypeParameterDeclaration> {
        if (!match(TokenKind.LT)) return emptyList()
        val parameters = mutableListOf<TypeParameterDeclaration>()
        if (!check(TokenKind.GT)) {
            do {
                val name = consume(TokenKind.IDENTIFIER, "Expected type parameter name.") ?: return parameters
                parameters += TypeParameterDeclaration(name.text, name.range)
            } while (match(TokenKind.COMMA))
        }
        consume(TokenKind.GT, "Expected `>` after type parameters.")
        return parameters
    }

    private fun parseTypeArgumentList(): List<TypeSyntax> {
        if (!match(TokenKind.LT)) return emptyList()
        val arguments = mutableListOf<TypeSyntax>()
        if (!check(TokenKind.GT)) {
            do {
                arguments += parseType() ?: return arguments
            } while (match(TokenKind.COMMA))
        }
        consume(TokenKind.GT, "Expected `>` after type arguments.")
        return arguments
    }

    private fun parseType(): TypeSyntax? {
        val first = consume(TokenKind.IDENTIFIER, "Expected type name.") ?: return null
        val (qualifier, name) =
            if (match(TokenKind.COLON_COLON)) {
                first.text to (consume(TokenKind.IDENTIFIER, "Expected type name after `::`.") ?: return null)
            } else {
                null to first
            }
        val arguments = parseTypeArgumentList()
        val nullable = match(TokenKind.QUESTION)
        return TypeSyntax(
            name = name.text,
            nullable = nullable,
            range = SourceRange(first.range.start, previous().range.end),
            qualifier = qualifier,
            arguments = arguments,
        )
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
                        val arguments = mutableListOf<CallArgument>()
                        if (!check(TokenKind.RPAREN)) {
                            do {
                                arguments += parseCallArgument() ?: return null
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

                    match(TokenKind.LBRACKET) -> {
                        val index = parseExpression() ?: return null
                        val end = consume(TokenKind.RBRACKET, "Expected `]` after index.") ?: return null
                        IndexAccessExpression(
                            expression,
                            index,
                            SourceRange(expression.range.start, end.range.end),
                        )
                    }

                    else -> {
                        return expression
                    }
                }
        }
    }

    private fun parseCallArgument(): CallArgument? {
        if (check(TokenKind.IDENTIFIER) && checkNext(TokenKind.EQUAL)) {
            val name = advance()
            advance()
            val value = parseExpression() ?: return null
            return NamedCallArgument(name.text, name.range, value, SourceRange(name.range.start, value.range.end))
        }
        val value = parseExpression() ?: return null
        return PositionalCallArgument(value, value.range)
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
                if (check(TokenKind.COLON_COLON)) {
                    advance()
                    val nameToken = consume(TokenKind.IDENTIFIER, "Expected name after `::`.") ?: return null
                    val scope =
                        ScopeAccessExpression(
                            qualifier = token.text,
                            name = nameToken.text,
                            qualifierRange = token.range,
                            range = SourceRange(token.range.start, nameToken.range.end),
                        )
                    if (check(TokenKind.LBRACE)) {
                        parseQualifiedRecordConstruction(scope)
                    } else {
                        scope
                    }
                } else if (check(TokenKind.LBRACE)) {
                    parseRecordConstruction(token)
                } else if (isTypeApplicationAhead()) {
                    val arguments = parseTypeArgumentList()
                    val end = previous()
                    TypeApplicationExpression(token.text, arguments, SourceRange(token.range.start, end.range.end))
                } else {
                    NameExpression(token.text, token.range)
                }
            }

            TokenKind.THIS -> {
                ThisExpression(token.range)
            }

            TokenKind.LPAREN -> {
                val expression = parseExpression() ?: return null
                val end = consume(TokenKind.RPAREN, "Expected `)` after expression.") ?: return null
                GroupExpression(expression, SourceRange(token.range.start, end.range.end))
            }

            TokenKind.LBRACKET -> {
                parseListLiteral(token)
            }

            TokenKind.LBRACE -> {
                parseMapLiteral(token)
            }

            else -> {
                diagnostics += FrontendDiagnostic("Expected an expression.", token.range)
                null
            }
        }
    }

    private fun parseListLiteral(start: Token): Expression? {
        val elements = mutableListOf<Expression>()
        if (!check(TokenKind.RBRACKET)) {
            do {
                elements += parseExpression() ?: return null
            } while (match(TokenKind.COMMA))
        }
        val end = consume(TokenKind.RBRACKET, "Expected `]` after list literal.") ?: return null
        return ListLiteralExpression(elements, SourceRange(start.range.start, end.range.end))
    }

    private fun parseMapLiteral(start: Token): Expression? {
        val entries = mutableListOf<MapEntryExpression>()
        if (!check(TokenKind.RBRACE)) {
            do {
                val key = parseExpression() ?: return null
                consume(TokenKind.COLON, "Expected `:` after map key.") ?: return null
                val value = parseExpression() ?: return null
                entries += MapEntryExpression(key, value, SourceRange(key.range.start, value.range.end))
            } while (match(TokenKind.COMMA))
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after map literal.") ?: return null
        return MapLiteralExpression(entries, SourceRange(start.range.start, end.range.end))
    }

    private fun isTypeApplicationAhead(): Boolean {
        if (!check(TokenKind.LT)) return false
        var depth = 0
        var offset = 0
        while (true) {
            val token = peekAhead(offset) ?: return false
            when (token.kind) {
                TokenKind.LT -> depth += 1
                TokenKind.GT -> {
                    depth -= 1
                    if (depth == 0) return peekAhead(offset + 1)?.kind == TokenKind.LPAREN
                }

                TokenKind.EOF -> return false
                else -> Unit
            }
            offset += 1
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
        return LegacyRecordConstructionExpression(nameToken.text, fields, SourceRange(nameToken.range.start, end.range.end))
    }

    private fun parseQualifiedRecordConstruction(scope: ScopeAccessExpression): Expression? {
        consume(TokenKind.LBRACE, "Expected `{` for record construction.") ?: return null
        val fields = mutableListOf<RecordFieldInitializer>()
        while (!check(TokenKind.RBRACE) && !isAtEnd()) {
            val fieldName = consume(TokenKind.IDENTIFIER, "Expected field name.") ?: return null
            consume(TokenKind.COLON, "Expected `:` after field name.") ?: return null
            val value = parseExpression() ?: return null
            fields +=
                RecordFieldInitializer(
                    fieldName.text,
                    value,
                    SourceRange(fieldName.range.start, value.range.end),
                )
            if (!match(TokenKind.COMMA)) break
        }
        val end = consume(TokenKind.RBRACE, "Expected `}` after record fields.") ?: return null
        return LegacyRecordConstructionExpression(
            typeName = scope.name,
            fields = fields,
            range = SourceRange(scope.range.start, end.range.end),
            qualifier = scope.qualifier,
        )
    }

    private fun consume(
        kind: TokenKind,
        message: String,
    ): Token? {
        if (check(kind)) return advance()
        diagnostics += FrontendDiagnostic(message, peek().range)
        return null
    }

    private fun consumeOptional(kind: TokenKind): Token? = if (check(kind)) advance() else null

    private fun match(kind: TokenKind): Boolean =
        if (check(kind)) {
            advance()
            true
        } else {
            false
        }

    private fun check(kind: TokenKind): Boolean = !isAtEnd() && peek().kind == kind

    private fun checkNext(kind: TokenKind): Boolean = peekAhead(1)?.kind == kind

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
