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

package ru.lazyhat.compukters.compiler.k2.engine.library

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.k2.engine.PlatformFieldLink
import ru.lazyhat.compukters.compiler.k2.engine.PlatformFunctionLink
import ru.lazyhat.compukters.compiler.k2.engine.PlatformTypeLink
import ru.lazyhat.compukters.platform.bundle.PlatformModule

fun loadPlatformLibraries(modules: List<PlatformModule>): LoadedPlatformLibraries {
    val artifacts =
        modules.mapNotNull { module ->
            module.libraryFragment?.let { fragmentBytes ->
                val fragment = PlatformLibraryFragmentCodec.decode(fragmentBytes)
                require(fragment.module == module.id) { "platform library fragment identity mismatch" }
                module to symbolicLibraryArtifact(ArtifactReader.read(fragment.artifact.toByteArray()))
            }
        }
    val functions = mutableListOf<PlatformFunctionLink>()
    val types = mutableListOf<PlatformTypeLink>()
    val fields = mutableListOf<PlatformFieldLink>()
    artifacts.forEach { (platformModule, artifact) ->
        val library =
            artifact.modules.first { module ->
                module.kind == ModuleKind.LIBRARY && module.exports.any { it.kind == SymbolKind.FUNCTION }
            }
        val moduleHash = ArtifactWriter.moduleSemanticHash(library)
        functions +=
            platformModule.declarations
                .filter { declaration ->
                    !declaration.trustedExternal && declaration.signature.startsWith("fun(") &&
                        declaration.identity !in platformModule.sourceDeclarations
                }.map { declaration ->
                    val simpleName = declaration.symbol.substringAfterLast('.')
                    val candidates =
                        library.exports.filter { export ->
                            export.kind == SymbolKind.FUNCTION &&
                                library.strings[export.name.value.toInt()].toString().let { name ->
                                    name == simpleName || name.startsWith("$simpleName#")
                                }
                        }
                    val matching =
                        candidates.filter { export ->
                            library.functionSignature(export.signature).shortTypeNames() == declaration.signature.shortTypeNames()
                        }
                    val exactName = candidates.filter { export -> library.strings[export.name.value.toInt()].toString() == simpleName }
                    val sourceShape =
                        declaration.signature
                            .removePrefix("fun")
                            .replaceFirst(":", "->")
                            .shortTypeNames()
                    val ownerType = declaration.symbol.substringBeforeLast('.').substringAfterLast('.')
                    val sourceShapes = setOf(sourceShape, sourceShape.withLeadingSourceParameter(ownerType))
                    val mangled =
                        candidates.filter { export ->
                            library.strings[export.name.value.toInt()]
                                .toString()
                                .substringAfter('#', "")
                                .shortTypeNames() in sourceShapes
                        }
                    val export =
                        mangled.singleOrNull() ?: matching.singleOrNull() ?: exactName.singleOrNull() ?: candidates.singleOrNull()
                            ?: error(
                                "cannot uniquely match ${declaration.symbol} ${declaration.signature} to a platform export: " +
                                    candidates.joinToString { library.strings[it.name.value.toInt()].toString() },
                            )
                    PlatformFunctionLink(
                        declaration.symbol,
                        declaration.signature,
                        library.strings[export.name.value.toInt()].toString(),
                        moduleHash.copyOf(),
                        declaration.defaultArguments,
                    )
                }
        library.exports.filter { it.kind == SymbolKind.TYPE }.forEach { export ->
            val exportName = library.strings[export.name.value.toInt()].toString()
            types += PlatformTypeLink(exportName, exportName, moduleHash.copyOf())
        }
        library.exports.filter { it.kind == SymbolKind.FIELD }.forEach { export ->
            val exportName = library.strings[export.name.value.toInt()].toString()
            val field = library.fields[export.localSymbol.toInt()]
            val owner = field.owner as? TypeRef.Local ?: error("platform field $exportName has an imported owner")
            val ownerSymbol =
                library.strings[
                    library.types[owner.id.value.toInt()]
                        .name.value
                        .toInt(),
                ].toString()
            fields +=
                PlatformFieldLink(
                    exportName,
                    ownerSymbol,
                    exportName,
                    field.static,
                    moduleHash.copyOf(),
                )
        }
    }
    val linkOrder =
        compareBy<PlatformFunctionLink>({ it.moduleHash.toHex() }, PlatformFunctionLink::exportName, PlatformFunctionLink::symbol)
    val typeOrder = compareBy<PlatformTypeLink>({ it.moduleHash.toHex() }, PlatformTypeLink::exportName, PlatformTypeLink::symbol)
    val fieldOrder = compareBy<PlatformFieldLink>({ it.moduleHash.toHex() }, PlatformFieldLink::exportName, PlatformFieldLink::symbol)
    return LoadedPlatformLibraries(
        functions.sortedWith(linkOrder),
        types.distinctBy { "${it.moduleHash.toHex()}:${it.exportName}" }.sortedWith(typeOrder),
        fields.distinctBy { "${it.moduleHash.toHex()}:${it.exportName}" }.sortedWith(fieldOrder),
        artifacts.map(Pair<PlatformModule, Artifact>::second),
    )
}

