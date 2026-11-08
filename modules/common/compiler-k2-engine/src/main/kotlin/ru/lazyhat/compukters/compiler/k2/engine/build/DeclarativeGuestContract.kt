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

package ru.lazyhat.compukters.compiler.k2.engine.build

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** Resolved server operations, with original Guest sources when analyzed on the native platform. */
data class DeclarativeGuestContract(
    val types: List<GuestContractType>,
    val nativeSources: Map<String, String> = emptyMap(),
    val boundaryRecords: Map<String, GuestContractType> = emptyMap(),
    val nativeInterfaces: List<NativeGuestInterface> = emptyList(),
) {
    val operations: List<GuestContractOperation> get() = types.flatMap { it.operations }
    val commands: Set<String>
        get() = operations.filter { it.kind == GuestOperationKind.COMMAND }.mapTo(sortedSetOf()) { bindingSymbol(it) }

    /** Source-level ABI names remain stable even when native implementation owners change. */
    fun bindingSymbol(operation: GuestContractOperation): String =
        if (nativeSources.isEmpty()) {
            operation.binding
        } else {
            val owner = operation.binding.substringBeforeLast('.')
            "${owner.substringBeforeLast('.')}.__Compukters${owner.substringAfterLast('.')}.${operation.binding.substringAfterLast('.')}"
        }

    val bindingAliases: Map<String, String> get() = operations.associate { bindingSymbol(it) to it.binding }
}

enum class GuestOperationKind { READ, CALL, COMMAND, WAIT }

data class GuestContractType(
    val packageName: String,
    val name: String,
    val deviceId: String?,
    val record: Boolean,
    val resource: Boolean,
    val fields: List<GuestContractParameter>,
    val operations: List<GuestContractOperation>,
    val constants: List<Pair<String, String>>,
    val documentation: String,
)

data class GuestContractOperation(
    val name: String,
    val result: String,
    val kind: GuestOperationKind,
    val binding: String,
    val device: Boolean,
    val parameters: List<GuestContractParameter>,
    val documentation: String,
)

data class GuestContractParameter(
    val name: String,
    val type: String,
    val constraints: List<GuestArgumentConstraint>,
)

data class GuestArgumentConstraint(
    val kind: String,
    val minimum: String = "",
    val maximum: String = "",
) {
    fun condition(
        value: String,
        type: String,
    ): String =
        when (kind) {
            "Finite" -> {
                if (type ==
                    "Float"
                ) {
                    "$value >= -Float.MAX_VALUE && $value <= Float.MAX_VALUE"
                } else {
                    "$value >= -Double.MAX_VALUE && $value <= Double.MAX_VALUE"
                }
            }

            "DoubleRange", "IntRange" -> {
                "$value >= $minimum && $value <= $maximum"
            }

            "MaxLength" -> {
                "$value.${if (type == "String") "length" else "size"} <= $maximum"
            }

            else -> {
                error("Unknown constraint")
            }
        }
}

private const val ANNOTATIONS = "ru.lazyhat.compukters.api.contracts"
private val scalarTypes = setOf("Unit", "Int", "Long", "Float", "Double", "Boolean", "Char", "String", "ByteArray")
private val typeAnnotations = setOf("GuestDevice", "GuestService", "GuestResource", "GuestRecord")
private val operationAnnotations =
    mapOf(
        "GuestRead" to GuestOperationKind.READ,
        "GuestCall" to GuestOperationKind.CALL,
        "GuestCommand" to GuestOperationKind.COMMAND,
        "GuestWait" to GuestOperationKind.WAIT,
    )
private val parameterAnnotations = setOf("Finite", "DoubleRange", "IntRange", "MaxLength")

