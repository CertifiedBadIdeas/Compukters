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

package ru.lazyhat.compukters.platform.k2.build

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import ru.lazyhat.compukters.platform.bundle.PlatformDeclaration
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.bundle.PlatformSource
import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class CompiledPlatformMetadata(
    val metadata: ImmutableBytes,
    val declarations: List<PlatformDeclaration>,
    val exportedSymbols: List<String>,
    val libraryDeclarations: List<PlatformLibraryDeclaration>,
)

data class PlatformLibraryDeclaration(
    val symbol: String,
    val signature: String,
    val sourcePath: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val kind: PlatformLibraryDeclarationKind,
)

enum class PlatformLibraryDeclarationKind {
    FUNCTION,
    PROPERTY,
}

data class DecodedPlatformMetadata(
    val module: PlatformModuleId,
    val declarations: List<PlatformDeclaration>,
    val exportedSymbols: List<String>,
)

/**
 * Compiles canonical declarations with Kotlin's common parser under the Compukters target identity.
 * FIR resolution consumes this deterministic declaration metadata in the next pipeline stage; this
 * compiler never installs a JVM classpath or a foreign standard library.
 */
@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
class PlatformMetadataCompiler {
    fun compile(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
    ): CompiledPlatformMetadata {
        require(sources.map(PlatformSource::path).toSet().size == sources.size) { "duplicate platform source paths" }
        val declarations = parse(module, sources).sortedWith(DECLARATION_ORDER)
        val keys = mutableSetOf<Pair<String, String>>()
        declarations.forEach { parsed ->
            val declaration = parsed.declaration
            require(keys.add(declaration.symbol to declaration.signature)) {
                "duplicate platform declaration ${declaration.symbol} ${declaration.signature}"
            }
        }
        val exports =
            declarations
                .filterNot(ParsedDeclaration::private)
                .map(ParsedDeclaration::declaration)
                .map(PlatformDeclaration::symbol)
                .distinct()
                .sorted()
        val publicDeclarations = declarations.map(ParsedDeclaration::declaration)
        val decoded = DecodedPlatformMetadata(module, publicDeclarations, exports)
        return CompiledPlatformMetadata(
            PlatformMetadataCodec.encode(decoded),
            publicDeclarations,
            exports,
            declarations.filter(ParsedDeclaration::hasBody).map { parsed ->
                val declaration = parsed.declaration
                PlatformLibraryDeclaration(
                    declaration.symbol,
                    declaration.signature,
                    declaration.sourcePath,
                    declaration.startUtf16,
                    declaration.endUtf16,
                    requireNotNull(parsed.libraryKind),
                )
            },
        )
    }