data class LoadedPlatformLibraries(
    val functions: List<PlatformFunctionLink>,
    val types: List<PlatformTypeLink>,
    val fields: List<PlatformFieldLink>,
    val artifacts: List<Artifact>,
)

/** Removes container-relative module indexes from tooling identities; final linking restores executable indexes. */
private fun symbolicLibraryArtifact(artifact: Artifact): Artifact {
    val completed = mutableMapOf<Int, Module>()
    val active = mutableSetOf<Int>()

    fun normalize(index: Int): Module {
        completed[index]?.let { return it }
        require(active.add(index)) { "platform library dependency cycle" }
        val module = artifact.modules[index]
        val normalized =
            module.copy(
                imports =
                    module.imports.map { reference ->
                        val target = normalize(reference.targetModule.value.toInt())
                        reference.copy(targetModule = ModuleId.of(0u), targetModuleHash = ArtifactWriter.moduleSemanticHash(target))
                    },
            )
        active.remove(index)
        completed[index] = normalized
        return normalized
    }
    return artifact.copy(modules = artifact.modules.indices.map(::normalize))
}

private fun Module.functionSignature(reference: TypeRef): String {
    val type = types[(reference as TypeRef.Local).id.value.toInt()] as NominalType.Function
    return "fun(${type.parameters.joinToString(",", transform = ::canonicalType)}):${canonicalType(type.result)}"
}

private fun Module.canonicalType(type: ValueType): String =
    when (type) {
        ValueType.Unit -> {
            "kotlin.Unit"
        }

        ValueType.I32 -> {
            "kotlin.Int"
        }

        ValueType.I64 -> {
            "kotlin.Long"
        }

        ValueType.F32 -> {
            "kotlin.Float"
        }

        ValueType.F64 -> {
            "kotlin.Double"
        }

        ValueType.Bool -> {
            "kotlin.Boolean"
        }

        ValueType.Char -> {
            "kotlin.Char"
        }

        is ValueType.Ref -> {
            val name =
                when (val reference = type.type) {
                    is TypeRef.Local -> {
                        val nominal = types[reference.id.value.toInt()]
                        val base = strings[nominal.name.value.toInt()].toString()
                        if (nominal is NominalType.Array) "$base<${canonicalType(nominal.element)}>" else base
                    }

                    is TypeRef.Imported -> {
                        strings[imports[reference.id.value.toInt()].targetName.value.toInt()].toString()
                    }
                }
            name + if (type.nullable) "?" else ""
        }
    }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.shortTypeNames(): String =
    Regex("[A-Za-z_][A-Za-z0-9_.]*").replace(this) { match -> match.value.substringAfterLast('.') }

private fun String.withLeadingSourceParameter(type: String): String {
    require(startsWith("(")) { "not a source function shape: $this" }
    val insertion = if (this[1] == ')') type else "$type,"
    return replaceRange(1, 1, insertion)
}
