package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.UiModifier

class UiLayoutResolver(
    private val rootWidth: Int,
    private val rootHeight: Int,
    private val fontMetrics: FontMetrics? = null,
) {
    fun resolve(root: UiElement): Map<String, LayoutNode> {
        val resolved = linkedMapOf<String, LayoutNode>()
        resolveNode(root, "root", 0, 0, rootWidth, rootHeight, resolved)
        return resolved
    }

    private fun resolveNode(
        element: UiElement,
        nodeId: String,
        parentX: Int,
        parentY: Int,
        parentWidth: Int,
        parentHeight: Int,
        resolved: MutableMap<String, LayoutNode>,
        forcedWidth: Int? = null,
        forcedHeight: Int? = null,
    ) {
        val width = forcedWidth ?: explicitOrIntrinsicWidth(element, parentWidth)
        val height = forcedHeight ?: explicitOrIntrinsicHeight(element, parentHeight)
        val alignedX = alignX(parentX, parentWidth, width, element.modifier.alignment)
        val alignedY = alignY(parentY, parentHeight, height, element.modifier.alignment)
        val x = alignedX + element.modifier.x
        val y = alignedY + element.modifier.y

        resolved[nodeId] = LayoutNode(nodeId, x, y, width, height)

        when (element) {
            is UiElement.Box -> resolveBoxChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.Row -> resolveRowChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.Column -> resolveColumnChildren(element.children, nodeId, x, y, width, height, element.modifier, resolved)
            is UiElement.IfNode ->
                element.children.forEachIndexed { index, child ->
                    resolveNode(child, "$nodeId-$index", x, y, width, height, resolved)
                }
            is UiElement.Text, is UiElement.TerminalSurface -> Unit
        }
    }

    private fun resolveBoxChildren(
        children: List<UiElement>,
        nodeId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        modifier: UiModifier,
        resolved: MutableMap<String, LayoutNode>,
    ) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom

        children.forEachIndexed { index, child ->
            resolveNode(child, "$nodeId-$index", contentX, contentY, contentWidth, contentHeight, resolved)
        }
    }

    private fun resolveRowChildren(
        children: List<UiElement>,
        nodeId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        modifier: UiModifier,
        resolved: MutableMap<String, LayoutNode>,
    ) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom
        val fixedWidth = children.filter { it.modifier.weight == null }.sumOf { primaryWidthForRow(it) }
        val totalWeight = children.sumOf { (it.modifier.weight ?: 0f).toDouble() }.toFloat()
        val remainingWidth = (contentWidth - fixedWidth).coerceAtLeast(0)
        var cursorX = contentX
        var assignedWeightedWidth = 0
        val weightedChildren = children.count { it.modifier.weight != null }
        var weightedIndex = 0

        children.forEachIndexed { index, child ->
            val childWeight = child.modifier.weight
            val childWidth =
                if (childWeight != null && totalWeight > 0f) {
                    weightedIndex += 1
                    if (weightedIndex == weightedChildren) {
                        remainingWidth - assignedWeightedWidth
                    } else {
                        ((remainingWidth * (childWeight / totalWeight))).toInt().also {
                            assignedWeightedWidth += it
                        }
                    }
                } else {
                    primaryWidthForRow(child)
                }
            val childHeight = when (child.modifier.alignment) {
                UiAlignment.Stretch -> contentHeight
                else -> explicitOrIntrinsicHeight(child, contentHeight)
            }

            resolveNode(
                child,
                "$nodeId-$index",
                cursorX,
                contentY,
                childWidth,
                childHeight,
                resolved,
                forcedWidth = childWidth,
                forcedHeight = childHeight,
            )
            cursorX += childWidth
        }
    }

    private fun resolveColumnChildren(
        children: List<UiElement>,
        nodeId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        modifier: UiModifier,
        resolved: MutableMap<String, LayoutNode>,
    ) {
        val contentX = x + modifier.padding.left
        val contentY = y + modifier.padding.top
        val contentWidth = width - modifier.padding.left - modifier.padding.right
        val contentHeight = height - modifier.padding.top - modifier.padding.bottom
        val fixedHeight = children.filter { it.modifier.weight == null }.sumOf { primaryHeightForColumn(it) }
        val totalWeight = children.sumOf { (it.modifier.weight ?: 0f).toDouble() }.toFloat()
        val remainingHeight = (contentHeight - fixedHeight).coerceAtLeast(0)
        var cursorY = contentY
        var assignedWeightedHeight = 0
        val weightedChildren = children.count { it.modifier.weight != null }
        var weightedIndex = 0

        children.forEachIndexed { index, child ->
            val childWeight = child.modifier.weight
            val childHeight =
                if (childWeight != null && totalWeight > 0f) {
                    weightedIndex += 1
                    if (weightedIndex == weightedChildren) {
                        remainingHeight - assignedWeightedHeight
                    } else {
                        ((remainingHeight * (childWeight / totalWeight))).toInt().also {
                            assignedWeightedHeight += it
                        }
                    }
                } else {
                    primaryHeightForColumn(child)
                }
            val childWidth = when (child.modifier.alignment) {
                UiAlignment.Stretch -> contentWidth
                else -> explicitOrIntrinsicWidth(child, contentWidth)
            }

            resolveNode(
                child,
                "$nodeId-$index",
                contentX,
                cursorY,
                childWidth,
                childHeight,
                resolved,
                forcedWidth = childWidth,
                forcedHeight = childHeight,
            )
            cursorY += childHeight
        }
    }

    private fun alignX(
        parentX: Int,
        parentWidth: Int,
        width: Int,
        alignment: UiAlignment?,
    ): Int =
        when (alignment) {
            UiAlignment.Center -> parentX + (parentWidth - width) / 2
            UiAlignment.End -> parentX + parentWidth - width
            else -> parentX
        }

    private fun alignY(
        parentY: Int,
        parentHeight: Int,
        height: Int,
        alignment: UiAlignment?,
    ): Int =
        when (alignment) {
            UiAlignment.Center -> parentY + (parentHeight - height) / 2
            UiAlignment.End -> parentY + parentHeight - height
            else -> parentY
        }

    private fun explicitOrIntrinsicWidth(
        element: UiElement,
        fallbackWidth: Int,
    ): Int =
        element.modifier.width ?: when (element) {
            is UiElement.Text -> fontMetrics?.width(element.value.evaluate()) ?: fallbackWidth
            else -> fallbackWidth
        }

    private fun explicitOrIntrinsicHeight(
        element: UiElement,
        fallbackHeight: Int,
    ): Int =
        element.modifier.height ?: when (element) {
            is UiElement.Text -> DEFAULT_TEXT_HEIGHT
            else -> fallbackHeight
        }

    private fun primaryWidthForRow(element: UiElement): Int =
        element.modifier.width ?: when (element) {
            is UiElement.Text -> fontMetrics?.width(element.value.evaluate()) ?: 0
            else -> 0
        }

    private fun primaryHeightForColumn(element: UiElement): Int =
        element.modifier.height ?: when (element) {
            is UiElement.Text -> DEFAULT_TEXT_HEIGHT
            else -> 0
        }

    private companion object {
        const val DEFAULT_TEXT_HEIGHT = 9
    }
}