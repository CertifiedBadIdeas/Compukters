package ru.lazyhat.kraftui.foundation.modifier

import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.HoverState
import ru.lazyhat.kraftui.foundation.IntSize
import ru.lazyhat.kraftui.foundation.Value
import ru.lazyhat.kraftui.foundation.value

data class PaddingModifier(
    val padding: Padding,
) : Modifier.Element

data class Padding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    companion object {
        val Zero = Padding(0, 0, 0, 0)
    }
}

fun Modifier.padding(all: Int): Modifier = padding(all, all, all, all)

fun Modifier.padding(
    horizontal: Int,
    vertical: Int,
): Modifier = padding(horizontal, vertical, horizontal, vertical)

fun Modifier.padding(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Modifier {
    require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
    return then(PaddingModifier(Padding(left, top, right, bottom)))
}

fun Modifier.findPadding() = find<PaddingModifier>()

//
//

data class OffsetModifier(
    val position: Position,
) : Modifier.Element

data class Position(
    val x: Int,
    val y: Int,
) {
    companion object {
        val Zero = Position(0, 0)
    }
}

fun Modifier.offset(
    x: Int,
    y: Int,
) = then(OffsetModifier(Position(x, y)))

fun Modifier.findOffset() = find<OffsetModifier>()

//
//

data class SizeModifier(
    val size: IntSize,
) : Modifier.Element

fun Modifier.size(size: IntSize) = then(SizeModifier(size))

fun Modifier.size(
    width: Int,
    height: Int,
) = then(SizeModifier(IntSize(width, height)))

fun Modifier.findSize() = find<SizeModifier>()

//

sealed interface AxisSize {
    data class Fixed(
        val pixels: Int,
    ) : AxisSize

    data object Fill : AxisSize
}

data class WidthModifier(
    val width: AxisSize,
) : Modifier.Element

data class HeightModifier(
    val height: AxisSize,
) : Modifier.Element

fun Modifier.width(width: Int): Modifier {
    require(width >= 0)
    return then(WidthModifier(AxisSize.Fixed(width)))
}

fun Modifier.height(height: Int): Modifier {
    require(height >= 0)
    return then(HeightModifier(AxisSize.Fixed(height)))
}

fun Modifier.fillMaxWidth(): Modifier = then(WidthModifier(AxisSize.Fill))

fun Modifier.fillMaxHeight(): Modifier = then(HeightModifier(AxisSize.Fill))

fun Modifier.fillMaxSize(): Modifier = fillMaxWidth().fillMaxHeight()

fun Modifier.findWidth() = find<WidthModifier>()

fun Modifier.findHeight() = find<HeightModifier>()

//

enum class TextAlignment {
    Start,
    Center,
    End,
}

data class TextAlignmentModifier(
    val alignment: TextAlignment,
) : Modifier.Element

fun Modifier.textAlign(alignment: TextAlignment): Modifier = then(TextAlignmentModifier(alignment))

fun Modifier.findTextAlignment() = find<TextAlignmentModifier>()

//

data class BackgroundModifier(
    val color: Color,
) : Modifier.Element

fun Modifier.background(color: Color) = then(BackgroundModifier(color))

fun Modifier.findBackground() = find<BackgroundModifier>()

//
//

data class ClickableModifier(
    val onClick: () -> Unit,
) : Modifier.Element

fun Modifier.clickable(onClick: () -> Unit) = then(ClickableModifier(onClick))

fun Modifier.findClickable() = find<ClickableModifier>()

//
//

data class ZIndexModifier(
    val zIndex: Int,
) : Modifier.Element

fun Modifier.zIndex(zIndex: Int) = then(ZIndexModifier(zIndex))

fun Modifier.findZIndex() = find<ZIndexModifier>()

//
//

enum class UiAlignment {
    Start,
    Center,
    End,
    Stretch,
}

data class AlignmentModifier(
    val alignment: UiAlignment,
) : Modifier.Element

fun Modifier.align(alignment: UiAlignment) = then(AlignmentModifier(alignment))

fun Modifier.findAlignment() = find<AlignmentModifier>()

//
//

data class WeightModifier(
    val weight: Float,
) : Modifier.Element

fun Modifier.weight(weight: Float) = then(WeightModifier(weight))

fun Modifier.findWeight() = find<WeightModifier>()

//
//

data class HoverableModifier(
    val state: HoverState,
) : Modifier.Element

fun Modifier.hoverable(state: HoverState) = then(HoverableModifier(state))

fun Modifier.findHoverable() = find<HoverableModifier>()

//
//

data class TooltipModifier(
    val text: Value<String>,
) : Modifier.Element

fun Modifier.tooltip(text: String) = then(TooltipModifier(value { text }))

fun Modifier.tooltip(text: Value<String>) = then(TooltipModifier(text))

fun Modifier.findTooltip() = find<TooltipModifier>()

//
//

/**
 * Marks the host element as a keyboard-focus target.
 *
 *  - [id] is the stable identifier the runtime uses to track which node
 *    currently owns focus across recompiles.
 *  - [tabOrder] determines Tab/Shift+Tab navigation: nodes are visited in
 *    ascending [tabOrder]. Nodes with the same value are visited in
 *    compile-time order. Nodes with a negative [tabOrder] are skipped by
 *    Tab cycling but can still acquire focus via mouse click.
 *  - [onKeyPressed]/[onKeyReleased]/[onCharTyped] return `true` to consume
 *    the event. If [onKeyPressed] returns `false` and the key is Tab, the
 *    runtime advances focus to the next/previous focusable node before
 *    returning.
 */
data class FocusableModifier(
    val id: String,
    val tabOrder: Int,
    val onKeyPressed: (keyCode: Int) -> Boolean,
    val onKeyReleased: (keyCode: Int) -> Boolean,
    val onCharTyped: (ch: Char) -> Boolean,
) : Modifier.Element

fun Modifier.focusable(
    id: String,
    tabOrder: Int = 0,
    onKeyPressed: (Int) -> Boolean = { false },
    onKeyReleased: (Int) -> Boolean = { false },
    onCharTyped: (Char) -> Boolean = { false },
): Modifier =
    then(
        FocusableModifier(
            id = id,
            tabOrder = tabOrder,
            onKeyPressed = onKeyPressed,
            onKeyReleased = onKeyReleased,
            onCharTyped = onCharTyped,
        ),
    )

fun Modifier.findFocusable() = find<FocusableModifier>()

//
//

/**
 * Marks the host element as draggable. The lambdas receive the cursor
 * position in absolute screen coordinates.
 *
 * - [onDragStart] fires once on the initial mousedown that starts a drag.
 * - [onDrag] fires on every subsequent mousedragged event while the mouse
 *   button is held.
 * - [onDragEnd] fires on mouseUp.
 *
 * A clickable element is just sugar over a draggable element whose drag
 * delta stays inside the original bounds.
 */
data class DraggableModifier(
    val onDragStart: (x: Int, y: Int) -> Unit,
    val onDrag: (x: Int, y: Int) -> Unit,
    val onDragEnd: (x: Int, y: Int) -> Unit,
) : Modifier.Element

fun Modifier.draggable(
    onDragStart: (Int, Int) -> Unit = { _, _ -> },
    onDrag: (Int, Int) -> Unit = { _, _ -> },
    onDragEnd: (Int, Int) -> Unit = { _, _ -> },
): Modifier =
    then(
        DraggableModifier(
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        ),
    )

fun Modifier.findDraggable() = find<DraggableModifier>()
