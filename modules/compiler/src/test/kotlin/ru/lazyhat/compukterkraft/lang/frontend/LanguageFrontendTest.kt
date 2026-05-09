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

import ru.lazyhat.compukterkraft.lang.api.ClassDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassFieldDeclaration
import ru.lazyhat.compukterkraft.lang.api.ClassMethodDeclaration
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.StructDeclaration
import ru.lazyhat.compukterkraft.lang.api.TokenKind
import ru.lazyhat.compukterkraft.lang.api.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageFrontendTest {
    private val frontend = LanguageFrontend()

    @Test
    fun lexesDoubleColonAsScopeOperator() {
        val tokens = Lexer("fun main() { terminal::println(\"ok\"); }").lex()

        assertTrue(tokens.any { it.kind == TokenKind.COLON_COLON }, tokens.joinToString { "${it.kind}:${it.text}" })
    }

    @Test
    fun lexesBackspaceEscapeInStringLiteral() {
        val tokens = Lexer("pub fun main() { stdout::write(\"\\b\"); }").lex()

        assertEquals("\b", tokens.single { it.kind == TokenKind.STRING }.text)
    }

    @Test
    fun lexesCarriageReturnEscapeInStringLiteral() {
        val tokens = Lexer("pub fun main() { system::log(\"\\r\"); }").lex()

        assertEquals("\r", tokens.single { it.kind == TokenKind.STRING }.text)
    }

    @Test
    fun lexesClassKeywords() {
        val tokens = Lexer("class Counter(var value: Int) { init {} static fun zero(): Int { return 0; } }").lex()

        assertTrue(tokens.any { it.kind == TokenKind.CLASS }, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(tokens.any { it.kind == TokenKind.INIT }, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(tokens.any { it.kind == TokenKind.STATIC }, tokens.joinToString { "${it.kind}:${it.text}" })
    }

    @Test
    fun lexesPubKeyword() {
        val tokens = Lexer("pub fun main() {}").lex()

        assertTrue(tokens.any { it.kind == TokenKind.PUB }, tokens.joinToString { "${it.kind}:${it.text}" })
    }

    @Test
    fun lexesBitwiseTokensAndBinaryLiterals() {
        val tokens =
            Lexer(
                """
                pub fun main() {
                    var flags: Int = 0b00101;
                    flags &= 0B11111;
                    flags |= 0b01000L;
                    flags ^= 0b00001;
                    flags <<= 1;
                    flags >>= 2;
                    val inverted: Int = ~flags;
                    val shifted: Int = flags << 1 >> 1;
                    val masked: Int = flags & 0b11111 | 0b00010 ^ 0b00001;
                }
                """.trimIndent(),
            ).lex()

        val kinds = tokens.map { it.kind }
        assertTrue(TokenKind.AMP in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.PIPE in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.CARET in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.TILDE in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.LT_LT in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.GT_GT in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.AMP_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.PIPE_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.CARET_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.LT_LT_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(TokenKind.GT_GT_EQUAL in kinds, tokens.joinToString { "${it.kind}:${it.text}" })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0b00101" }, tokens.joinToString { it.text })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0B11111" }, tokens.joinToString { it.text })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text == "0b01000L" }, tokens.joinToString { it.text })
    }

    @Test
    fun rejectsMalformedBinaryLiterals() {
        val empty = DefaultParserFacade().parse("empty_binary.ck", "pub fun main() { val value: Int = 0b; }")
        assertTrue(
            empty.syntaxDiagnostics.any { it.message.contains("Binary literal requires at least one digit") },
            empty.syntaxDiagnostics.joinToString { it.message },
        )

        val badDigit = DefaultParserFacade().parse("bad_binary.ck", "pub fun main() { val value: Int = 0b102; }")
        assertTrue(
            badDigit.syntaxDiagnostics.any { it.message.contains("Binary literal can only contain 0 or 1") },
            badDigit.syntaxDiagnostics.joinToString { it.message },
        )

        val tooLarge =
            frontend.compile(
                "large_binary.ck",
                "pub fun main() { val value: Int = 0b10000000000000000000000000000000; }",
            )
        assertTrue(
            tooLarge.analysis.diagnostics.any {
                it.message.contains("exceeds Int range") && it.message.contains("append `L`")
            },
            tooLarge.analysis.diagnostics.joinToString { it.message },
        )
    }

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

        assertTrue(
            parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR },
            parsed.syntaxDiagnostics.joinToString { it.message },
        )
        val pair =
            parsed.program.declarations
                .filterIsInstance<StructDeclaration>()
                .single()
        assertEquals(listOf("A", "B"), pair.typeParameters.map { it.name })
        assertEquals(
            "A",
            pair.fields
                .single { it.name == "first" }
                .type.name,
        )

        val box =
            parsed.program.declarations
                .filterIsInstance<ClassDeclaration>()
                .single()
        assertEquals(listOf("T"), box.typeParameters.map { it.name })
        assertEquals(
            "T",
            box.constructorParameters
                .single()
                .type.name,
        )

        val identity =
            parsed.program.declarations
                .filterIsInstance<FunctionDeclaration>()
                .single { it.name == "identity" }
        assertEquals(listOf("T"), identity.typeParameters.map { it.name })
        assertEquals("T", identity.returnType?.name)
    }

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

        assertTrue(
            parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR },
            parsed.syntaxDiagnostics.joinToString { it.message },
        )
    }

    @Test
    fun parsesPubTopLevelDeclarationsAndClassMembers() {
        val parsed =
            DefaultParserFacade().parse(
                "visibility.ck",
                """
                pub struct Vec2 { x: Int, y: Int }
                pub class Counter(pub var value: Int) {
                    pub val label: String = "counter";
                    var cached: Int = 0;
                    pub fun current(): Int { return this.cached; }
                    pub static fun zero(): Counter { return Counter(value = 0); }
                }
                pub fun main() {}
                fun helper(): Int { return 1; }
                """.trimIndent(),
            )

        assertTrue(
            parsed.syntaxDiagnostics.none { it.severity == FrontendSeverity.ERROR },
            parsed.syntaxDiagnostics.joinToString { it.message },
        )
        val struct =
            parsed.program.declarations
                .filterIsInstance<StructDeclaration>()
                .single()
        val klass =
            parsed.program.declarations
                .filterIsInstance<ClassDeclaration>()
                .single()
        val functions = parsed.program.declarations.filterIsInstance<FunctionDeclaration>()
        assertEquals(Visibility.PUBLIC, struct.visibility)
        assertEquals(Visibility.PUBLIC, klass.visibility)
        assertEquals(Visibility.PUBLIC, functions.single { it.name == "main" }.visibility)
        assertEquals(Visibility.PRIVATE, functions.single { it.name == "helper" }.visibility)
        assertEquals(Visibility.PUBLIC, klass.constructorParameters.single { it.name == "value" }.visibility)
        assertEquals(
            Visibility.PUBLIC,
            klass.members
                .filterIsInstance<ClassFieldDeclaration>()
                .single { it.name == "label" }
                .visibility,
        )
        assertEquals(
            Visibility.PRIVATE,
            klass.members
                .filterIsInstance<ClassFieldDeclaration>()
                .single { it.name == "cached" }
                .visibility,
        )
        assertEquals(
            Visibility.PUBLIC,
            klass.members
                .filterIsInstance<ClassMethodDeclaration>()
                .single {
                    it.function.name == "current"
                }.visibility,
        )
        assertEquals(
            Visibility.PUBLIC,
            klass.members
                .filterIsInstance<ClassMethodDeclaration>()
                .single { it.function.name == "zero" }
                .visibility,
        )
    }

    @Test
    fun parsesBasicClassDeclaration() {
        val artifact =
            frontend.compile(
                "class_parse.ck",
                """
                class Counter(var value: Int) {
                    init { this.value = value; }
                    fun current(): Int { return this.value; }
                    static fun zero(): Counter { return Counter(value = 0); }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Expected a top-level declaration")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsClassDeclarationWithoutConstructorAndBodySyntax() {
        val missingConstructor =
            DefaultParserFacade().parse("missing_constructor.ck", "class Counter\npub fun main() {}")
        val missingBody = DefaultParserFacade().parse("missing_body.ck", "class Counter()\npub fun main() {}")

        assertTrue(
            missingConstructor.syntaxDiagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Expected `(` after class name")
            },
            missingConstructor.syntaxDiagnostics.joinToString { it.message },
        )
        assertTrue(
            missingBody.syntaxDiagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Expected `{` after class constructor")
            },
            missingBody.syntaxDiagnostics.joinToString { it.message },
        )
    }

    @Test
    fun parsesScopeCallToBuiltin() {
        val artifact =
            frontend.compile(
                "ok.ck",
                """
                pub fun main() { system::log("hi"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun parsesNumericGlyphDisplayBuiltin() {
        val artifact =
            frontend.compile(
                "glyph.ck",
                """
                pub fun main() {
                    display::blitMono5x7(1, 2, 3, 14, 17, 17, 31, 17, 17, 17, 2016, -1)
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun parsesPackedNumericGlyphDisplayBuiltin() {
        val artifact =
            frontend.compile(
                "packed_glyph.ck",
                """
                pub fun main() {
                    display::blitMono5x7Packed(1, 2, 3, 0b01110100011000111111100011000110001L, 2016, -1)
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun parsesTextRunDisplayBuiltin() {
        val artifact =
            frontend.compile(
                "text_run.ck",
                """
                pub fun main() {
                    display::blitMono5x7Text(1, 2, 3, "hello", 2016, -1)
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun compilesBitwiseOperatorsWithApprovedTypes() {
        val artifact =
            frontend.compile(
                "bitwise.ck",
                """
                pub fun main() {
                    val a: Int = 0b01100 & 0b01010;
                    val b: Int = a | 0b00001;
                    val c: Int = b ^ 0b00100;
                    val d: Int = ~c;
                    val e: Int = 1 << 4 - 1;
                    val f: Int = e >> 2;
                    val g: Long = 0b1000L | 0b0011;
                    val h: Long = g & 0b1111L;
                    val i: Long = h << 2;
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsBitwiseOperatorsForNonNumericOperands() {
        val artifact =
            frontend.compile(
                "bad_bitwise.ck",
                """
                pub fun main() {
                    val a: Bool = true & false;
                    val b: String = "a" | "b";
                    val c: Bool = ~true;
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Bitwise operators expect Int or Long operands") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Bitwise not expects Int or Long") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun terminalAndStdoutBuiltinsAreRemoved() {
        assertNull(LanguageBuiltins.defaultRuntimeRegistry.module("terminal"))
        assertNull(LanguageBuiltins.defaultRuntimeRegistry.module("stdout"))
    }

    @Test
    fun terminalAndStdoutCallsAreUnknownModules() {
        val terminal = frontend.compile("main.ck", "pub fun main() { terminal::println(\"hi\"); }")
        assertTrue(terminal.analysis.diagnostics.any { it.message.contains("terminal") })

        val stdout = frontend.compile("main.ck", "pub fun main() { stdout::write(\"hi\"); }")
        assertTrue(stdout.analysis.diagnostics.any { it.message.contains("stdout") })
    }

    @Test
    fun requiresPublicMainEntryPoint() {
        val artifact = frontend.compile("main.ck", "fun main() {}")

        assertTrue(
            artifact.analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR && it.message.contains("pub fun main") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun acceptsPublicMainEntryPoint() {
        val artifact = frontend.compile("main.ck", "pub fun main() {}")

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsDotForBuiltinModuleAccess() {
        val artifact =
            frontend.compile(
                "dot.ck",
                """
                pub fun main() { system.log("hi"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Use `::` for module access")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsLegacyImportDeclarationsHard() {
        val artifact =
            frontend.compile(
                "import.ck",
                """
                import legacy;
                pub fun main() { system::log("ok"); }
                """.trimIndent(),
            )
        val errors = artifact.analysis.diagnostics.filter { it.severity == FrontendSeverity.ERROR }

        assertTrue(
            errors.any { it.message.contains("Use `import legacy { name }`") },
            errors.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun ambientBuiltinsWorkWithoutImport() {
        val artifact =
            frontend.compile(
                "ambient.ck",
                """
                pub fun main() { system::log("ok"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsQualifiedTypesUntilUserImportsLand() {
        val artifact =
            frontend.compile(
                "qual.ck",
                """
                pub fun main() { val v: m::Foo = null; }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Qualified types are not yet supported")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesRecordsFunctionsAndBuiltins() {
        val artifact =
            frontend.compile(
                "test.ck",
                """
                struct Point {
                    x: Int,
                    y: Int
                }

                fun sum(point: Point): Int {
                    return point.x + point.y;
                }

                pub fun main() {
                    val point: Point = Point(x = 1, y = 2);
                    system::log("sum=" + sum(point));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
        assertTrue(artifact.analysis.symbols.any { it.name == "Point" })
        assertTrue(artifact.analysis.references.any { it.name == "log" })
    }

    @Test
    fun compilesStructCallStyleConstruction() {
        val artifact =
            frontend.compile(
                "struct_call.ck",
                """
                struct Point { x: Int, y: Int }
                pub fun main() {
                    val point: Point = Point(x = 1, y = 2);
                    system::log("x=" + point.x);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsOldRecordConstructionSyntax() {
        val artifact =
            frontend.compile(
                "old_record.ck",
                """
                struct Point { x: Int, y: Int }
                pub fun main() { val point: Point = Point { x: 1, y: 2 }; }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Old record construction syntax")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun compilesClassConstructorCall() {
        val artifact =
            frontend.compile(
                "class_ctor.ck",
                """
                class Counter(pub var value: Int) {}
                pub fun main() {
                    val counter: Counter = Counter(value = 3);
                    system::log("value=" + counter.value);
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
        assertTrue(artifact.analysis.symbols.any { it.name == "Counter" && it.detail.contains("class Counter") })
    }

    @Test
    fun reportsClassConstructorArgumentErrors() {
        val artifact =
            frontend.compile(
                "class_ctor_errors.ck",
                """
                class Counter(var value: Int) {}
                pub fun main() { val counter: Counter = Counter(missing = 3); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Unknown constructor parameter `missing`") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Missing constructor argument `value`") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun analyzesThisAndInitAssignments() {
        val artifact =
            frontend.compile(
                "this_init.ck",
                """
                class Counter(var value: Int) {
                    init { this.value = value + 1; }
                    fun current(): Int { return this.value; }
                }
                pub fun main() { val counter: Counter = Counter(value = 1); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsExternalAccessToPrivateClassFieldAndMethod() {
        val artifact =
            frontend.compile(
                "private_member.ck",
                """
                pub class Counter(var value: Int) {
                    fun hidden(): Int { return this.value; }
                    pub fun shown(): Int { return this.hidden(); }
                }
                pub fun main() {
                    val counter: Counter = Counter(value = 1);
                    system::log("v=" + counter.value);
                    system::log("h=" + counter.hidden());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Member `value` of class `Counter` is private") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Member `hidden` of class `Counter` is private") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun allowsClassOwnerToAccessPrivateMembers() {
        val artifact =
            frontend.compile(
                "owner_member.ck",
                """
                pub class Counter(var value: Int) {
                    fun hidden(): Int { return this.value; }
                    pub fun shown(): Int { return this.hidden(); }
                }
                pub fun main() {
                    val counter: Counter = Counter(value = 1);
                    system::log("v=" + counter.shown());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsExternalAccessToPrivateStaticMethod() {
        val artifact =
            frontend.compile(
                "private_static.ck",
                """
                pub class Counter() {
                    static fun hidden(): Counter { return Counter(); }
                    pub static fun shown(): Counter { return Counter.hidden(); }
                }
                pub fun main() {
                    val counter: Counter = Counter.hidden();
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Member `hidden` of class `Counter` is private") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsUnexpectedPubOnInitAndPlainConstructorParameter() {
        val artifact =
            frontend.compile(
                "invalid_pub.ck",
                """
                pub class Counter(pub value: Int) {
                    pub init {}
                }
                pub fun main() {}
                """.trimIndent(),
            )

        val unexpectedPubDiagnostics = artifact.analysis.diagnostics.filter { it.message.contains("Unexpected `pub` modifier") }
        assertTrue(
            unexpectedPubDiagnostics.size >= 2,
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsUninitializedClassBodyField() {
        val artifact =
            frontend.compile(
                "uninitialized_body_field.ck",
                """
                class Holder() {
                    val value: Int;

                    fun current(): Int { return this.value; }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Field `value` must be initialized") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun rejectsConditionallyInitializedClassBodyField() {
        val artifact =
            frontend.compile(
                "conditionally_initialized_body_field.ck",
                """
                class Holder(flag: Bool) {
                    val value: Int;

                    init {
                        if (flag) {
                            this.value = 1;
                        }
                    }

                    fun current(): Int { return this.value; }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Field `value` must be initialized") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun acceptsClassBodyFieldInitializedInAllIfBranches() {
        val artifact =
            frontend.compile(
                "definitely_initialized_body_field.ck",
                """
                class Holder(flag: Bool) {
                    val value: Int;

                    init {
                        if (flag) {
                            this.value = 1;
                        } else {
                            this.value = 2;
                        }
                    }

                    fun current(): Int { return this.value; }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsAssignmentToValField() {
        val artifact =
            frontend.compile(
                "val_field.ck",
                """
                class Holder(val value: Int) {
                    fun bad(): Unit { this.value = 2; }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Cannot assign to val field `value`") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun rejectsThisInStaticMethod() {
        val artifact =
            frontend.compile(
                "static_this.ck",
                """
                class Holder(var value: Int) {
                    static fun bad(): Int { return this.value; }
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Static method cannot access `this`") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsTypeMismatchDiagnostics() {
        val artifact =
            frontend.compile(
                "broken.ck",
                """
                pub fun main() {
                    val flag: Bool = 42;
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Expected Bool") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesElseIfChains() {
        val artifact =
            frontend.compile(
                "elseif.ck",
                """
                pub fun main() {
                    val x: Int = 2;
                    if (x == 1) {
                        system::log("one");
                    } else if (x == 2) {
                        system::log("two");
                    } else if (x == 3) {
                        system::log("three");
                    } else {
                        system::log("other");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun compilesWhenWithSubject() {
        val artifact =
            frontend.compile(
                "when_subject.ck",
                """
                pub fun main() {
                    val x: Int = 2;
                    when(x) {
                        1 -> {
                            system::log("one");
                        }
                        2, 3 -> {
                            system::log("two or three");
                        }
                        else -> {
                            system::log("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun compilesWhenWithoutSubject() {
        val artifact =
            frontend.compile(
                "when_no_subject.ck",
                """
                pub fun main() {
                    val x: Int = 5;
                    when {
                        x > 10 -> {
                            system::log("big");
                        }
                        x > 0 -> {
                            system::log("positive");
                        }
                        else -> {
                            system::log("non-positive");
                        }
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun reportsWhenBranchTypeMismatch() {
        val artifact =
            frontend.compile(
                "when_mismatch.ck",
                """
                pub fun main() {
                    val x: Int = 1;
                    when(x) {
                        "hello" -> {
                            val y: Int = 1;
                        }
                    }
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("When branch value type mismatch") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsWhenConditionMustBeBool() {
        val artifact =
            frontend.compile(
                "when_bool.ck",
                """
                pub fun main() {
                    when {
                        42 -> {
                            val y: Int = 1;
                        }
                    }
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Expected Bool") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsElseFollowedByNonIfStatement() {
        val cases =
            listOf(
                "else_while.ck" to """
                pub fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else while
                }
            """,
                "else_val.ck" to """
                pub fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else val
                }
            """,
                "else_return.ck" to """
                pub fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else return
                }
            """,
                "else_when.ck" to """
                pub fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else when
                }
            """,
            )

        for ((name, source) in cases) {
            val artifact = frontend.compile(name, source.trimIndent())
            assertTrue(
                artifact.analysis.diagnostics.any {
                    it.severity == FrontendSeverity.ERROR
                },
                "Expected parse error for $name but got: ${artifact.analysis.diagnostics.joinToString { it.message }}",
            )
        }
    }

    @Test
    fun reportsIfWithoutParentheses() {
        val artifact =
            frontend.compile(
                "if_no_parens.ck",
                """
                pub fun main() {
                    if true {
                        val x: Int = 1;
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Expected `(`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesAssignmentToVar() {
        val artifact =
            frontend.compile(
                "assign.ck",
                """
                pub fun main() {
                    var i: Int = 0;
                    while (i < 3) {
                        i = i + 1;
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsAssignmentToVal() {
        val artifact =
            frontend.compile(
                "assign_val.ck",
                """
                pub fun main() {
                    val i: Int = 0;
                    i = 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Cannot reassign") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsAssignmentToUnknownVariable() {
        val artifact =
            frontend.compile(
                "assign_unknown.ck",
                """
                pub fun main() {
                    nope = 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Unknown variable") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsAssignmentTypeMismatch() {
        val artifact =
            frontend.compile(
                "assign_type.ck",
                """
                pub fun main() {
                    var i: Int = 0;
                    i = "hello";
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Assignment type mismatch") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesCompoundAssignmentToVar() {
        val artifact =
            frontend.compile(
                "compound.ck",
                """
                pub fun main() {
                    var i: Int = 0;
                    i += 1;
                    i -= 2;
                    i *= 3;
                    i /= 4;
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsCompoundAssignmentToVal() {
        val artifact =
            frontend.compile(
                "compound_val.ck",
                """
                pub fun main() {
                    val i: Int = 0;
                    i += 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Cannot reassign") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsIntLiteralOutOfRangeWithLongSuggestion() {
        val artifact =
            frontend.compile(
                "overflow.ck",
                """
                pub fun main() {
                    val n: Int = 2342343243;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any {
                it.message.contains("exceeds Int range") && it.message.contains("2342343243L")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsLongLiteralOutOfRange() {
        val artifact =
            frontend.compile(
                "longoverflow.ck",
                """
                pub fun main() {
                    val n: Long = 99999999999999999999L;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Long literal") && it.message.contains("out of range") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }
}
