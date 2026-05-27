# CKL Collections and Generics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement compile-time generics plus mutable `Array<T>`, `List<T>`, and `Map<K, V>` in CKL.

**Architecture:** Add generic syntax and structural type refs in the frontend, then add collection AST nodes, semantic rules, bytecode instructions, and VM-managed heap collection objects. Runtime generic type arguments are erased; the compiler enforces type safety and the VM stores ordinary `VmValue` elements.

**Tech Stack:** Kotlin, Gradle, kotlin.test, CKL compiler frontend in `modules/compiler`, bytecode VM in `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime`.

---

## Scope and sequencing

The spec is intentionally broad. Execute the tasks in order and commit after each task. Do not start collection runtime work before generic type syntax and type substitution tests pass.

Use this worktree:

```bash
cd /home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/ckl-collections-design
```

Primary verification command:

```bash
./gradlew :compiler:test
```

## File map

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`: add generic type parameters, generic type arguments, collection expression AST nodes, index assignment AST node, and collection bytecode instructions.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`: add `LBRACKET` and `RBRACKET`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`: register generic collection type constructors.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`: lexer, parser, semantic analyzer, bytecode compiler, formatter-facing AST handling, and collection method binding.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt` if formatter expression rendering is not fully delegated from `LanguageFrontend.kt`.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt` if completions or hovers need explicit collection method/type support outside semantic metadata.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`: heap representation, VM instructions, collection operations, memory accounting, and snapshots.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`: collection rendering and conversion helpers.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`: parser/lexer tests.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageGenericsSemanticTest.kt`: generic typechecking tests.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageCollectionsSemanticTest.kt`: collection typechecking tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`: runtime execution tests.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`: formatting tests for generics/literals/indexing.
- Modify `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`: completion/hover diagnostics tests.
- Modify `docs/LANGUAGE.md`: user-facing language documentation.

---

### Task 1: Generic syntax model and parser

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing parser tests**

Add these tests to `LanguageFrontendTest`:

```kotlin
@Test
fun parsesGenericTypeDeclarationsAndNestedTypeArguments() {
	val parsed =
		DefaultParserFacade().parse(
			"generics.ck",
			"""
			pub struct Pair<A, B> { first: A, second: B }
			pub class Box<T>(pub var value: T) {
				pub fun current(): T { return this.value; }
			}
			pub fun identity<T>(value: T): T { return value; }
			pub fun main() {
				val xs: List<Int> = null;
				val table: Map<String, List<Int>> = null;
			}
			""".trimIndent(),
		)

	assertTrue(parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR }, parsed.syntaxDiagnostics.joinToString { it.message })
	val pair = parsed.program.declarations.filterIsInstance<StructDeclaration>().single()
	assertEquals(listOf("A", "B"), pair.typeParameters.map { it.name })
	assertEquals("A", pair.fields.single { it.name == "first" }.type.name)

	val box = parsed.program.declarations.filterIsInstance<ClassDeclaration>().single()
	assertEquals(listOf("T"), box.typeParameters.map { it.name })
	assertEquals("T", box.constructorParameters.single().type.name)

	val identity = parsed.program.declarations.filterIsInstance<FunctionDeclaration>().single { it.name == "identity" }
	assertEquals(listOf("T"), identity.typeParameters.map { it.name })
	assertEquals("T", identity.returnType?.name)
}
```

- [ ] **Step 2: Run the parser test and verify it fails**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesGenericTypeDeclarationsAndNestedTypeArguments"
```

Expected: FAIL because declarations do not expose `typeParameters`, and `parseType()` rejects `List<Int>`.

- [ ] **Step 3: Extend API model for type parameters and type arguments**

In `LanguageModel.kt`, add this model and fields:

