package ru.lazyhat.compukterkraft.core.ui.foundation.modifier

import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.HoverState
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize

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
