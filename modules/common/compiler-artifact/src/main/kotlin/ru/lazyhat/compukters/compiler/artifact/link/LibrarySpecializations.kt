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

package ru.lazyhat.compukters.compiler.artifact.link

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Import
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter

/**
 * Tooling-only concrete-template symbols carried by ordinary artifact exports.
 * Eligible names come from trusted K2 source provenance, never from user spelling.
 */
object LibrarySpecializations {
    private const val PREFIX = "@specialization:"

    fun isSpecializationExport(name: String): Boolean = name.startsWith(PREFIX)

    private fun typeName(name: String) = "${PREFIX}type:$name"

    fun export(
        module: Module,
        names: Set<String>,
    ): Module {
        val specialized =
            module.types.indices
                .filter { module.text(module.types[it].name) in names }
                .toSet()
        if (specialized.isEmpty()) return module
        val strings = module.strings.toMutableList()

        fun name(value: String): StringId {
            val text = MetadataText.of(value)
            var index = strings.indexOf(text)
            if (index < 0) {
                index = strings.size
                strings += text
            }
            return StringId.of(index.toUInt())
        }
        val exports = module.exports.toMutableList()
        specialized.sorted().forEach { index ->
            exports +=
                Export(
                    SymbolKind.TYPE,
                    ExportVisibility.PUBLIC_LIBRARY,
                    name(typeName(module.text(module.types[index].name))),
                    index.toUInt(),
                    TypeRef.Local(TypeId.of(index.toUInt())),
                )
        }
        module.functions.forEachIndexed { index, function ->
            if (module.functionOwner(function) in names) {
                exports +=
                    Export(
                        SymbolKind.FUNCTION,
                        ExportVisibility.PUBLIC_LIBRARY,
                        name(functionName(module, function)),
                        index.toUInt(),
                        function.signature,
                    )
            }
        }
        module.fields.forEachIndexed { index, field ->
            if (module.referenceName(field.owner) in names) {
                exports +=
                    Export(
                        SymbolKind.FIELD,
                        ExportVisibility.PUBLIC_LIBRARY,
                        name(fieldName(module, field)),
                        index.toUInt(),
                        field.owner,
                    )
            }
        }
        return module.copy(strings = strings, exports = exports).canonicalStrings()
    }

    fun ownsFunction(
        module: Module,
        function: Function,
        names: Set<String>,
    ): Boolean = module.functionOwner(function) in names