@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
fun parseDeclarativeGuestContract(
    root: Path,
    addon: String,
): DeclarativeGuestContract {
    require(Files.isDirectory(root)) { "Addon contracts are missing in $root; author declarations in src/contract/kotlin" }
    val paths =
        Files.walk(root).use {
            it
                .filter { p ->
                    Files.isRegularFile(p) && p.fileName.toString().endsWith(".kt")
                }.sorted()
                .toList()
        }
    require(paths.isNotEmpty()) { "Addon contract has no Kotlin declarations: $root" }
    val disposable = Disposer.newDisposable("Compukters addon contract")
    try {
        val configuration =
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MODULE_NAME, "$addon-contract")
                put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            }
        val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.METADATA_CONFIG_FILES)
        val factory = KtPsiFactory(environment.project, markGenerated = false)
        val types =
            paths
                .flatMap { path ->
                    val relative = root.relativize(path).invariantSeparatorsPathString
                    val file = factory.createFile(path.fileName.toString(), Files.readString(path))

                    fun reject(reason: String): Nothing = throw IllegalArgumentException("$relative: $reason")
                    file.collectDescendantsOfType<PsiErrorElement>().firstOrNull()?.let {
                        reject(
                            "Invalid Kotlin contract: ${it.errorDescription}",
                        )
                    }
                    val pkg = file.packageFqName.asString()
                    if (pkg != addon && !pkg.startsWith("$addon.")) reject("Contract package must belong to $addon")
                    if (!relative.startsWith(pkg.replace('.', '/') + "/")) reject("Contract path must match its package $pkg")
                    if (file.fileAnnotationList != null) reject("File annotations are not supported in contracts")
                    val imports =
                        file.importDirectives.map { directive ->
                            if (directive.aliasName != null || directive.isAllUnder) reject("Use explicit, unaliased contract imports")
                            directive.importedFqName?.asString() ?: reject("Invalid contract import")
                        }
                    imports.forEach { value ->
                        val allowed =
                            value.startsWith("$ANNOTATIONS.") || value.startsWith("$addon.") || value in scalarTypes.map { "kotlin.$it" }
                        if (!allowed) reject("Unsupported contract import: $value")
                    }
                    val importMap = imports.associateBy { it.substringAfterLast('.') }
                    if (importMap.size != imports.size) reject("Contract imports must have unique simple names")
                    val resolver = ContractResolver(pkg, importMap, ::reject)
                    file.declarations.map { declaration -> resolver.type(declaration) }
                }.sortedBy { "${it.packageName}.${it.name}" }
        require(types.isNotEmpty()) { "Addon contract has no declarations" }
        require(types.distinctBy { "${it.packageName}.${it.name}" }.size == types.size) { "Duplicate addon contract type" }
        val records = types.filter { it.record }.mapTo(mutableSetOf()) { "${it.packageName}.${it.name}" }
        val resources = types.filter { it.resource }.mapTo(mutableSetOf()) { "${it.packageName}.${it.name}" }
        types.forEach { type ->
            fun validate(
                value: String,
                recordAllowed: Boolean,
                resourceAllowed: Boolean = false,
            ) {
                require(
                    value in scalarTypes || (recordAllowed && (value in records || value in guestMathematicalTypes)) ||
                        (resourceAllowed && value in resources),
                ) {
                    "${type.name}: unsupported boundary type $value"
                }
            }
            type.fields.forEach { field ->
                validate(field.type, true)
                require(field.type != "Unit") { "Record fields cannot be Unit" }
                require(field.type != "ByteArray") { "Record fields cannot be arrays" }
            }
            type.operations.forEach { operation ->
                validate(operation.result, true, true)
                operation.parameters.forEach { parameter ->
                    validate(parameter.type, true, true)
                    require(parameter.type != "Unit") { "Operation parameters cannot be Unit" }
                }
                require(operation.kind != GuestOperationKind.COMMAND || operation.result == "Unit") {
                    "${type.name}.${operation.name}: @GuestCommand must return Unit; use @GuestCall for a value-returning operation"
                }
                require(operation.kind != GuestOperationKind.READ || operation.result != "Unit") {
                    "${type.name}.${operation.name}: @GuestRead must return a value"
                }
            }
            val names =
                type.operations.map { it.name } +
                    type.operations.filter { it.kind == GuestOperationKind.COMMAND }.map { it.name + "Command" }
            require(names.distinct().size == names.size) { "${type.name}: overloaded or generated method name collision" }
        }
        val operations = types.flatMap { it.operations }
        require(operations.isNotEmpty()) { "Addon contract has no operations" }
        require(operations.distinctBy { it.binding }.size == operations.size) { "Duplicate contract binding" }
        require(
            operations
                .distinctBy {
                    it.binding.substringAfterLast('.')
                }.size == operations.size,
        ) { "Host operation names must be unique within an addon" }
        val contract = DeclarativeGuestContract(types)
        val boundary = GuestBoundaryTypes(contract)
        types.filter { it.record }.forEach { type ->
            val wire = boundary.wireType("${type.packageName}.${type.name}")
            require(
                wire == "${type.packageName}.${type.name}" ||
                    types.none {
                        "${it.packageName}.${it.name}" == wire
                    },
            ) { "Generated wire record name collision: $wire" }
        }
        operations.forEach { boundary.arguments(it) }
        return contract
    } finally {
        Disposer.dispose(disposable)
    }
}

