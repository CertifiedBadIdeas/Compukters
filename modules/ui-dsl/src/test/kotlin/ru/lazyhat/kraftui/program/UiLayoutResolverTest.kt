package ru.lazyhat.kraftui.program

import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.UiAlignment
import ru.lazyhat.kraftui.foundation.modifier.align
import ru.lazyhat.kraftui.foundation.modifier.fillMaxHeight
import ru.lazyhat.kraftui.foundation.modifier.fillMaxWidth
import ru.lazyhat.kraftui.foundation.modifier.height
import ru.lazyhat.kraftui.foundation.modifier.padding
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.modifier.weight
import ru.lazyhat.kraftui.foundation.modifier.width
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLayoutResolverTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    @Test
    fun boxCentersChildInsidePaddedBounds() {
        val root =
            ui {
                box(modifier = Modifier.size(200, 120).padding(10)) {
                    text(
                        text = value { "Centered" },
                        modifier = Modifier.size(80, 20).align(UiAlignment.Center),
                    )
                }
            }

        val layout = UiLayoutResolver(rootWidth = 200, rootHeight = 120).resolve(root)

        assertEquals(LayoutNode("root-0-0", 60, 50, 80, 20), layout.getValue("root-0-0"))
    }

    @Test
    fun rowDistributesRemainingWidthAcrossWeightedChildren() {
        val root =
            ui {
                row(modifier = Modifier.size(120, 20)) {
                    text(text = value { "A" }, modifier = Modifier.size(20, 20))
                    text(text = value { "B" }, modifier = Modifier.weight(1f).size(0, 20))
                    text(text = value { "C" }, modifier = Modifier.weight(2f).size(0, 20))
                }
            }

        val layout = UiLayoutResolver(rootWidth = 120, rootHeight = 20).resolve(root)

        assertEquals(LayoutNode("root-0-1", 20, 0, 33, 20), layout.getValue("root-0-1"))
        assertEquals(LayoutNode("root-0-2", 53, 0, 67, 20), layout.getValue("root-0-2"))
    }

    @Test
    fun boxIgnoresWeightAndUsesAlignedPlacement() {
        val root =
            ui {
                box(modifier = Modifier.size(100, 100).padding(10)) {
                    text(
                        text = value { "Weighted" },
                        modifier = Modifier.size(20, 10).weight(1f).align(UiAlignment.End),
                    )
                }
            }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 100).resolve(root)

        assertEquals(LayoutNode("root-0-0", 70, 80, 20, 10), layout.getValue("root-0-0"))
    }

    @Test
    fun centeredTextUsesMeasuredWidthAndDefaultHeight() {
        val root =
            ui {
                box(modifier = Modifier.size(100, 40)) {
                    text(
                        text = value { "AB" },
                        modifier = Modifier.align(UiAlignment.Center),
                    )
                }
            }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 40, fontMetrics = fontMetrics).resolve(root)

        assertEquals(LayoutNode("root-0-0", 44, 15, 12, 9), layout.getValue("root-0-0"))
    }

    @Test
    fun fillMaxWidthComposesWithFixedHeight() {
        val root =
            ui {
                column(modifier = Modifier.size(160, 80).padding(4)) {
                    box(modifier = Modifier.fillMaxWidth().height(14))
                }
            }

        val layout = UiLayoutResolver(rootWidth = 160, rootHeight = 80).resolve(root)

        assertEquals(LayoutNode("root-0-0", 4, 4, 152, 14), layout.getValue("root-0-0"))
    }

    @Test
    fun fixedWidthComposesWithFillMaxHeight() {
        val root =
            ui {
                row(modifier = Modifier.size(160, 80).padding(4)) {
                    box(modifier = Modifier.width(24).fillMaxHeight())
                }
            }

        val layout = UiLayoutResolver(rootWidth = 160, rootHeight = 80).resolve(root)

        assertEquals(LayoutNode("root-0-0", 4, 4, 24, 72), layout.getValue("root-0-0"))
    }

    @Test
    fun rowGapAddsSpacingBetweenFlowChildren() {
        val root =
            ui {
                row(modifier = Modifier.size(100, 20), gap = 4) {
                    box(modifier = Modifier.size(10, 20))
                    box(modifier = Modifier.size(20, 20))
                }
            }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 20).resolve(root)

        assertEquals(LayoutNode("root-0-0", 0, 0, 10, 20), layout.getValue("root-0-0"))
        assertEquals(LayoutNode("root-0-1", 14, 0, 20, 20), layout.getValue("root-0-1"))
    }

    @Test
    fun rowVerticalAlignmentCentersChildrenInCrossAxis() {
        val root =
            ui {
                row(modifier = Modifier.size(100, 30), verticalAlignment = UiAlignment.Center) {
                    box(modifier = Modifier.size(10, 10))
                }
            }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 30).resolve(root)

        assertEquals(LayoutNode("root-0-0", 0, 10, 10, 10), layout.getValue("root-0-0"))
    }

    @Test
    fun columnGapAddsSpacingAndHorizontalAlignmentMovesChildren() {
        val root =
            ui {
                column(modifier = Modifier.size(100, 60), gap = 3, horizontalAlignment = UiAlignment.End) {
                    box(modifier = Modifier.size(20, 10))
                    box(modifier = Modifier.size(30, 10))
                }
            }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 60).resolve(root)

        assertEquals(LayoutNode("root-0-0", 80, 0, 20, 10), layout.getValue("root-0-0"))
        assertEquals(LayoutNode("root-0-1", 70, 13, 30, 10), layout.getValue("root-0-1"))
    }
}