    private fun parse(
        module: PlatformModuleId,
        sources: List<PlatformSource>,
    ): List<ParsedDeclaration> {
        val disposable = Disposer.newDisposable("Compukters platform metadata compiler")
        return try {
            val configuration =
                CompilerConfiguration().apply {
                    put(CommonConfigurationKeys.MODULE_NAME, module.toString())
                    put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                }
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.METADATA_CONFIG_FILES,
                )
            val factory = KtPsiFactory(environment.project, markGenerated = false)
            sources
                .sortedBy(PlatformSource::path)
                .flatMap { source ->
                    val content = strictUtf8(source.content.toByteArray(), source.path)
                    val file = factory.createFile(source.path.substringAfterLast('/'), content)
                    val errors = file.collectDescendantsOfType<PsiErrorElement>()
                    require(errors.isEmpty()) {
                        "invalid Kotlin platform source ${source.path}: ${errors.joinToString { it.errorDescription }}"
                    }
                    val packageName = file.packageFqName.asString()
                    file.declarations.flatMap { declaration ->
                        collect(module, source.path, packageName, emptyList(), declaration)
                    }
                }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun collect(
        module: PlatformModuleId,
        sourcePath: String,
        packageName: String,
        owners: List<String>,
        declaration: KtDeclaration,
    ): List<ParsedDeclaration> {
        val named = declaration as? KtNamedDeclaration ?: return emptyList()
        val name =
            when {
                declaration is KtConstructor<*> -> "<init>"
                declaration is KtObjectDeclaration && declaration.isCompanion() -> "Companion"
                else -> named.name ?: return emptyList()
            }
        val symbol = (listOf(packageName).filter(String::isNotEmpty) + owners + name).joinToString(".")
        val external = declaration.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        val platformDeclaration =
            PlatformDeclaration(
                symbol = symbol,
                signature = signature(declaration),
                module = module,
                sourcePath = sourcePath,
                startUtf16 = declaration.declarationStartOffset(),
                endUtf16 = declaration.textRange.endOffset,
                trustedExternal = external,
            )
        val nested =
            (
                (declaration as? KtDeclarationContainer)?.declarations.orEmpty() +
                    listOfNotNull((declaration as? KtClass)?.primaryConstructor)
            )
                .flatMap { child -> collect(module, sourcePath, packageName, owners + name, child) }
        return listOf(
            ParsedDeclaration(
                platformDeclaration,
                declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) || declaration.hasModifier(KtTokens.INTERNAL_KEYWORD),
                when (declaration) {
                    is KtNamedFunction -> declaration.hasBody()
                    is KtProperty -> declaration.hasInitializer() || declaration.accessors.any { it.hasBody() }
                    else -> false
                },
                when (declaration) {
                    is KtNamedFunction -> PlatformLibraryDeclarationKind.FUNCTION
                    is KtProperty -> PlatformLibraryDeclarationKind.PROPERTY
                    else -> null
                },
            ),
        ) + nested
    }

    private fun signature(declaration: KtDeclaration): String =
        when (declaration) {
            is KtNamedFunction -> {
                val receiver =
                    declaration.receiverTypeReference
                        ?.text
                        ?.canonicalType()
                        ?.plus(".")
                        .orEmpty()
                val parameters = declaration.valueParameters.joinToString(",") { it.typeReference?.text?.canonicalType() ?: "?" }
                val result = declaration.typeReference?.text?.canonicalType() ?: if (declaration.hasBlockBody()) "Unit" else "?"
                "fun($receiver$parameters):$result"
            }

            is KtProperty -> {
                val receiver =
                    declaration.receiverTypeReference
                        ?.text
                        ?.canonicalType()
                        ?.plus(".")
                        .orEmpty()
                "${if (declaration.isVar) "var" else "val"}($receiver):${declaration.typeReference?.text?.canonicalType() ?: "?"}"
            }

            is KtTypeAlias -> {
                "typealias:${declaration.getTypeReference()?.text?.canonicalType() ?: "?"}"
            }

            is KtConstructor<*> -> {
                "constructor(${declaration.valueParameters.joinToString(",") { it.typeReference?.text?.canonicalType() ?: "?" }})"
            }

            is KtObjectDeclaration -> {
                if (declaration.isCompanion()) "companion" else "object"
            }

            is KtClass -> {
                val kind =
                    when {
                        declaration.isInterface() -> "interface"
                        declaration.isEnum() -> "enum"
                        declaration.isAnnotation() -> "annotation"
                        declaration.hasModifier(KtTokens.VALUE_KEYWORD) -> "value-class"
                        else -> "class"
                    }
                val parameters =
                    declaration.primaryConstructorParameters.joinToString(
                        ",",
                    ) { it.typeReference?.text?.canonicalType() ?: "?" }
                "$kind($parameters)"
            }

            is KtClassOrObject -> {
                "class"
            }

            else -> {
                declaration.javaClass.simpleName
            }
        }

    private data class ParsedDeclaration(
        val declaration: PlatformDeclaration,
        val private: Boolean,
        val hasBody: Boolean,
        val libraryKind: PlatformLibraryDeclarationKind?,
    )