```kotlin
data class TypeParameterDeclaration(
	val name: String,
	val range: SourceRange,
)

data class StructDeclaration(
	override val name: String,
	val fields: List<RecordFieldDeclaration>,
	val visibility: Visibility = Visibility.PRIVATE,
	val typeParameters: List<TypeParameterDeclaration> = emptyList(),
	override val range: SourceRange,
) : TopLevelDeclaration

data class ClassDeclaration(
	override val name: String,
	val constructorParameters: List<ClassConstructorParameter>,
	val members: List<ClassMemberDeclaration>,
	val visibility: Visibility = Visibility.PRIVATE,
	val typeParameters: List<TypeParameterDeclaration> = emptyList(),
	override val range: SourceRange,
) : TopLevelDeclaration

data class TypeSyntax(
	val name: String,
	val nullable: Boolean = false,
	val range: SourceRange,
	val qualifier: String? = null,
	val arguments: List<TypeSyntax> = emptyList(),
) {
	val displayName: String
		get() {
			val qualifiedName = qualifier?.let { "$it::$name" } ?: name
			val args = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.displayName }
			return if (nullable) "$qualifiedName$args?" else "$qualifiedName$args"
		}
}
```

Update `FunctionDeclaration` in `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt` to add:

```kotlin
val typeParameters: List<TypeParameterDeclaration> = emptyList(),
```

- [ ] **Step 4: Add parser support for generic parameter lists and type arguments**

In `LanguageFrontend.kt`, add parser helpers near `parseType()`:

```kotlin
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
```

Call `parseTypeParameterList()` after struct/class/function names. Call `parseTypeArgumentList()` inside `parseType()` after the base type name and before nullable `?`.

- [ ] **Step 5: Run parser tests**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesGenericTypeDeclarationsAndNestedTypeArguments"
```

Expected: PASS.

- [ ] **Step 6: Run all compiler tests and commit**

Run:

```bash
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/FunctionDeclaration.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat: parse generic type syntax"
```

Expected: tests PASS and commit succeeds.

---

### Task 2: Structural generic type checking foundation

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageGenericsSemanticTest.kt`

- [ ] **Step 1: Write failing semantic tests**

Create `LanguageGenericsSemanticTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertTrue

class LanguageGenericsSemanticTest {
	private val frontend = LanguageFrontend()

	@Test
	fun acceptsGenericTypeParametersInScope() {
		val artifact =
			frontend.compile(
				"generic_scope.ck",
				"""
				pub struct Box<T> { value: T }
				pub fun identity<T>(value: T): T { return value; }
				pub fun main() {}
				""".trimIndent(),
			)

		assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	}

	@Test
	fun rejectsGenericCollectionTypeMismatch() {
		val artifact =
			frontend.compile(
				"generic_mismatch.ck",
				"""
				pub fun acceptStrings(xs: List<String>) {}
				pub fun passInts(xs: List<Int>) {
					acceptStrings(xs);
				}
				pub fun main() {}
				""".trimIndent(),
			)

		assertTrue(
			artifact.analysis.diagnostics.any { it.message.contains("Expected List<String>, got List<Int>") },
			artifact.analysis.diagnostics.joinToString { it.message },
		)
	}
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageGenericsSemanticTest"
```

Expected: FAIL because `List` is unknown and `TypeRef` does not preserve type arguments.

- [ ] **Step 3: Add generic built-in type constructors**

In `LanguageModel.kt`, extend `BuiltinType`:

```kotlin
data class BuiltinType(
	val name: String,
	val documentation: String,
	val fields: List<RecordFieldDefinition> = emptyList(),
	val typeParameterCount: Int = 0,
)
```

In `LanguageBuiltins.kt`, add built-in types:

```kotlin
BuiltinType("Array", "Mutable fixed-size indexed collection.", typeParameterCount = 1),
BuiltinType("List", "Mutable growable indexed collection.", typeParameterCount = 1),
BuiltinType("Map", "Mutable insertion-ordered key/value collection.", typeParameterCount = 2),
```

- [ ] **Step 4: Make `TypeRef` structural**

In `LanguageFrontend.kt`, replace `TypeRef` with:

```kotlin
internal data class TypeRef(
	val name: String,
	val nullable: Boolean = false,
	val arguments: List<TypeRef> = emptyList(),
	val typeParameter: Boolean = false,
) {
	val displayName: String
		get() {
			val args = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.displayName }
			return if (nullable) "$name$args?" else "$name$args"
		}
}
```

