package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.HoverState
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.UiAlignment
import ru.lazyhat.kraftui.foundation.modifier.align
import ru.lazyhat.kraftui.foundation.modifier.background
import ru.lazyhat.kraftui.foundation.modifier.focusable
import ru.lazyhat.kraftui.foundation.modifier.hoverable
import ru.lazyhat.kraftui.foundation.modifier.offset
import ru.lazyhat.kraftui.foundation.modifier.padding
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.modifier.tooltip
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenProgramCompilerTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    private fun emptySnapshot(): Any = "terminal"

    @Test
    fun terminalSurfaceIsCollectedAsAFocusNode() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = value { emptySnapshot() },
                        modifier = Modifier.offset(12, 28).size(96, 48),
                        onKey = { true },
                    )
                },
            )

        assertTrue(
            program.frames[0].ops.any { it is RenderOp.DrawTerminalSurface },
        )
        val node = program.focusNodes.single()
        assertEquals("root-0", node.nodeId)
        assertEquals(12, node.x)
        assertEquals(28, node.y)
        assertEquals(96, node.width)
        assertEquals(48, node.height)
        assertTrue(node.handler.onKeyPressed(257, 0))
    }

    @Test
    fun screensWithoutFocusableElementsHaveEmptyFocusNodeList() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button({}) { text(text = value { "Click" }) }
                },
            )

        assertTrue(program.focusNodes.isEmpty())
    }

    @Test
    fun multipleFocusableElementsAreCollectedIntoFocusNodes() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(snapshot = value { emptySnapshot() }, onKey = { true })
                    box(modifier = Modifier.size(20, 20).focusable(id = "editor", tabOrder = 1))
                },
            )

        assertEquals(2, program.focusNodes.size)
        assertEquals(0, program.focusNodes[0].tabOrder)
        assertEquals("editor", program.focusNodes[1].nodeId)
        assertEquals(1, program.focusNodes[1].tabOrder)
    }

    @Test
    fun focusableModifierOnTerminalSurfaceTakesPrecedenceOverImplicitClaim() {
        // When TerminalSurface already carries an explicit Modifier.focusable,
        // the compiler must not also auto-claim a second FocusNode for it.
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    terminalSurface(
                        snapshot = value { emptySnapshot() },
                        modifier = Modifier.size(40, 40).focusable(id = "explicit"),
                        onKey = { true },
                    )
                },
            )

        assertEquals(1, program.focusNodes.size)
        assertEquals("explicit", program.focusNodes.single().nodeId)
    }

    @Test
    fun textOverloadsHideValueWrappersForStaticAndDynamicText() {
        var dynamic = "Dynamic"
        val program =
            ScreenProgramCompiler(fontMetrics).compile(
                ui {
                    text("Static")
                    text { dynamic }
                    text(color = { Color.Red }) { "Colored" }
                },
            )

        val textOps = program.frames[0].ops.filterIsInstance<RenderOp.DrawText>()

        assertEquals(listOf("Static", "Dynamic", "Colored"), textOps.map { it.value.value })
        assertEquals(Color.Red, textOps[2].color.value)
        dynamic = "Updated"
        assertEquals("Updated", textOps[1].value.value)
    }

    @Test
    fun keySurfaceLowersToFocusableCanvas() {
        var pressed = false
        var typed = false
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    keySurface(
                        modifier = Modifier.size(40, 12),
                        id = "keyboard",
                        onKeyPressed = {
                            pressed = true
                            true
                        },
                        onCharTyped = {
                            typed = true
                            true
                        },
                    )
                },
            )

        assertTrue(program.frames[0].ops.any { it is RenderOp.DrawCanvas })
        val node = program.focusNodes.single()
        assertEquals("keyboard", node.nodeId)
        assertEquals(40, node.width)
        assertEquals(12, node.height)
        assertTrue(node.handler.onKeyPressed(257, 0))
        assertTrue(node.handler.onCharTyped('x'))
        assertTrue(pressed)
        assertTrue(typed)
    }

    @Test
    fun ifNodeProducesSeparateFrameGuardedByVisibilityExpression() {
        var visible = false
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    If(value { visible }) {
                        box(modifier = Modifier.size(10, 10).background(Color.Red))
                    }
                },
            )

        // Two frames: root + If frame.
        assertEquals(2, program.frames.size)
        val ifFrame = program.frames[1]
        assertNotNull(ifFrame.visible)
        assertEquals(false, ifFrame.visible.value)
        visible = true
        assertEquals(true, ifFrame.visible.value)
        // Frame carries the FillRect op for the inner box.
        assertTrue(ifFrame.ops.any { it is RenderOp.FillRect })
    }

    @Test
    fun buttonClickIsBakedIntoHitRegion() {
        var pressed = false
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    button(onClick = { pressed = true }) { text(text = value { "Power" }) }
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
                        ) { text(text = value { "Centered" }) }
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
                        text = value { "AB" },
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
        assertEquals("AB", text.value.value)
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
                            value {
                                ru.lazyhat.kraftui.foundation.modifier
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
        assertEquals(0, overlayFrame.origin.value.x)
        anchorX = 42
        anchorY = 17
        assertEquals(42, overlayFrame.origin.value.x)
        assertEquals(17, overlayFrame.origin.value.y)

        val fill =
            overlayFrame.ops
                .filterIsInstance<RenderOp.FillRect>()
                .single()
        // Overlay children use frame-local coordinates starting at (0, 0).
        assertEquals(0, fill.x)
        assertEquals(0, fill.y)
    }

    @Test
    fun canvasLowersToDrawCanvasOpAndDrawsRelativeToItsOrigin() {
        val recordedCalls = mutableListOf<Triple<Int, Int, Color>>()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    canvas(modifier = Modifier.offset(30, 40).size(16, 16)) {
                        // Draw at canvas-local (2, 3) — should end up at absolute (32, 43).
                        fillRect(2, 3, 4, 5, Color.Red)
                    }
                },
            )

        val op =
            program.frames[0]
                .ops
                .filterIsInstance<RenderOp.DrawCanvas>()
                .single()
        assertEquals(30, op.x)
        assertEquals(40, op.y)
        assertEquals(16, op.width)
        assertEquals(16, op.height)

        val executor = ScreenRuntimeExecutor(program)
        val backend =
            object : RenderBackend {
                override fun fillRect(
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                    color: Color,
                ) {
                    recordedCalls += Triple(x, y, color)
                }

                override fun drawText(
                    x: Int,
                    y: Int,
                    text: String,
                    color: Color,
                ) {}

                override fun drawTerminalSurface(
                    x: Int,
                    y: Int,
                    snapshot: Any,
                ) {}

                override fun pushClip(
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ) {}

                override fun popClip() {}

                override fun drawCodeEditor(
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                    viewModel: ru.lazyhat.kraftui.editor.EditorViewModel,
                    fontWidth: Int,
                    fontHeight: Int,
                ) {}

                override fun measureText(text: String): Int = text.length * 6
            }
        executor.render(backend)
        assertEquals(Triple(32, 43, Color.Red), recordedCalls.single())
    }

    @Test
    fun hoverableModifierFlipsHoverStateBasedOnUpdateMouse() {
        val state = HoverState()
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.offset(10, 20).size(30, 40).hoverable(state))
                },
            )

        val region = program.hoverRegions.single()
        assertEquals(10, region.x)
        assertEquals(20, region.y)
        assertEquals(30, region.width)
        assertEquals(40, region.height)

        val executor = ScreenRuntimeExecutor(program)

        executor.updateMouse(mouseX = 5, mouseY = 5)
        assertTrue(!state.isHovered)

        executor.updateMouse(mouseX = 15, mouseY = 30)
        assertTrue(state.isHovered)

        executor.updateMouse(mouseX = 40, mouseY = 60)
        assertTrue(!state.isHovered)
    }

    @Test
    fun tooltipRegionExposesTextOnlyWhenMouseIsInside() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    box(modifier = Modifier.offset(100, 100).size(20, 20).tooltip("Power"))
                },
            )

        val executor = ScreenRuntimeExecutor(program)

        executor.updateMouse(mouseX = 0, mouseY = 0)
        assertNull(executor.activeTooltip)

        executor.updateMouse(mouseX = 110, mouseY = 110)
        assertEquals("Power", executor.activeTooltip)

        executor.updateMouse(mouseX = 200, mouseY = 200)
        assertNull(executor.activeTooltip)
    }

    @Test
    fun scrollAreaEmitsPushClipPopClipAndSubFrameWithDynamicOrigin() {
        val scroll = MutableStateInt(0)
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    scrollArea(
                        modifier = Modifier.offset(10, 20).size(50, 40),
                        scrollY = value { scroll.value },
                    ) {
                        box(modifier = Modifier.size(50, 200).background(Color.White))
                    }
                },
            )

        // Parent frame should have one PushClip and one PopClip wrapping the
        // sub-frame's draw ops.
        val parentOps = program.frames[0].ops
        val pushIndex = parentOps.indexOfFirst { it is RenderOp.PushClip }
        val popIndex = parentOps.indexOfFirst { it is RenderOp.PopClip }
        assertTrue(pushIndex >= 0)
        assertTrue(popIndex > pushIndex)
        val push = parentOps[pushIndex] as RenderOp.PushClip
        assertEquals(10, push.x)
        assertEquals(20, push.y)
        assertEquals(50, push.width)
        assertEquals(40, push.height)

        // Sub-frame origin should follow scroll: starts at (10, 20), then scrolls.
        val subFrame = program.frames[1]
        val origin0 = subFrame.origin?.value
        assertNotNull(origin0)
        assertEquals(10, origin0.x)
        assertEquals(20, origin0.y)

        scroll.value = 30
        val origin1 = subFrame.origin.value
        assertNotNull(origin1)
        assertEquals(10, origin1.x)
        assertEquals(-10, origin1.y)
    }

    @Test
    fun scrollAreaStampsClipOntoChildHitRegions() {
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    scrollArea(
                        modifier = Modifier.offset(10, 20).size(50, 40),
                    ) {
                        button(
                            modifier = Modifier.offset(0, 100).size(50, 20),
                            onClick = {},
                        ) {}
                    }
                },
            )

        assertEquals(1, program.hitRegions.size)
        val hit = program.hitRegions[0]
        val clip = hit.clip
        assertNotNull(clip)
        assertEquals(10, clip.x)
        assertEquals(20, clip.y)
        assertEquals(50, clip.width)
        assertEquals(40, clip.height)
    }

    @Test
    fun scrollAreaRegistersOnScrollHandler() {
        var totalDelta = 0.0
        val program =
            ScreenProgramCompiler().compile(
                ui {
                    scrollArea(
                        modifier = Modifier.offset(0, 0).size(100, 100),
                        onScroll = { delta ->
                            totalDelta += delta
                            true
                        },
                    ) {}
                },
            )

        assertEquals(1, program.scrollRegions.size)
        val region = program.scrollRegions[0]
        assertEquals(0, region.x)
        assertEquals(100, region.width)

        assertTrue(region.onScroll(2.5))
        assertEquals(2.5, totalDelta)
    }

    private class MutableStateInt(
        var value: Int,
    )

    private fun mutableStateOf(initial: Int): MutableStateInt = MutableStateInt(initial)
}