    private companion object {
        val DECLARATION_ORDER =
            compareBy<ParsedDeclaration>(
                { it.declaration.symbol },
                { it.declaration.signature },
                { it.declaration.sourcePath },
                { it.declaration.startUtf16 },
            )
    }
}

object PlatformMetadataCodec {
    private const val FORMAT = 1
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte())

    fun encode(metadata: DecodedPlatformMetadata): ImmutableBytes {
        val bytes =
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { sink ->
                    sink.write(MAGIC)
                    sink.writeInt(FORMAT)
                    sink.string(metadata.module.namespace)
                    sink.string(metadata.module.name)
                    sink.writeInt(metadata.declarations.size)
                    metadata.declarations.forEach { declaration ->
                        sink.string(declaration.symbol)
                        sink.string(declaration.signature)
                        sink.string(declaration.sourcePath)
                        sink.writeInt(declaration.startUtf16)
                        sink.writeInt(declaration.endUtf16)
                        sink.writeBoolean(declaration.trustedExternal)
                    }
                    sink.writeInt(metadata.exportedSymbols.size)
                    metadata.exportedSymbols.forEach(sink::string)
                }
                output.toByteArray()
            }
        return ImmutableBytes.of(bytes)
    }

    fun decode(bytes: ImmutableBytes): DecodedPlatformMetadata =
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { source ->
            require(source.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "invalid platform metadata magic" }
            require(source.readInt() == FORMAT) { "unsupported platform metadata format" }
            val module = PlatformModuleId(source.string(), source.string())
            val declarations =
                List(source.count("declaration")) {
                    PlatformDeclaration(
                        symbol = source.string(),
                        signature = source.string(),
                        module = module,
                        sourcePath = source.string(),
                        startUtf16 = source.readInt().also { require(it >= 0) },
                        endUtf16 = source.readInt().also { require(it >= 0) },
                        trustedExternal = source.readBoolean(),
                    )
                }
            val exports = List(source.count("export")) { source.string() }
            require(source.read() == -1) { "trailing platform metadata bytes" }
            require(declarations == declarations.sortedWith(DECLARATION_ORDER)) { "platform metadata declarations are not canonical" }
            require(exports == exports.distinct().sorted()) { "platform metadata exports are not canonical" }
            DecodedPlatformMetadata(module, declarations, exports)
        }

    fun validateAgainstSources(
        metadata: DecodedPlatformMetadata,
        sources: List<PlatformSource>,
    ) {
        val compiled = PlatformMetadataCompiler().compile(metadata.module, sources)
        require(compiled.declarations == metadata.declarations) { "platform metadata/source declaration mismatch" }
        require(compiled.exportedSymbols == metadata.exportedSymbols) { "platform metadata/source export mismatch" }
    }

    private val DECLARATION_ORDER =
        compareBy<PlatformDeclaration>(
            PlatformDeclaration::symbol,
            PlatformDeclaration::signature,
            PlatformDeclaration::sourcePath,
            PlatformDeclaration::startUtf16,
        )
}

private fun DataOutputStream.string(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.string(): String {
    val length = readInt()
    require(length in 0..1_048_576) { "invalid platform metadata string length" }
    return strictUtf8(readNBytes(length), "platform metadata string").also {
        require(it.encodeToByteArray().size == length) { "truncated platform metadata string" }
    }
}

private fun DataInputStream.count(label: String): Int =
    readInt().also { require(it in 0..262_144) { "invalid platform metadata $label count" } }

private fun String.canonicalType(): String = replace(Regex("\\s+"), "")

private fun KtDeclaration.declarationStartOffset(): Int =
    modifierList?.textRange?.startOffset
        ?: when (this) {
            is KtNamedFunction -> funKeyword?.textRange?.startOffset
            is KtClassOrObject -> nameIdentifier?.textRange?.startOffset
            is KtProperty -> valOrVarKeyword.textRange.startOffset
            else -> null
        }
        ?: textRange.startOffset

private fun strictUtf8(
    bytes: ByteArray,
    label: String,
): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("$label is not valid UTF-8", failure)
    }