Update all `.copy(nullable = ...)`, `.displayName`, and `.name` usages to preserve arguments. Keep bytecode erasure by using `.name` only when writing runtime local/field type names.

- [ ] **Step 5: Resolve type parameters and validate arity**

Add a type-parameter stack in `SemanticAnalyzer`:

```kotlin
private val typeParameterScopes = ArrayDeque<Set<String>>()

private fun withTypeParameters(parameters: List<TypeParameterDeclaration>, body: () -> Unit) {
	typeParameterScopes.addLast(parameters.map { it.name }.toSet())
	body()
	typeParameterScopes.removeLast()
}

private fun isTypeParameter(name: String): Boolean = typeParameterScopes.asReversed().any { name in it }
```

In `resolveType()`, before looking in `typeNames`, resolve type parameters:

```kotlin
if (syntax.qualifier == null && isTypeParameter(syntax.name)) {
	return TypeRef(syntax.name, nullable = syntax.nullable, typeParameter = true)
}
```

Then resolve `syntax.arguments.map { resolveType(it, it.range) }`, validate `BuiltinType.typeParameterCount`, and return `TypeRef(type.name, syntax.nullable, resolvedArguments)`.

- [ ] **Step 6: Run semantic tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageGenericsSemanticTest"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageGenericsSemanticTest.kt
git commit -m "feat: add generic type checking foundation"
```

Expected: tests PASS and commit succeeds.

---

### Task 3: User-defined generic substitution

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageGenericsSemanticTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write failing substitution tests**

Add to `LanguageGenericsSemanticTest.kt`:

```kotlin
@Test
fun substitutesGenericFunctionStructAndClassTypes() {
	val artifact =
		frontend.compile(
			"generic_substitution.ck",
			"""
			pub struct Pair<A, B> { first: A, second: B }
			pub class Box<T>(pub var value: T) {
				pub fun current(): T { return this.value; }
			}
			pub fun identity<T>(value: T): T { return value; }
			pub fun main() {
				val answer: Int = identity(42);
				val pair: Pair<String, Int> = Pair(first = "x", second = answer);
				val box: Box<String> = Box(value = pair.first);
				val text: String = box.current();
			}
			""".trimIndent(),
		)

	assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
}
```

Add to `LanguageRuntimeTest`:

```kotlin
@Test
fun executesErasedGenericFunctionAndClass() {
	val artifact =
		frontend.compile(
			"generic_runtime.ck",
			"""
			pub class Box<T>(pub var value: T) {
				pub fun current(): T { return this.value; }
			}
			pub fun identity<T>(value: T): T { return value; }
			pub fun main() {
				val box: Box<String> = Box(value = identity("ok"));
				system::log(box.current());
			}
			""".trimIndent(),
		)

	assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	val runtime = RecordingRuntime()
	runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
	assertEquals(listOf("ok"), runtime.lines)
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageGenericsSemanticTest.substitutesGenericFunctionStructAndClassTypes" --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesErasedGenericFunctionAndClass"
```

Expected: FAIL because generic substitutions are not applied to calls, constructors, fields, or methods.

- [ ] **Step 3: Add substitution helpers**

In `LanguageFrontend.kt`, add helpers in `SemanticAnalyzer`:

```kotlin
private fun substitute(type: TypeRef, substitutions: Map<String, TypeRef>): TypeRef {
	if (type.typeParameter) return substitutions[type.name]?.copy(nullable = type.nullable) ?: type
	return type.copy(arguments = type.arguments.map { substitute(it, substitutions) })
}

private fun inferSubstitutions(parameters: List<TypeRef>, arguments: List<TypeRef>): Map<String, TypeRef>? {
	val result = mutableMapOf<String, TypeRef>()
	fun collect(expected: TypeRef, actual: TypeRef): Boolean {
		if (expected.typeParameter) {
			val previous = result[expected.name]
			if (previous != null && previous != actual) return false
			result[expected.name] = actual.copy(nullable = false)
			return true
		}
		if (expected.name != actual.name || expected.arguments.size != actual.arguments.size) return false
		return expected.arguments.zip(actual.arguments).all { (e, a) -> collect(e, a) }
	}
	return if (parameters.zip(arguments).all { (p, a) -> collect(p, a) }) result else null
}
```

- [ ] **Step 4: Apply substitutions in analyzer binding paths**

Update `analyzeCall()`, record constructors, class constructors, `analyzeMember()`, and `analyzeMethodCall()` so parameter and result types are substituted before `expectAssignable()` and before storing `expressionTypes[expression]`.

Use this rule for implicit generic function calls:

```kotlin
val substitutions = inferSubstitutions(binding.parameterTypes, argumentTypes)
if (substitutions == null) {
	diagnostics += FrontendDiagnostic("Cannot infer generic type arguments for `${binding.symbol.name}`.", expression.range)
	return TypeRef("Unit")
}
val returnType = substitute(binding.returnType, substitutions)
```

- [ ] **Step 5: Run substitution tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageGenericsSemanticTest" --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesErasedGenericFunctionAndClass"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageGenericsSemanticTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: substitute generic user types"
```

Expected: tests PASS and commit succeeds.

---

### Task 4: Collection literal and index AST/parser

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`

- [ ] **Step 1: Write failing parser tests**

Add to `LanguageFrontendTest`:

```kotlin
@Test
fun parsesCollectionLiteralsAndIndexing() {
	val parsed =
		DefaultParserFacade().parse(
			"collections_parse.ck",
			"""
			pub fun main() {
				val xs: List<Int> = [1, 2, 3];
				val table: Map<String, Int> = {"a": 1, "b": 2};
				val first: Int = xs[0];
				xs[1] = 42;
				table["c"] = first;
			}
			""".trimIndent(),
		)

	assertTrue(parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR }, parsed.syntaxDiagnostics.joinToString { it.message })
}
```

- [ ] **Step 2: Run parser test and verify it fails**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesCollectionLiteralsAndIndexing"
```