private class ContractResolver(
    val pkg: String,
    val imports: Map<String, String>,
    val reject: (String) -> Nothing,
) {
    private fun identifier(value: String?): String {
        if (value == null || !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(value)) reject("Use ordinary ASCII contract identifiers")
        return value
    }

    private fun modifiers(
        value: KtModifierListOwner,
        allowed: Set<String> = setOf("public"),
    ) {
        value.modifierList?.node?.getChildren(null)?.forEach { child ->
            if (child.elementType is org.jetbrains.kotlin.lexer.KtModifierKeywordToken &&
                child.text !in allowed
            ) {
                reject("Unsupported contract modifier: ${child.text}")
            }
        }
    }

    private fun annotations(
        value: KtAnnotated,
        allowed: Set<String>,
    ): Map<String, KtAnnotationEntry> {
        val entries = value.annotationEntries
        entries.forEach { entry ->
            val name = entry.shortName?.asString() ?: reject("Invalid annotation")
            val spelling = entry.typeReference?.text ?: reject("Invalid annotation")
            val resolved = if ('.' in spelling) spelling else imports[spelling]
            if (name !in allowed || resolved != "$ANNOTATIONS.$name" ||
                entry.useSiteTarget != null
            ) {
                reject("Unsupported contract annotation: ${entry.text}")
            }
        }
        if (entries.map { it.shortName }.distinct().size != entries.size) reject("Duplicate contract annotation")
        return entries.associateBy { it.shortName!!.asString() }
    }

    private fun arguments(
        entry: KtAnnotationEntry?,
        names: List<String>,
    ): Map<String, String> {
        if (entry == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        entry.valueArguments.forEachIndexed { index, argument ->
            val name = argument.getArgumentName()?.asName?.asString() ?: names.getOrNull(index) ?: reject("Too many annotation arguments")
            if (name !in names || result.containsKey(name)) reject("Unknown or duplicate annotation argument: $name")
            result[name] = argument.getArgumentExpression()?.text ?: reject("Missing annotation value")
        }
        return result
    }

    private fun string(value: String): String {
        if (!Regex("\"[A-Za-z0-9_.$:/-]*\"").matches(value)) reject("Expected a plain identifier string literal")
        return value.substring(1, value.length - 1)
    }

    private fun resolve(reference: KtTypeReference?): String {
        val text = reference?.text?.replace(" ", "") ?: reject("Declare every contract type explicitly")
        if (!Regex(
                "[A-Za-z_][A-Za-z0-9_.]*",
            ).matches(text)
        ) {
            reject("Unsupported contract type $text; no generics, nullable or function types")
        }
        if (text in scalarTypes) return text
        val full = if ('.' in text) text else imports[text] ?: "$pkg.$text"
        if (full.removePrefix("kotlin.") in scalarTypes) return full.removePrefix("kotlin.")
        if (full.startsWith("$ANNOTATIONS.types.") &&
            "kotlin.math.${full.substringAfterLast('.')}" in guestMathematicalTypes
        ) {
            return "kotlin.math.${full.substringAfterLast('.')}"
        }
        return full
    }

    private fun constraints(
        parameter: KtParameter,
        type: String,
    ): List<GuestArgumentConstraint> {
        val annotations = annotations(parameter, parameterAnnotations)
        val constraints =
            annotations.map { (kind, entry) ->
                when (kind) {
                    "Finite" -> {
                        if (type !in setOf("Float", "Double") ||
                            entry.valueArguments.isNotEmpty()
                        ) {
                            reject("@Finite requires a Float or Double parameter")
                        }
                        GuestArgumentConstraint(kind)
                    }

                    "DoubleRange" -> {
                        if (type !in setOf("Float", "Double")) reject("@DoubleRange requires a Float or Double parameter")
                        val args = arguments(entry, listOf("min", "max"))
                        val minimum =
                            args["min"]?.toDoubleOrNull()
                                ?: if (args.containsKey("min")) reject("Range requires a finite numeric literal") else -Double.MAX_VALUE
                        val maximum =
                            args["max"]?.toDoubleOrNull()
                                ?: if (args.containsKey("max")) reject("Range requires a finite numeric literal") else Double.MAX_VALUE
                        if (!minimum.isFinite() || !maximum.isFinite() || minimum > maximum) reject("Invalid finite Double range")
                        GuestArgumentConstraint(kind, minimum.toString(), maximum.toString())
                    }

                    "IntRange" -> {
                        if (type != "Int") reject("@IntRange requires an Int parameter")
                        val args = arguments(entry, listOf("min", "max"))
                        val minimum =
                            args["min"]?.toIntOrNull()
                                ?: if (args.containsKey("min")) reject("Range requires an Int literal") else Int.MIN_VALUE
                        val maximum =
                            args["max"]?.toIntOrNull()
                                ?: if (args.containsKey("max")) reject("Range requires an Int literal") else Int.MAX_VALUE
                        if (minimum > maximum) reject("Invalid Int range")
                        GuestArgumentConstraint(kind, minimum.toString(), maximum.toString())
                    }

                    "MaxLength" -> {
                        if (type !in setOf("String", "ByteArray")) reject("@MaxLength requires String or ByteArray")
                        val size = arguments(entry, listOf("value"))["value"]?.toIntOrNull() ?: reject("@MaxLength requires an Int literal")
                        if (size !in 0..65535) reject("@MaxLength must be between 0 and 65535")
                        GuestArgumentConstraint(kind, maximum = size.toString())
                    }

                    else -> {
                        reject("Unsupported parameter annotation")
                    }
                }
            }
        // A finite range already rejects NaN and infinities, so do not emit duplicate comparisons.
        return if (constraints.any { it.kind == "DoubleRange" }) constraints.filter { it.kind != "Finite" } else constraints
    }

    fun type(declaration: KtDeclaration): GuestContractType {
        val type =
            declaration as? KtClass ?: reject("Only annotated device/service interfaces and immutable record data classes are allowed")
        val tags = annotations(type, typeAnnotations)
        if (tags.size != 1) reject("Every type needs one contract type annotation")
        val name = identifier(type.name)
        val record = tags.containsKey("GuestRecord")
        modifiers(type, if (record) setOf("public", "data") else setOf("public"))
        if (type.typeParameters.isNotEmpty() || type.superTypeListEntries.isNotEmpty() ||
            type.secondaryConstructors.isNotEmpty()
        ) {
            reject("Contract inheritance, generics and secondary constructors are not supported")
        }
        if (record) {
            if (!type.isData() || type.declarations.isNotEmpty() ||
                tags.getValue("GuestRecord").valueArguments.isNotEmpty()
            ) {
                reject("@GuestRecord must be a constructor-only immutable data class")
            }
            val fields =
                type.primaryConstructorParameters.map { parameter ->
                    modifiers(parameter, setOf("public", "val"))
                    if (!parameter.hasValOrVar() || parameter.isMutable ||
                        parameter.hasDefaultValue()
                    ) {
                        reject("Record fields must be val without initializers")
                    }
                    if (parameter.annotationEntries.isNotEmpty()) reject("Record field annotations are not supported")
                    GuestContractParameter(identifier(parameter.name), resolve(parameter.typeReference), emptyList())
                }
            if (fields.isEmpty() || fields.size > 64) reject("Records require 1..64 immutable fields")
            return GuestContractType(pkg, name, null, true, false, fields, emptyList(), emptyList(), type.docComment?.text.orEmpty())
        }
        if (!type.isInterface() || type.primaryConstructor != null) reject("Devices and services must be bodyless-method interfaces")
        val device = tags["GuestDevice"]
        val resource = tags.containsKey("GuestResource")
        val handle = device != null || resource
        val args =
            arguments(
                device ?: tags["GuestService"] ?: tags["GuestResource"],
                if (device !=
                    null
                ) {
                    listOf("id", "bindings")
                } else {
                    listOf("bindings")
                },
            )
        val id = if (device != null) args["id"]?.let(::string) ?: reject("@GuestDevice requires an id") else null
        if (id != null && !Regex("[a-z0-9_-]+:[a-z0-9_./-]+").matches(id)) reject("Invalid device contract id")
        val owner = args["bindings"]?.let(::string)?.ifEmpty { null } ?: "${name}Bindings"
        identifier(owner)
        val constants = mutableListOf<Pair<String, String>>()
        val operations = mutableListOf<GuestContractOperation>()
        type.declarations.forEach { member ->
            if (member is KtObjectDeclaration && member.isCompanion()) {
                if (member.superTypeListEntries.isNotEmpty() ||
                    member.annotationEntries.isNotEmpty()
                ) {
                    reject("A contract companion may contain only literal constants")
                }
                member.declarations.forEach { item ->
                    val property = item as? KtProperty ?: reject("Only literal constants are allowed in a contract companion")
                    modifiers(property, setOf("public", "const"))
                    if (!property.hasModifier(KtTokens.CONST_KEYWORD) || property.isVar || property.accessors.isNotEmpty() ||
                        property.annotationEntries.isNotEmpty()
                    ) {
                        reject("Only immutable literal constants are allowed")
                    }
                    val value = property.initializer?.text ?: reject("Constant requires a literal")
                    val t = resolve(property.typeReference)
                    val valid =
                        when (t) {
                            "Double", "Float" -> {
                                value
                                    .removeSuffix("f")
                                    .removeSuffix("F")
                                    .toDoubleOrNull()
                                    ?.isFinite() == true
                            }

                            "Int" -> {
                                value.toIntOrNull() != null
                            }

                            "Long" -> {
                                value.removeSuffix("L").toLongOrNull() != null
                            }

                            "Boolean" -> {
                                value == "true" || value == "false"
                            }

                            "String" -> {
                                Regex("\"[^\"$\\\\]*\"").matches(value)
                            }

                            else -> {
                                false
                            }
                        }
                    if (!valid) reject("Unsupported constant literal: $value")
                    constants += identifier(property.name) to "$t = $value"
                }
            } else {
                val function =
                    member as? KtNamedFunction
                        ?: reject("Contracts cannot contain executable bodies, properties, initializers or nested declarations")
                modifiers(function)
                if (function.hasBody() || function.receiverTypeReference != null || function.typeParameters.isNotEmpty() ||
                    function.contextReceivers.isNotEmpty()
                ) {
                    reject("Contract methods must be ordinary declarations without bodies, receivers or generics")
                }
                val method = identifier(function.name)
                val annotations = annotations(function, operationAnnotations.keys)
                if (annotations.size != 1) reject("$name.$method requires exactly one operation annotation")
                val annotation = annotations.values.single()
                val kind = operationAnnotations.getValue(annotations.keys.single())
                val bindingName =
                    arguments(annotation, listOf("binding"))["binding"]?.let(::string)?.ifEmpty { null }
                        ?: "${name.replaceFirstChar(Char::lowercase)}${method.replaceFirstChar(Char::uppercase)}"
                identifier(bindingName)
                val parameters =
                    function.valueParameters.map { parameter ->
                        modifiers(parameter, emptySet())
                        if (parameter.hasDefaultValue() ||
                            parameter.hasValOrVar()
                        ) {
                            reject("Operation parameters cannot have defaults or properties")
                        }
                        val t = resolve(parameter.typeReference)
                        GuestContractParameter(identifier(parameter.name), t, constraints(parameter, t))
                    }
                if (parameters.size + (if (handle) 1 else 0) > 32) reject("Operations are limited to 32 wire arguments")
                if (handle && parameters.any { it.name == "handle" }) reject("Device/resource handle is SDK-owned")
                operations +=
                    GuestContractOperation(
                        method,
                        resolve(function.typeReference),
                        kind,
                        "$pkg.$owner.$bindingName",
                        handle,
                        parameters,
                        function.docComment?.text.orEmpty(),
                    )
            }
        }
        return GuestContractType(pkg, name, id, false, resource, emptyList(), operations, constants, type.docComment?.text.orEmpty())
    }
}

/** The source files returned here are exactly the Guest compiler input, not a pretty-printed side artifact. */
fun generateGuestContractSources(contract: DeclarativeGuestContract): Map<String, String> {
    if (contract.nativeSources.isNotEmpty()) return generateNativeGuestSources(contract)
    val sources = sortedMapOf<String, String>()
    val boundary = GuestBoundaryTypes(contract)
    val bindings = linkedMapOf<String, MutableList<GuestContractOperation>>()
    contract.types.forEach { type ->
        val text =
            buildString {
                appendLine("/* Generated by Compukters from a declarative contract. Do not edit. */")
                appendLine("package ${type.packageName}")
                val signatureTypes =
                    type.fields.map { it.type } +
                        type.operations.flatMap { operation ->
                            operation.parameters.map { it.type } + operation.result +
                                if (operation.kind == GuestOperationKind.COMMAND) listOf("compukter.commands.Command") else emptyList()
                        }
                appendGuestImports(signatureTypes)
                if (type.documentation.isNotEmpty()) appendLine(type.documentation)
                if (type.record) {
                    appendLine("public value class ${type.name}(")
                    type.fields.forEach { appendLine("    public val ${it.name}: ${it.type.substringAfterLast('.')},") }
                    appendLine(")")
                } else {
                    if (type.resource) {
                        appendLine("public value class ${type.name} internal constructor(internal val handle: Int) {")
                    } else if (type.deviceId != null) {
                        appendLine(
                            "public value class ${type.name} internal constructor(private val handle: Int) : compukter.peripheral.Peripheral {",
                        )
                        appendLine(
                            "    public companion object : compukter.peripheral.TypedPeripheralProvider<${type.name}>(\"${type.deviceId}\") {",
                        )
                        appendLine("        override fun wrap(handle: Int): ${type.name} = ${type.name}(handle)")
                        appendLine("    }")
                    } else {
                        appendLine("public object ${type.name} {")
                    }
                    type.constants.forEach { (name, value) -> appendLine("    public const val $name: $value") }
                    type.operations.forEach operation@{ operation ->
                        if (operation.documentation.isNotEmpty()) appendLine(operation.documentation)
                        val parameters = operation.parameters.joinToString { "${it.name}: ${it.type.substringAfterLast('.')}" }
                        val wireArguments = boundary.arguments(operation)
                        val arguments = wireArguments.map { it.expression }
                        if (operation.binding.substringBeforeLast('.') == "${type.packageName}.${type.name}") {
                            require(
                                !operation.device && operation.binding.substringAfterLast('.') == operation.name &&
                                    operation.kind != GuestOperationKind.COMMAND && boundary.wireType(
                                        operation.result,
                                    ) == operation.result &&
                                    operation.parameters.all { it.constraints.isEmpty() } &&
                                    wireArguments.map { it.name to it.type } == operation.parameters.map { it.name to it.type },
                            ) { "A service-owned legacy external binding must match its public signature without adapters or constraints" }
                            appendLine(
                                "    public external fun ${operation.name}($parameters): ${operation.result.substringAfterLast('.')}",
                            )
                            return@operation
                        }
                        appendLine("    public fun ${operation.name}($parameters): ${operation.result.substringAfterLast('.')} {")
                        operation.parameters.forEach { parameter ->
                            parameter.constraints.forEach { constraint ->
                                appendLine(
                                    "        if (!(${constraint.condition(
                                        parameter.name,
                                        parameter.type,
                                    )})) throw IllegalArgumentException(\"Invalid ${parameter.name}\")",
                                )
                            }
                        }
                        val call = "${operation.binding}(${arguments.joinToString()})"
                        if (boundary.wireType(operation.result) == operation.result) {
                            appendLine("        return $call")
                        } else {
                            appendLine("        val reply = $call")
                            appendLine("        return ${boundary.decode(operation.result, "reply")}")
                        }
                        appendLine("    }")
                        if (operation.kind == GuestOperationKind.COMMAND) {
                            appendLine("    public fun ${operation.name}Command($parameters): Command<Unit> {")
                            operation.parameters.forEach { parameter ->
                                parameter.constraints.forEach { constraint ->
                                    appendLine(
                                        "        if (!(${constraint.condition(
                                            parameter.name,
                                            parameter.type,
                                        )})) throw IllegalArgumentException(\"Invalid ${parameter.name}\")",
                                    )
                                }
                            }
                            val encoded =
                                wireArguments.map { parameter ->
                                    val method =
                                        when (parameter.type) {
                                            "ByteArray" -> "bytes"
                                            else -> parameter.type.replaceFirstChar(Char::lowercase)
                                        }
                                    "compukter.commands.CommandArgument.$method(${parameter.expression})"
                                }
                            appendLine(
                                "        return compukter.commands.Command.unit(\"${operation.binding}\", listOf(${encoded.joinToString()}))",
                            )
                            appendLine("    }")
                        }
                        bindings.getOrPut(operation.binding.substringBeforeLast('.')) { mutableListOf() } += operation
                    }
                    appendLine("}")
                }
            }
        sources[guestSourcePath("${type.packageName}.${type.name}")] = text
        if (type.record) {
            val publicType = "${type.packageName}.${type.name}"
            val wireType = boundary.wireType(publicType)
            if (wireType != publicType) {
                sources[guestSourcePath(wireType)] =
                    buildString {
                        appendLine("/* Generated by Compukters. Do not edit. */")
                        appendLine("package ${type.packageName}")
                        appendGuestImports(type.fields.map { boundary.wireType(it.type) })
                        appendLine("internal value class ${wireType.substringAfterLast('.')}(")
                        type.fields.forEach {
                            appendLine(
                                "    public val ${it.name}: ${boundary.wireType(it.type).substringAfterLast('.')},",
                            )
                        }
                        appendLine(")")
                    }
            }
        }
    }
    bindings.forEach { (owner, operations) ->
        val pkg = owner.substringBeforeLast('.')
        val name = owner.substringAfterLast('.')
        require(contract.types.none { "${it.packageName}.${it.name}" == owner }) { "Binding owner collides with a public declaration" }
        sources[guestSourcePath(owner)] =
            buildString {
                appendLine("/* Generated by Compukters. Do not edit. */")
                appendLine("package $pkg")
                appendGuestImports(operations.map { boundary.wireType(it.result) })
                appendLine("internal object $name {")
                operations.sortedBy { it.binding }.forEach { operation ->
                    val parameters = boundary.arguments(operation).map { "${it.name}: ${it.type}" }
                    appendLine(
                        "    external fun ${operation.binding.substringAfterLast(
                            '.',
                        )}(${parameters.joinToString()}): ${boundary.wireType(operation.result).substringAfterLast('.')}",
                    )
                }
                appendLine("}")
            }
    }
    return sources
}

// The compiler uses source basenames to attribute declarations across all installed addon libraries.
private fun guestSourcePath(qualifiedName: String): String = "${qualifiedName.substringBeforeLast('.').replace('.', '/')}/$qualifiedName.kt"

private fun StringBuilder.appendGuestImports(types: List<String>) {
    val imports = types.filter { '.' in it }.distinct().sorted()
    require(imports.distinctBy { it.substringAfterLast('.') }.size == imports.size) { "Ambiguous generated Guest signature types" }
    imports.forEach { appendLine("import $it") }
}

fun writeGeneratedGuestSources(
    contract: DeclarativeGuestContract,
    root: Path,
) {
    Files.createDirectories(root)
    val sources = generateGuestContractSources(contract)
    Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { path ->
            if (root.relativize(path).invariantSeparatorsPathString !in sources) Files.delete(path)
        }
    }
    sources.forEach { (relative, text) ->
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root.normalize()))
        Files.createDirectories(target.parent)
        if (!Files.exists(target) || Files.readString(target) != text) Files.writeString(target, text)
    }
}
