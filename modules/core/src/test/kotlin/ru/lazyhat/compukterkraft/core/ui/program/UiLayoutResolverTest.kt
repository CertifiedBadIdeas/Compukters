package ru.lazyhat.compukterkraft.core.ui.program

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.textExpr
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class UiLayoutResolverTest {
    private val fontMetrics = FontMetrics { text -> text.length * 6 }

    @Test
    fun boxCentersChildInsidePaddedBounds() {
        val root = ui {
            box(modifier = Modifier.size(200, 120).padding(10)) {
                text(
                    value = textExpr { "Centered" },
                    modifier = Modifier.size(80, 20).align(UiAlignment.Center),
                )
            }
        }

        val layout = UiLayoutResolver(rootWidth = 200, rootHeight = 120).resolve(root)

        assertEquals(LayoutNode("root-0-0", 60, 50, 80, 20), layout.getValue("root-0-0"))
    }

    @Test
    fun rowDistributesRemainingWidthAcrossWeightedChildren() {
        val root = ui {
            row(modifier = Modifier.size(120, 20)) {
                text(value = textExpr { "A" }, modifier = Modifier.size(20, 20))
                text(value = textExpr { "B" }, modifier = Modifier.weight(1f).size(0, 20))
                text(value = textExpr { "C" }, modifier = Modifier.weight(2f).size(0, 20))
            }
        }

        val layout = UiLayoutResolver(rootWidth = 120, rootHeight = 20).resolve(root)

        assertEquals(LayoutNode("root-0-1", 20, 0, 33, 20), layout.getValue("root-0-1"))
        assertEquals(LayoutNode("root-0-2", 53, 0, 67, 20), layout.getValue("root-0-2"))
    }

    @Test
    fun boxIgnoresWeightAndUsesAlignedPlacement() {
        val root = ui {
            box(modifier = Modifier.size(100, 100).padding(10)) {
                text(
                    value = textExpr { "Weighted" },
                    modifier = Modifier.size(20, 10).weight(1f).align(UiAlignment.End),
                )
            }
        }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 100).resolve(root)

        assertEquals(LayoutNode("root-0-0", 70, 80, 20, 10), layout.getValue("root-0-0"))
    }

    @Test
    fun centeredTextUsesMeasuredWidthAndDefaultHeight() {
        val root = ui {
            box(modifier = Modifier.size(100, 40)) {
                text(
                    value = textExpr { "AB" },
                    modifier = Modifier.align(UiAlignment.Center),
                )
            }
        }

        val layout = UiLayoutResolver(rootWidth = 100, rootHeight = 40, fontMetrics = fontMetrics).resolve(root)

        assertEquals(LayoutNode("root-0-0", 44, 15, 12, 9), layout.getValue("root-0-0"))
    }
}