Expected: FAIL because `[` and `]` are unknown and collection AST nodes do not exist.

- [ ] **Step 3: Add tokens and AST nodes**

In `TokenKind.kt`, add:

```kotlin
LBRACKET,
RBRACKET,
```

In `LanguageModel.kt`, add:

```kotlin
data class ListLiteralExpression(
	val elements: List<Expression>,
	override val range: SourceRange,
) : Expression

data class MapEntryExpression(
	val key: Expression,
	val value: Expression,
	val range: SourceRange,
)

data class MapLiteralExpression(
	val entries: List<MapEntryExpression>,
	override val range: SourceRange,
) : Expression

data class IndexAccessExpression(
	val receiver: Expression,
	val index: Expression,
	override val range: SourceRange,
) : Expression

data class IndexAssignmentStatement(
	val receiver: Expression,
	val index: Expression,
	val expression: Expression,
	override val range: SourceRange,
) : Statement
```

- [ ] **Step 4: Implement parser support**

In lexer `when`, add `[` and `]` token handling. In `parseCall()`, after member access handling, add:

```kotlin
match(TokenKind.LBRACKET) -> {
	val index = parseExpression() ?: return null
	val end = consume(TokenKind.RBRACKET, "Expected `]` after index.") ?: return null
	IndexAccessExpression(expression, index, SourceRange(expression.range.start, end.range.end))
}
```

In `parsePrimary()`, add list literal parsing for `LBRACKET` and map literal parsing for `LBRACE` when the brace appears as a primary expression:

```kotlin
TokenKind.LBRACKET -> parseListLiteral(token)
TokenKind.LBRACE -> parseMapLiteral(token)
```

In `parseStatement()`, detect `IndexAccessExpression` followed by `=` after parsing a receiver/index chain. Implement `parseIndexAssignment()` so `xs[i] = value` returns `IndexAssignmentStatement`.

- [ ] **Step 5: Run parser tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest.parsesCollectionLiteralsAndIndexing"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/TokenKind.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt
git commit -m "feat: parse collection literals and indexing"
```

Expected: tests PASS and commit succeeds.

---

### Task 5: Collection semantic typing and method bindings

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageCollectionsSemanticTest.kt`

- [ ] **Step 1: Write failing collection semantic tests**

Create `LanguageCollectionsSemanticTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertTrue

class LanguageCollectionsSemanticTest {
	private val frontend = LanguageFrontend()

	@Test
	fun typechecksListMapArrayOperations() {
		val artifact =
			frontend.compile(
				"collections_semantic.ck",
				"""
				pub fun main() {
					val xs: List<Int> = [1, 2, 3];
					xs.add(4);
					xs[0] = xs.get(1);
					val maybe: Int? = xs.getOrNull(99);

					val fixed: Array<Int> = Array<Int>(size = 2, default = 0);
					fixed[1] = xs[0];

					val table: Map<String, Int> = {"a": 1};
					table["b"] = fixed[1];
					val present: Int? = table["a"];
					val fallback: Int = table.getOrDefault("missing", 7);
					val keys: List<String> = table.keys();
					val values: List<Int> = table.values();
				}
				""".trimIndent(),
			)

		assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	}

	@Test
	fun rejectsCollectionTypeMismatches() {
		val artifact =
			frontend.compile(
				"collections_bad.ck",
				"""
				pub fun main() {
					val xs: List<Int> = [1, 2];
					xs.add("bad");
					val table: Map<String, Int> = {"a": 1};
					table[1] = 2;
					val value: Int = table["a"];
				}
				""".trimIndent(),
			)

		val messages = artifact.analysis.diagnostics.joinToString { it.message }
		assertTrue(messages.contains("Expected Int, got String"), messages)
		assertTrue(messages.contains("Expected String, got Int"), messages)
		assertTrue(messages.contains("Expected Int, got Int?"), messages)
	}
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageCollectionsSemanticTest"
```

Expected: FAIL because collection literals, methods, and index types are not analyzed.

- [ ] **Step 3: Add collection type helpers**

In `SemanticAnalyzer`, add helpers:

```kotlin
private fun collectionType(name: String, vararg arguments: TypeRef): TypeRef = TypeRef(name, arguments = arguments.toList())
private fun TypeRef.elementType(): TypeRef? = if (name in setOf("Array", "List") && arguments.size == 1) arguments[0] else null
private fun TypeRef.mapKeyType(): TypeRef? = if (name == "Map" && arguments.size == 2) arguments[0] else null
private fun TypeRef.mapValueType(): TypeRef? = if (name == "Map" && arguments.size == 2) arguments[1] else null
```

- [ ] **Step 4: Analyze collection literals and index operations**

Add `analyzeListLiteral()`, `analyzeMapLiteral()`, `analyzeIndexAccess()`, and `analyzeIndexAssignment()`.

Use these result rules:

```kotlin
// List literal without expected type:
TypeRef("List", arguments = listOf(commonElementType))

// Array/List index read:
receiver.elementType() ?: TypeRef("Unit")

// Map index read:
receiver.mapValueType()?.copy(nullable = true) ?: TypeRef("Unit")
```

Thread expected types into variable declarations and assignments so `[]` and `{}` can infer from `val xs: List<Int> = []` and `val m: Map<String, Int> = {}`.

- [ ] **Step 5: Analyze collection methods**

Extend method-call analysis for built-in collection receiver types before class method lookup. Add method signatures exactly as in the spec:

```kotlin
Array<T>.size(): Int
Array<T>.get(Int): T
Array<T>.set(Int, T): Unit
Array<T>.getOrNull(Int): T?
List<T>.size(): Int
List<T>.isEmpty(): Bool
List<T>.get(Int): T
List<T>.set(Int, T): Unit
List<T>.getOrNull(Int): T?
List<T>.add(T): Unit
List<T>.insert(Int, T): Unit
List<T>.removeAt(Int): T
List<T>.clear(): Unit
Map<K, V>.size(): Int
Map<K, V>.isEmpty(): Bool
Map<K, V>.containsKey(K): Bool
Map<K, V>.get(K): V?
Map<K, V>.getOrDefault(K, V): V
Map<K, V>.set(K, V): Unit
Map<K, V>.remove(K): V?
Map<K, V>.clear(): Unit
Map<K, V>.keys(): List<K>
Map<K, V>.values(): List<V>
```