    /** Redirects complete trusted variants; reachability subsequently removes superseded local records. */
    fun reuse(
        application: Artifact,
        libraries: List<Artifact>,
        names: Set<String>,
        definitionModule: Int = 0,
    ): Artifact {
        if (names.isEmpty()) return application
        val modules =
            (
                application.modules.filterIndexed { index, module -> index != definitionModule && module.kind == ModuleKind.LIBRARY } +
                    libraries.flatMap { it.modules.filter { m -> m.kind == ModuleKind.LIBRARY } }
            ).distinctBy { ArtifactWriter.moduleSemanticHash(it).hex() }
        val source = application.modules[definitionModule]
        val owners = linkedMapOf<Int, Module>()
        source.types.forEachIndexed { index, type ->
            val name = source.text(type.name)
            if (name !in names) return@forEachIndexed
            val candidates =
                modules.filter { module ->
                    module.exports.any { it.kind == SymbolKind.TYPE && module.text(it.name) == typeName(name) }
                }
            require(candidates.size <= 1) { "ambiguous specialization owner for $name" }
            candidates.singleOrNull()?.let { owners[index] = it }
        }
        if (owners.isEmpty()) return application
        val strings = source.strings.toMutableList()
        val imports = source.imports.toMutableList()
        val typeImports = linkedMapOf<Int, TypeRef.Imported>()
        val functionImports = linkedMapOf<Int, FunctionRef.Imported>()
        val fieldImports = linkedMapOf<Int, FieldRef.Imported>()

        fun addImport(
            module: Module,
            kind: SymbolKind,
            exportName: String,
            signature: TypeRef?,
        ): ImportId {
            val text = MetadataText.of(exportName)
            var index = strings.indexOf(text)
            if (index < 0) {
                index = strings.size
                strings += text
            }
            val id = ImportId.of(imports.size.toUInt())
            require(module.exports.count { it.kind == kind && module.text(it.name) == exportName } == 1) {
                "incomplete specialization export $kind $exportName"
            }
            imports +=
                Import(
                    kind,
                    ModuleId.of(0u),
                    StringId.of(index.toUInt()),
                    signature ?: TypeRef.Imported(id),
                    ArtifactWriter.moduleSemanticHash(module),
                )
            return id
        }
        owners.forEach { (index, module) ->
            val name = source.text(source.types[index].name)
            val exported = module.exports.single { it.kind == SymbolKind.TYPE && module.text(it.name) == typeName(name) }
            val target = module.types[exported.localSymbol.toInt()]
            require(source.layout(source.types[index]) == module.layout(target)) {
                "inconsistent specialization layout for $name: ${source.layout(source.types[index])} != ${module.layout(target)}"
            }
            typeImports[index] = TypeRef.Imported(addImport(module, SymbolKind.TYPE, typeName(name), null))
        }

        fun type(reference: TypeRef): TypeRef = (reference as? TypeRef.Local)?.let { typeImports[it.id.value.toInt()] } ?: reference
        source.functions.forEachIndexed { index, function ->
            val ownerName = source.functionOwner(function) ?: return@forEachIndexed
            val ownerIndex = source.types.indexOfFirst { source.text(it.name) == ownerName }
            val module = owners[ownerIndex] ?: return@forEachIndexed
            functionImports[index] =
                FunctionRef.Imported(
                    addImport(
                        module,
                        SymbolKind.FUNCTION,
                        functionName(source, function),
                        function.signature,
                    ),
                )
        }
        source.fields.forEachIndexed { index, field ->
            val owner = field.owner as? TypeRef.Local ?: return@forEachIndexed
            val module = owners[owner.id.value.toInt()] ?: return@forEachIndexed
            fieldImports[index] = FieldRef.Imported(addImport(module, SymbolKind.FIELD, fieldName(source, field), type(field.owner)))
        }

        fun function(reference: FunctionRef): FunctionRef =
            (reference as? FunctionRef.Local)?.let { functionImports[it.id.value.toInt()] } ?: reference

        fun field(reference: FieldRef): FieldRef = (reference as? FieldRef.Local)?.let { fieldImports[it.id.value.toInt()] } ?: reference
        val rewritten =
            source
                .copy(
                    strings = strings,
                    imports = imports,
                    exports =
                        source.exports.filterNot {
                            when (it.kind) {
                                SymbolKind.TYPE -> it.localSymbol.toInt() in typeImports
                                SymbolKind.FUNCTION -> it.localSymbol.toInt() in functionImports
                                SymbolKind.FIELD -> it.localSymbol.toInt() in fieldImports
                            }
                        },
                ).rewrite(::type, ::function, ::field)
                .canonicalStrings()

        fun string(id: StringId): StringId = StringId.of(rewritten.strings.indexOf(source.strings[id.value.toInt()]).toUInt())
        return application.copy(
            modules = application.modules.mapIndexed { index, module -> if (index == definitionModule) rewritten else module },
            capabilities =
                if (definitionModule ==
                    0
                ) {
                    application.capabilities.map { it.copy(namespace = string(it.namespace), name = string(it.name)) }
                } else {
                    application.capabilities
                },
        )
    }

    private fun functionName(
        module: Module,
        function: Function,
    ): String = "${PREFIX}function:${module.functionOwner(function)}:${module.text(function.name)}:${module.signature(function.signature)}"

    private fun fieldName(
        module: Module,
        field: Field,
    ): String = "${PREFIX}field:${module.referenceName(field.owner)}:${module.text(field.name)}"

    private fun Module.functionOwner(function: Function): String? =
        function.owner?.let { referenceName(it) }
            ?: text(function.name).takeIf { it.startsWith("<init:") }?.removePrefix("<init:")?.removeSuffix(">")

    private fun Module.text(id: StringId): String = strings[id.value.toInt()].toString()

    private fun Module.referenceName(ref: TypeRef): String =
        when (ref) {
            is TypeRef.Local -> text(types[ref.id.value.toInt()].name)
            is TypeRef.Imported -> text(imports[ref.id.value.toInt()].targetName).removePrefix("${PREFIX}type:")
        }

    private fun Module.valueName(value: ValueType): String =
        when (value) {
            is ValueType.Ref -> referenceName(value.type) + if (value.nullable) "?" else ""
            else -> value.toString()
        }

    private fun Module.signature(ref: TypeRef): String {
        val type = types[(ref as TypeRef.Local).id.value.toInt()] as NominalType.Function
        return "(${type.parameters.joinToString(",") { valueName(it) }}):${valueName(type.result)}:${type.suspending}"
    }

