package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.expr
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.align
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.padding
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenProgramCompilerTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    @Test
    fun terminalSurfaceIsMarkedAsTheSoleFocusedElement() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = expr { "snapshot" },
                        modifier = Modifier.offset(12, 28).size(96, 48),
                        onKey = { true },
                    )
                },
            )

        assertTrue(
            program.frames[0].ops.any { it is RenderOp.DrawTerminalSurface },
        )
        assertEquals("root-0", program.focusedNodeId)
        assertNotNull(program.keyHandler)
        assertTrue(program.keyHandler!!.invoke(257))
    }

    @Test
    fun screensWithoutFocusableElementsHaveNullFocusAndNoKeyHandler() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button({}) { text(text = expr { "Click" }) }
                },
            )

        assertNull(program.focusedNodeId)
        assertNull(program.keyHandler)
    }

    @Test
    fun multipleFocusableElementsAreRejectedAtCompileTime() {
        val error =
            assertFailsWith<IllegalStateException> {
                ScreenProgramCompiler().compile(
                    ui {
                        terminalSurface(snapshot = expr { "a" }, onKey = { true })
                        terminalSurface(snapshot = expr { "b" }, onKey = { true })
                    },
                )
            }
        assertTrue(error.message!!.contains("multiple focusable elements"))
    }

    @Test
    fun ifNodeProducesSeparateFrameGuardedByVisibilityExpression() {
        var visible = false
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    If(expr { visible }) {
                        box(modifier = Modifier.size(10, 10).background(Color.Red))
                    }
                },
            )

        // Two frames: root + If frame.
        assertEquals(2, program.frames.size)
        val ifFrame = program.frames[1]
        assertNotNull(ifFrame.visible)
        assertEquals(false, ifFrame.visible!!.evaluate())
        visible = true
        assertEquals(true, ifFrame.visible!!.evaluate())
        // Frame carries the FillRect op for the inner box.
        assertTrue(ifFrame.ops.any { it is RenderOp.FillRect })
    }

    @Test
    fun buttonClickIsBakedIntoHitRegion() {
        var pressed = false
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button(onClick = { pressed = true }) { text(text = expr { "Power" }) }
                },
            )

        assertEquals(1, program.hitRegions.size)
        program.hitRegions
            .single()
            .onClick
            .invoke()
        assertTrue(pressed)
    }

    @Test
    fun alignedChildBoundsAreBakedIntoFillRectAndHitRegion() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.size(200, 120).padding(10)) {
                        button(
                            modifier =
                                Modifier
                                    .size(80, 20)
                                    .align(UiAlignment.Center)
                                    .background(Color.Red),
                            onClick = {},
                        ) { text(text = expr { "Centered" }) }
                    }
                },
            )

        val region = program.hitRegions.single()
        assertEquals("root-0-0", region.nodeId)
        assertEquals(60, region.x)
        assertEquals(50, region.y)
        assertEquals(80, region.width)
        assertEquals(20, region.height)

        val fill =
            program.frames[0]
                .ops
                .filterIsInstance<RenderOp.FillRect>()
                .single()
        assertEquals(60, fill.x)
        assertEquals(50, fill.y)
        assertEquals(80, fill.width)
        assertEquals(20, fill.height)
    }

    @Test
    fun centeredTextUsesMeasuredBoundsInCompiledOps() {
        val program =
            ScreenProgramCompiler(fontMetrics = fontMetrics).compile(
                ui(Modifier.size(100, 40)) {
                    text(
                        modifier = Modifier.align(UiAlignment.Center),
                        text = expr { "AB" },
                    )
                },
            )

        val text =
            program.frames[0]
                .ops
                .filterIsInstance<RenderOp.DrawText>()
                .single()
        assertEquals(44, text.x)
        assertEquals(15, text.y)
        assertEquals("AB", text.value.evaluate())
    }

    @Test
    fun overlayProducesSeparateFrameWithDynamicOrigin() {
        var anchorX = 0
        var anchorY = 0
        val program =
            ScreenProgramCompiler().compile(
                ui(Modifier.size(100, 100)) {
                    overlay(
                        modifier = Modifier.size(20, 20),
                        anchor =
                            expr {
                                ru.lazyhat.compukterkraft.core.ui.foundation.modifier
                                    .Position(anchorX, anchorY)
                            },
                    ) {
                        box(modifier = Modifier.size(20, 20).background(Color.Red))
                    }
                },
            )

        assertEquals(2, program.frames.size)
        val overlayFrame = program.frames[1]
        assertNotNull(overlayFrame.origin)
        assertEquals(0, overlayFrame.origin!!.evaluate().x)
        anchorX = 42
        anchorY = 17
        assertEquals(42, overlayFrame.origin!!.evaluate().x)
        assertEquals(17, overlayFrame.origin!!.evaluate().y)

        val fill =
            overlayFrame.ops
                .filterIsInstance<RenderOp.FillRect>()
                .single()
        // Overlay children use frame-local coordinates starting at (0, 0).
        assertEquals(0, fill.x)
        assertEquals(0, fill.y)
    }
}