- [ ] **Step 6: Run semantic tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageCollectionsSemanticTest"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageCollectionsSemanticTest.kt
git commit -m "feat: typecheck ckl collections"
```

Expected: tests PASS and commit succeeds.

---

### Task 6: Collection bytecode and VM heap storage

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write failing runtime tests**

Add to `LanguageRuntimeTest`:

```kotlin
@Test
fun executesListArrayAndMapCollections() {
	val artifact =
		frontend.compile(
			"collections_runtime.ck",
			"""
			pub struct Key { name: String }

			pub fun main() {
				val xs: List<Int> = [1, 2];
				xs.add(3);
				xs[1] = 7;
				system::log("list=" + xs.size() + ":" + xs[0] + ":" + xs[1] + ":" + xs.removeAt(2));

				val fixed: Array<Int> = Array<Int>(size = 2, default = 5);
				fixed[1] = 9;
				system::log("array=" + fixed.size() + ":" + fixed[0] + ":" + fixed[1]);

				val table: Map<Key, String> = {};
				val key: Key = Key(name = "a");
				table[key] = "ok";
				system::log("map=" + table.size() + ":" + table[key] + ":" + table.containsKey(Key(name = "a")));
			}
			""".trimIndent(),
		)

	assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	val runtime = RecordingRuntime()
	runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
	assertEquals(listOf("list=3:1:7:3", "array=2:5:9", "map=1:ok:true"), runtime.lines)
}
```

- [ ] **Step 2: Run runtime test and verify it fails**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesListArrayAndMapCollections"
```

Expected: FAIL because collection bytecode and runtime storage are missing.

- [ ] **Step 3: Add collection instructions**

In `Instruction`, add:

```kotlin
data class ConstructArray(val sizeArgumentCount: Int = 2) : Instruction
data class ConstructList(val elementCount: Int) : Instruction
data class ConstructMap(val entryCount: Int) : Instruction
data object IndexGet : Instruction
data object IndexSet : Instruction
data class CallCollectionMethod(val methodName: String, val argumentCount: Int) : Instruction
```

Use `ConstructArray` for `Array<T>(size = ..., default = ...)`; the two arguments are `size` and `default`.

- [ ] **Step 4: Emit collection instructions**

In `FunctionCompiler.compileExpression()`:

```kotlin
is ListLiteralExpression -> {
	expression.elements.forEach(::compileExpression)
	instructions += Instruction.ConstructList(expression.elements.size)
}
is MapLiteralExpression -> {
	expression.entries.forEach { entry ->
		compileExpression(entry.key)
		compileExpression(entry.value)
	}
	instructions += Instruction.ConstructMap(expression.entries.size)
}
is IndexAccessExpression -> {
	compileExpression(expression.receiver)
	compileExpression(expression.index)
	instructions += Instruction.IndexGet
}
```

In `compileStatement()` for `IndexAssignmentStatement`:

```kotlin
compileExpression(statement.receiver)
compileExpression(statement.index)
compileExpression(statement.expression)
instructions += Instruction.IndexSet
instructions += Instruction.Pop
```

For collection method bindings, emit `Instruction.CallCollectionMethod(method.name, expression.arguments.size)` after compiling receiver and arguments.

- [ ] **Step 5: Add VM heap collection state**

In `LanguageRuntime.kt`, replace the class-only heap state with a sealed heap object:

```kotlin
private sealed interface VmHeapObject

private data class VmClassObject(
	val className: String,
	val fields: MutableMap<String, VmValue>,
) : VmHeapObject

private data class VmArrayObject(
	val elements: MutableList<VmValue>,
) : VmHeapObject

private data class VmListObject(
	val elements: MutableList<VmValue>,
) : VmHeapObject

private data class VmMapObject(
	val entries: LinkedHashMap<VmMapKey, VmValue>,
) : VmHeapObject
```

Keep `VmValue.ObjectRef(id)` as the shared heap reference representation. Add `VmMapKey` that preserves structural equality for primitive/string/record keys and identity for class/collection refs:

```kotlin
private data class VmMapKey(val value: VmValue)
```

Implement custom key normalization so `VmValue.RecordValue` uses structural equality and `VmValue.ObjectRef` uses id equality.

- [ ] **Step 6: Implement VM instruction handlers**