    private fun Module.layout(type: NominalType): String {
        val parents =
            when (type) {
                is NominalType.Class -> listOfNotNull(type.superType) + type.interfaces
                is NominalType.Interface -> listOfNotNull(type.superType) + type.interfaces
                else -> error("specialization must be a class or interface")
            }.map { referenceName(it) }.sorted()
        return when (type) {
            is NominalType.Class -> {
                "class:${type.abstract}:${type.final}:$parents:" +
                    (type.fieldStart.toInt() until (type.fieldStart + type.fieldCount).toInt()).joinToString(",") {
                        val field = fields[it]
                        "${text(field.name)}:${valueName(field.type)}:${field.mutable}:${field.static}"
                    }
            }

            is NominalType.Interface -> {
                "interface:${type.sealed}:$parents"
            }

            else -> {
                error("specialization must be a class or interface")
            }
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

private fun Module.canonicalStrings(): Module {
    val ordered = strings.withIndex().sortedBy { it.value }
    val ids = ordered.withIndex().associate { (index, old) -> old.index to StringId.of(index.toUInt()) }
    return rewrite(string = { requireNotNull(ids[it.value.toInt()]) }).copy(strings = ordered.map { it.value })
}

private fun Module.rewrite(
    type: (TypeRef) -> TypeRef = { it },
    function: (FunctionRef) -> FunctionRef = { it },
    field: (FieldRef) -> FieldRef = { it },
    string: (StringId) -> StringId = { it },
): Module {
    fun value(value: ValueType): ValueType = if (value is ValueType.Ref) value.copy(type = type(value.type)) else value
    return copy(
        name = string(name),
        imports = imports.map { it.copy(targetName = string(it.targetName), expectedSignature = type(it.expectedSignature)) },
        exports = exports.map { it.copy(name = string(it.name), signature = type(it.signature)) },
        types =
            types.map {
                when (it) {
                    is NominalType.Class -> {
                        it.copy(
                            name = string(it.name),
                            superType = it.superType?.let(type),
                            interfaces = it.interfaces.map(type),
                        )
                    }

                    is NominalType.Interface -> {
                        it.copy(
                            name = string(it.name),
                            superType = it.superType?.let(type),
                            interfaces = it.interfaces.map(type),
                        )
                    }

                    is NominalType.Function -> {
                        it.copy(
                            name = string(it.name),
                            result = value(it.result),
                            parameters = it.parameters.map(::value),
                        )
                    }

                    is NominalType.Array -> {
                        it.copy(name = string(it.name), element = value(it.element))
                    }
                }
            },
        fields = fields.map { it.copy(owner = type(it.owner), name = string(it.name), type = value(it.type)) },
        functions =
            functions.map {
                it.copy(
                    owner = it.owner?.let(type),
                    name = string(it.name),
                    signature = type(it.signature),
                    values = it.values.map { value -> value.copy(semanticType = value(value.semanticType)) },
                )
            },
        exceptions = exceptions.map { it.copy(catchType = it.catchType?.let(type)) },
        blocks =
            blocks.map { block ->
                block.copy(
                    instructions =
                        block.instructions.map {
                            when (it) {
                                is Instruction.NewObject -> {
                                    it.copy(type = type(it.type))
                                }

                                is Instruction.NewArray -> {
                                    it.copy(type = type(it.type))
                                }

                                is Instruction.IsType -> {
                                    it.copy(type = type(it.type))
                                }

                                is Instruction.CheckedCast -> {
                                    it.copy(type = type(it.type))
                                }

                                is Instruction.FieldGet -> {
                                    it.copy(field = field(it.field))
                                }

                                is Instruction.FieldSet -> {
                                    it.copy(field = field(it.field))
                                }

                                is Instruction.StaticGet -> {
                                    it.copy(field = field(it.field))
                                }

                                is Instruction.StaticSet -> {
                                    it.copy(field = field(it.field))
                                }

                                is Instruction.Call -> {
                                    Instruction.Call(it.destination, function(it.function), it.arguments)
                                }

                                is Instruction.CallVirtual -> {
                                    Instruction.CallVirtual(it.destination, function(it.function), it.arguments)
                                }

                                is Instruction.CallInterface -> {
                                    Instruction.CallInterface(
                                        it.destination,
                                        function(it.function),
                                        it.arguments,
                                    )
                                }

                                is Instruction.CallSuspend -> {
                                    Instruction.CallSuspend(
                                        it.destination,
                                        function(it.function),
                                        it.arguments,
                                        it.resumeBlock,
                                    )
                                }

                                is Instruction.TaskSpawn -> {
                                    Instruction.TaskSpawn(it.destination, function(it.function), it.arguments)
                                }

                                else -> {
                                    it
                                }
                            }
                        },
                )
            },
    )
}