In `runUntilSignal()`, add handlers:

```kotlin
is Instruction.ConstructList -> {
	val values = frame.popMany(instruction.elementCount)
	frame.stack += allocate(VmListObject(values.toMutableList()))
}
is Instruction.ConstructMap -> {
	val values = frame.popMany(instruction.entryCount * 2)
	val entries = LinkedHashMap<VmMapKey, VmValue>()
	values.chunked(2).forEach { (key, value) -> entries[mapKey(key)] = value }
	frame.stack += allocate(VmMapObject(entries))
}
Instruction.IndexGet -> applyIndexGet(frame)
Instruction.IndexSet -> applyIndexSet(frame)
is Instruction.CallCollectionMethod -> applyCollectionMethod(frame, instruction.methodName, instruction.argumentCount)
```

Implement `allocate()`, `applyIndexGet()`, `applyIndexSet()`, and `applyCollectionMethod()` in the VM. Bounds errors should use `error("Index $index out of bounds for size $size.")`.

- [ ] **Step 7: Update rendering and memory accounting**

In `VmValueSupport.kt`, render heap refs as `object#id` as today. In `estimatedMemoryBytes()`, count heap object contents in `ensureWithinMemoryLimit()` by walking `heap.values` in addition to frames.

- [ ] **Step 8: Run runtime tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesListArrayAndMapCollections"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/api/LanguageModel.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/VmValueSupport.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: execute native ckl collections"
```

Expected: tests PASS and commit succeeds.

---

### Task 7: Deterministic Map behavior and runtime errors

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`

- [ ] **Step 1: Write failing determinism and error tests**

Add to `LanguageRuntimeTest`:

```kotlin
@Test
fun preservesMapInsertionOrderForKeysAndValues() {
	val artifact = frontend.compile(
		"map_order.ck",
		"""
		pub fun main() {
			val map: Map<String, Int> = {"a": 1, "b": 2};
			map["a"] = 3;
			map.remove("b");
			map["b"] = 4;
			val keys: List<String> = map.keys();
			val values: List<Int> = map.values();
			system::log(keys[0] + keys[1] + ":" + values[0] + ":" + values[1]);
		}
		""".trimIndent(),
	)
	assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	val runtime = RecordingRuntime()
	runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime) }
	assertEquals(listOf("ab:3:4"), runtime.lines)
}

@Test
fun crashesOnOutOfBoundsListIndex() {
	val artifact = frontend.compile(
		"list_oob.ck",
		"""
		pub fun main() {
			val xs: List<Int> = [1];
			system::log(xs[2]);
		}
		""".trimIndent(),
	)
	assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
	val failure = assertFailsWith<IllegalStateException> {
		runBlocking { BytecodeComputerProgram(requireNotNull(artifact.module)).run(RecordingRuntime()) }
	}
	assertTrue(failure.message.orEmpty().contains("Index 2 out of bounds"), failure.message.orEmpty())
}
```

- [ ] **Step 2: Run tests and verify failures**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.preservesMapInsertionOrderForKeysAndValues" --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.crashesOnOutOfBoundsListIndex"
```

Expected: FAIL if ordering or error class/message is not implemented.

- [ ] **Step 3: Fix Map ordering and error reporting**

Ensure `VmMapObject.entries` is a `LinkedHashMap`. Replacing an existing key must not remove and reinsert it. `remove()` must delete the key, and a later `set()` must insert at the end.

Use this bounds helper:

```kotlin
private fun checkedIndex(index: Int, size: Int): Int {
	check(index >= 0 && index < size) { "Index $index out of bounds for size $size." }
	return index
}
```

- [ ] **Step 4: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.preservesMapInsertionOrderForKeysAndValues" --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.crashesOnOutOfBoundsListIndex"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "test: cover deterministic collection behavior"
```

Expected: tests PASS and commit succeeds.

---

### Task 8: Formatter, IDE, and docs

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Modify: `docs/LANGUAGE.md`

- [ ] **Step 1: Write failing formatter and IDE tests**

Add to `LanguageFormatterTest`:

```kotlin
@Test
fun formatsGenericCollectionsAndIndexing() {
	val source = "pub fun main(){val xs:List<Int>=[1,2];xs[0]=3;val map:Map<String,Int>={\"a\":1};}"
	val expected =
		"""
		pub fun main() {
			val xs: List<Int> = [1, 2]
			xs[0] = 3
			val map: Map<String, Int> = {"a": 1}
		}
		""".trimIndent() + "\n"

	val formatted = applySingleEdit(source, formatter.formatDocument("collections.ck", source))

	assertEquals(expected, formatted)
}
```

Add to `LanguageIdeTest`:

```kotlin
@Test
fun completesCollectionMethodsAndShowsGenericHover() {
	val source =
		"""
		pub fun main() {
			val xs: List<Int> = [1];
			xs.
		}
		""".trimIndent()
	val cursor = lineAndColumnOf(source, "xs.") + 3

	val items = ide.complete("collections.ck", source, cursor.first, cursor.second)

	assertTrue(items.any { it.label == "add" }, items.joinToString { it.label })
	assertTrue(items.any { it.label == "getOrNull" }, items.joinToString { it.label })

	val hoverPosition = lineAndColumnOf(source, "List")
	val hover = ide.hover("collections.ck", source, hoverPosition.first, hoverPosition.second)
	assertNotNull(hover)
	assertTrue(hover.contents.contains("List<Int>"), hover.contents)
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatsGenericCollectionsAndIndexing" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.completesCollectionMethodsAndShowsGenericHover"
```

Expected: FAIL until formatter and IDE metadata understand new AST/type forms.

- [ ] **Step 3: Update formatter and IDE**

Formatter output rules:

```text
List<Int>
Map<String, Int>
[1, 2]
{"a": 1}
xs[0]
xs[0] = 3
```

IDE rules:

- Type hover uses `TypeRef.displayName`.
- Collection method completions come from the same collection method table used by semantic analysis.
- Diagnostics must include full generic display names.

- [ ] **Step 4: Update docs**

In `docs/LANGUAGE.md`, add sections for:

```markdown
## Generics
## Collections
### Array<T>
### List<T>
### Map<K, V>
### Collection literals and indexing
### Map key equality and ordering
```

Include examples for `Array<Int>(size = 2, default = 0)`, `[1, 2]`, `{"a": 1}`, `xs[0]`, and `map["a"] = 2`.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageFormatterTest.formatsGenericCollectionsAndIndexing" --tests "ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest.completesCollectionMethodsAndShowsGenericHover"
./gradlew :compiler:test
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatter.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIde.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFormatterTest.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt docs/LANGUAGE.md
git commit -m "docs: document ckl generics and collections"
```

Expected: tests PASS and commit succeeds.

---

### Task 9: Final verification and integration audit

**Files:**
- Verify only unless failures require fixes.

- [ ] **Step 1: Run full compiler test suite**

Run:

```bash
./gradlew :compiler:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run ROM compile tests because ROM scripts use parser edge cases**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.RomScriptCompileTest"
```

Expected: BUILD SUCCESSFUL. If this fails with parser diagnostics around `while` or `{}`, fix parser ambiguity before proceeding.

- [ ] **Step 3: Inspect git status and recent commits**

Run:

```bash
git --no-pager status --short
git --no-pager log --oneline -10
```

Expected: clean status and one commit per task.

- [ ] **Step 4: Commit any final fixes**

If Step 1 or Step 2 required fixes, commit them:

```bash
git add modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin docs/LANGUAGE.md
git commit -m "fix: stabilize ckl collection integration"
```

Expected: final verification passes after the fix commit.

## Plan self-review notes

- Spec coverage: generic syntax, mutable collections, list/map literals, indexing, Map equality/ordering, runtime errors, IDE, docs, and tests are covered by Tasks 1-9.
- Placeholder scan: no unresolved placeholder markers or intentionally vague implementation steps remain.
- Type consistency: the plan consistently uses `TypeSyntax.arguments`, structural `TypeRef.arguments`, and erased runtime heap refs.
- Execution consistency: commands use the verified Gradle project path and `:compiler:test`; ROM verification is included because parser ambiguity can affect bundled CKL scripts.
