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

@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package compukter.display

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

import kotlin.math.Position2i
import kotlin.math.Rect2i
import kotlin.math.Size2i
import kotlin.math.Vector2i

/** A fixed-size element. Mutable presentation properties never change its layout. */
public abstract class UiElement internal constructor(public val size: Size2i) {
    init {
        require(size.x >= 0 && size.y >= 0)
    }

    private var dirty: Rect2i? = Rect2i(Vector2i(0, 0), size)
    private var version: Long = 0
    private var drawingVersion: Long = 0

    /** Request repaint after changing state captured by custom drawing callbacks. Coordinates are local. */
    public fun invalidate() { invalidate(Rect2i(Vector2i(0, 0), size)) }

    public fun invalidate(bounds: Rect2i) {
        val clipped = bounds.intersection(Rect2i(Vector2i(0, 0), size))
        if (clipped.isEmpty) return
        dirty = uiUnion(dirty, clipped)
        version++
    }

    internal open fun damage(): Rect2i? = dirty
    internal open fun prepare() { drawingVersion = version }
    internal open fun painted() { if (version == drawingVersion) dirty = null }
    internal abstract fun draw(canvas: CanvasScope)

    internal open fun hit(point: Position2i, position: Position2i): UiHit? =
        if (point in Rect2i(position, size)) UiHit(this, position) else null

    internal open fun pointer(event: DisplayPointerEvent) {}
}

public class UiText internal constructor(size: Size2i, text: String) : UiElement(size) {
    public var text: String = text
        set(value) { val changed = field != value; field = value; if (changed) invalidate() }
    public var color: Int = Colors.WHITE
        set(value) { if (field != value) { field = value; invalidate() } }
    public var scale: Int = 1
        set(value) { if (field != value) { field = value; invalidate() } }

    internal override fun draw(canvas: CanvasScope) {
        canvas.text(0, 0, text, color, scale)
    }
}

/** A disabled button still blocks clicks. Registering or drawing a button never invokes [onClick]. */
public class UiButton internal constructor(size: Size2i, label: String) : UiElement(size) {
    public var label: String = label
        set(value) { val changed = field != value; field = value; if (changed) invalidate() }
    public var enabled: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }
    public var onClick: (() -> Unit)? = null

    internal override fun pointer(event: DisplayPointerEvent) {
        if (event.kind == DisplayPointerKind.DOWN && enabled) onClick?.invoke()
    }

    internal override fun draw(canvas: CanvasScope) {
        DisplayWidgets(canvas).button(Rect2i(Vector2i(0, 0), size), label, null, enabled)
    }
}

public class UiIndicator internal constructor(size: Size2i, label: String) : UiElement(size) {
    public var label: String = label
        set(value) { val changed = field != value; field = value; if (changed) invalidate() }
    public var color: Int = Colors.GREEN
        set(value) { if (field != value) { field = value; invalidate() } }

    internal override fun draw(canvas: CanvasScope) {
        DisplayWidgets(canvas).indicator(Rect2i(Vector2i(0, 0), size), label, color)
    }
}

public class UiGauge internal constructor(size: Size2i) : UiElement(size) {
    public var value: Float = Float.NaN
        set(value) { val changed = field != value && !(field != field && value != value); field = value; if (changed) invalidate() }
    public var minimum: Float = 0f
        set(value) { val changed = field != value && !(field != field && value != value); field = value; if (changed) invalidate() }
    public var maximum: Float = 100f
        set(value) { val changed = field != value && !(field != field && value != value); field = value; if (changed) invalidate() }
    public var color: Int = Colors.GREEN
        set(value) { if (field != value) { field = value; invalidate() } }

    internal override fun draw(canvas: CanvasScope) {
        DisplayWidgets(canvas).gauge(Rect2i(Vector2i(0, 0), size), value, minimum, maximum, color)
    }
}

public class UiChart internal constructor(size: Size2i, public val history: DisplayHistory) : UiElement(size) {
    private var historyRevision: Long = -1
    private var drawingHistoryRevision: Long = -1
    internal override fun damage(): Rect2i? {
        if (historyRevision != history.revision) invalidate()
        return super.damage()
    }
    internal override fun prepare() { super.prepare(); drawingHistoryRevision = history.revision }
    internal override fun painted() { super.painted(); historyRevision = drawingHistoryRevision }
    public var minimum: Float = 0f
        set(value) { val changed = field != value && !(field != field && value != value); field = value; if (changed) invalidate() }
    public var maximum: Float = 100f
        set(value) { val changed = field != value && !(field != field && value != value); field = value; if (changed) invalidate() }
    public var color: Int = Colors.CYAN
        set(value) { if (field != value) { field = value; invalidate() } }

    internal override fun draw(canvas: CanvasScope) {
        DisplayWidgets(canvas).chart(Rect2i(Vector2i(0, 0), size), history, minimum, maximum, color)
    }
}

internal class UiHit(val element: UiElement, val position: Position2i)
internal class UiCapture(val gesture: Int, val hit: UiHit)

/** Local drawing and pointer surface. Call invalidate after changing captured drawing state. */
public class UiCanvas internal constructor(size: Size2i) : UiElement(size) {
    public var onDraw: (CanvasScope.() -> Unit)? = null
        set(value) { field = value; invalidate() }
    public var onPointer: ((DisplayPointerEvent) -> Unit)? = null

    internal override fun draw(canvas: CanvasScope) { onDraw?.invoke(canvas) }
    internal override fun pointer(event: DisplayPointerEvent) { onPointer?.invoke(event) }
}

/** Horizontal finite-range slider. Click/drag changes value; assigning value does not invoke the handler. */
public class UiSlider internal constructor(size: Size2i, public val minimum: Float, public val maximum: Float) : UiElement(size) {
    init {
        require(size.x >= 2 && size.y >= 1)
        require(minimum >= -Float.MAX_VALUE && maximum <= Float.MAX_VALUE && maximum > minimum)
    }

    public var enabled: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }
    public var color: Int = Colors.CYAN
        set(value) { if (field != value) { field = value; invalidate() } }
    public var onValueChange: ((Float) -> Unit)? = null
    public var value: Float = minimum
        set(value) {
            require(value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE)
            val next = if (value < minimum) minimum else if (value > maximum) maximum else value
            val changed = field != next
            field = next
            if (changed) invalidate()
        }

    internal override fun pointer(event: DisplayPointerEvent) {
        if (!enabled || (event.kind != DisplayPointerKind.DOWN && event.kind != DisplayPointerKind.DRAG)) return
        val x = if (event.position.x < 0) 0 else if (event.position.x >= size.x) size.x - 1 else event.position.x
        val fraction = x.toDouble() / (size.x - 1)
        val next = (minimum.toDouble() * (1.0 - fraction) + maximum.toDouble() * fraction).toFloat()
        if (next != value) {
            value = next
            onValueChange?.invoke(value)
        }
    }

    internal override fun draw(canvas: CanvasScope) {
        canvas.fillRect(0, 0, size.x, size.y, 0x202B33)
        val fraction = (value.toDouble() - minimum) / (maximum.toDouble() - minimum)
        val x = (fraction * (size.x - 1)).toInt()
        canvas.line(x, 0, x, size.y - 1, if (enabled) color else 0x596168)
        canvas.rect(Rect2i(Vector2i(0, 0), size), 0x687D8C)
    }
}

internal class UiPlacement(val element: UiElement, val position: Position2i)

internal class UiGroup(val children: List<UiPlacement>, size: Size2i) : UiElement(size) {
    internal override fun damage(): Rect2i? {
        var result = super.damage()
        for (child in children) {
            val area = child.element.damage()
            if (area != null) result = uiUnion(result, Rect2i(uiPosition(child.position, area.position), area.size))
        }
        return result
    }

    internal override fun prepare() { super.prepare(); for (child in children) child.element.prepare() }
    internal override fun painted() { super.painted(); for (child in children) child.element.painted() }

    internal override fun draw(canvas: CanvasScope) {
        for (child in children) {
            val target = canvas.child(child.position, child.element.size)
            if (!target.isEmpty) child.element.draw(target)
        }
    }

    internal override fun hit(point: Position2i, position: Position2i): UiHit? {
        if (!(point in Rect2i(position, size))) return null
        var index = children.size - 1
        while (index >= 0) {
            val child = children[index]
            val found = child.element.hit(point, uiPosition(position, child.position))
            if (found != null) return found
            index--
        }
        return null
    }
}

/**
 * Construction-only DSL. Row/column children flow in declaration order; root and [at] children overlap.
 * Sizes and positions are pixels, fixed at construction. A retained builder cannot be used after construction.
 */
public class UiBuilder internal constructor(private val direction: Int, private val spacing: Int) {
    private val children: MutableList<UiPlacement> = ArrayList()
    private var width: Int = 0
    private var height: Int = 0
    private var closed: Boolean = false

    init { require(spacing >= 0) }

    public fun text(text: String, size: Size2i, block: (UiText.() -> Unit)? = null): UiText {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiText(size, text)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun button(label: String, size: Size2i, block: (UiButton.() -> Unit)? = null): UiButton {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiButton(size, label)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun indicator(label: String, size: Size2i, block: (UiIndicator.() -> Unit)? = null): UiIndicator {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiIndicator(size, label)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun gauge(size: Size2i, block: (UiGauge.() -> Unit)? = null): UiGauge {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiGauge(size)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun chart(size: Size2i, history: DisplayHistory, block: (UiChart.() -> Unit)? = null): UiChart {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiChart(size, history)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun canvas(size: Size2i, block: (UiCanvas.() -> Unit)? = null): UiCanvas {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiCanvas(size)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun slider(size: Size2i, block: (UiSlider.() -> Unit)? = null): UiSlider = slider(size, 0f, 1f, block)

    public fun slider(size: Size2i, minimum: Float, maximum: Float, block: (UiSlider.() -> Unit)? = null): UiSlider {
        if (closed) throw IllegalStateException("UI construction has finished")
        val element = UiSlider(size, minimum, maximum)
        block?.invoke(element)
        add(element, Vector2i(0, 0))
        return element
    }

    public fun row(spacing: Int = 0, block: UiBuilder.() -> Unit): UiElement {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return group(1, spacing, Vector2i(0, 0), block)
    }

    public fun column(spacing: Int = 0, block: UiBuilder.() -> Unit): UiElement {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return group(2, spacing, Vector2i(0, 0), block)
    }

    /** Offsets a group relative to its parent; row/column flow adds its offset before this position. */
    public fun at(position: Position2i, block: UiBuilder.() -> Unit): UiElement {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        return group(0, 0, position, block)
    }

    private fun group(direction: Int, spacing: Int, position: Position2i, block: UiBuilder.() -> Unit): UiElement {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        if (closed) throw IllegalStateException("UI construction has finished")
        val builder = UiBuilder(direction, spacing)
        builder.block()
        val element = builder.finish()
        add(element, position)
        return element
    }

    private fun add(element: UiElement, offset: Position2i) {
        if (closed) throw IllegalStateException("UI construction has finished")
        require(offset.x >= 0 && offset.y >= 0)
        var x = offset.x
        var y = offset.y
        val gap = if (children.isEmpty()) 0 else spacing
        if (direction == 1) x = uiExtent(x.toLong() + width + gap)
        if (direction == 2) y = uiExtent(y.toLong() + height + gap)
        val right = uiExtent(x.toLong() + element.size.x)
        val bottom = uiExtent(y.toLong() + element.size.y)
        children.add(UiPlacement(element, Vector2i(x, y)))
        if (right > width) width = right
        if (bottom > height) height = bottom
    }

    internal fun finish(): UiGroup {
        if (closed) throw IllegalStateException("UI construction has finished")
        closed = true
        return UiGroup(children, Vector2i(width, height))
    }
}

/** A retained tree in an explicit screen viewport. The caller owns polling; render owns incremental frames. */
public class DisplayUi internal constructor(bounds: Rect2i, private val root: UiGroup) {
    private var captures: MutableList<UiCapture> = ArrayList()
    private var dispatching: Boolean = false
    private var renderedBounds: Rect2i? = null
    private var renderedBackground: Int = -1
    private var rendering: Boolean = false
    private var renderedDisplay: GraphicalDisplay? = null
    private var invalidation: Long = 1
    private var paintedInvalidation: Long = 0
    public var bounds: Rect2i = bounds
        set(value) {
            if (value != field) { cancelInput(); invalidate() }
            field = value
        }
    public val size: Size2i get() = bounds.size
    public val contentSize: Size2i get() = root.size

    /** Click-only compatibility: one hit leaf blocks the click, even if passive or disabled. */
    public fun dispatch(click: DisplayClick?): Boolean {
        if (click == null || !(click.position in bounds)) return false
        if (dispatching) throw IllegalStateException("Recursive UI dispatch")
        val hit = root.hit(local(click.position, Vector2i(0, 0)), Vector2i(0, 0)) ?: return false
        dispatching = true
        try {
            hit.element.pointer(DisplayPointerEvent(DisplayPointerKind.DOWN, 0, local(click.position, hit.position)))
        } finally { dispatching = false }
        return true
    }

    /**
     * Down captures one topmost leaf for this gesture. Drag/Up use that leaf's local coordinates,
     * including outside its rectangle. Up/Cancel release capture before invoking callbacks.
     * Handler failures drop that gesture and propagate. No polling or drawing occurs here.
     */
    public fun dispatch(event: DisplayPointerEvent?): Boolean {
        if (event == null) return false
        if (dispatching) throw IllegalStateException("Recursive UI dispatch")
        if (event.kind == DisplayPointerKind.CANCEL && event.gesture == 0) {
            cancelInput()
            return true
        }
        var index = 0
        while (index < captures.size && captures[index].gesture != event.gesture) index++
        var hit: UiHit? = if (index < captures.size) captures[index].hit else null
        if (event.kind == DisplayPointerKind.DOWN) {
            if (event.gesture <= 0) return false
            if (hit != null || !(event.position in bounds)) return false
            hit = root.hit(local(event.position, Vector2i(0, 0)), Vector2i(0, 0)) ?: return false
            if (captures.size >= 8) return false
            captures.add(UiCapture(event.gesture, hit))
        } else {
            if (hit == null) return false
            if (event.kind == DisplayPointerKind.UP || event.kind == DisplayPointerKind.CANCEL) captures.removeAt(index)
        }
        dispatching = true
        try {
            hit.element.pointer(DisplayPointerEvent(event.kind, event.gesture, local(event.position, hit.position)))
        } catch (error: Throwable) {
            var failed = 0
            while (failed < captures.size) {
                if (captures[failed].gesture == event.gesture) { captures.removeAt(failed); break }
                failed++
            }
            throw error
        } finally { dispatching = false }
        return true
    }

    /** Clears capture before notifying widgets; call when rebinding or abandoning a display. */
    public fun cancelInput() {
        if (captures.isEmpty()) return
        val previous = captures
        captures = ArrayList()
        val previousDispatching = dispatching
        dispatching = true
        try {
            for (capture in previous) {
                capture.hit.element.pointer(DisplayPointerEvent(DisplayPointerKind.CANCEL, capture.gesture, Vector2i(0, 0)))
            }
        } finally { dispatching = previousDispatching }
    }

    /** True until the first successful render, or after visible widget/state changes. */
    public val needsRedraw: Boolean get() = paintedInvalidation != invalidation || renderedBounds != bounds || root.damage() != null

    public fun invalidate() { invalidation++ }

    /**
     * Repaint only the damaged region, including overlapping widgets in their original order.
     * Owns the background in [bounds]. An unchanged UI makes no display calls and opens no frame.
     * Custom animations call their widget's invalidate(); failures leave damage pending for retry.
     * Use draw() instead when the caller owns a larger composition and its frame lifecycle.
     */
    public fun render(display: GraphicalDisplay, background: Int = 0x000000): Boolean {
        if (rendering) throw IllegalStateException("Recursive UI rendering")
        require(background in 0..0xFFFFFF)
        val full = paintedInvalidation != invalidation || renderedBounds != bounds || renderedBackground != background || renderedDisplay != display
        val localDamage = root.damage()
        if (!full && localDamage == null) return false
        val targetBounds = bounds
        val oldBounds = if (renderedDisplay == display) renderedBounds else null
        val area = if (full) targetBounds else Rect2i(uiGlobal(targetBounds.position, localDamage!!.position), localDamage.size).intersection(targetBounds)
        val version = invalidation
        root.prepare()
        rendering = true
        try {
            val changed = !area.isEmpty || (oldBounds != null && oldBounds != targetBounds && !oldBounds.isEmpty)
            if (changed) {
                display.beginFrame()
                val frame = display.recorder()
                var completed = false
                try {
                    if (oldBounds != null && oldBounds != targetBounds) frame.fillRect(oldBounds, background)
                    if (!area.isEmpty) {
                        frame.fillRect(area, background)
                        root.draw(CanvasScope(frame, targetBounds, area))
                    }
                    display.publishFrame(frame)
                    completed = true
                } finally { display.closeFrame(frame, completed) }
            }
            root.painted()
            renderedBounds = targetBounds
            renderedBackground = background
            renderedDisplay = display
            paintedInvalidation = version
            return changed
        } finally { rendering = false }
    }

    /** Clipped drawing in [bounds]; does not clear, publish a frame or invoke input handlers. */
    public fun draw(display: DisplayFrame) { root.draw(display.canvas(bounds)) }

    private fun local(position: Position2i, origin: Position2i): Position2i =
        Vector2i(uiInputCoordinate(position.x.toLong() - bounds.left - origin.x), uiInputCoordinate(position.y.toLong() - bounds.top - origin.y))
}

/** Build once, then retain widget references for explicit updates. Root/leaf viewports clip drawing. */
public fun displayUi(bounds: Rect2i, block: UiBuilder.() -> Unit): DisplayUi {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val builder = UiBuilder(0, 0)
    builder.block()
    return DisplayUi(bounds, builder.finish())
}

private fun uiInputCoordinate(value: Long): Int =
    if (value < Int.MIN_VALUE.toLong()) Int.MIN_VALUE else if (value > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else value.toInt()

private fun uiExtent(value: Long): Int {
    if (!(value >= 0L && value <= Int.MAX_VALUE.toLong())) throw IllegalArgumentException("UI extent overflows")
    return value.toInt()
}

private fun uiPosition(parent: Position2i, child: Position2i): Position2i =
    Vector2i(uiExtent(parent.x.toLong() + child.x), uiExtent(parent.y.toLong() + child.y))

private fun uiUnion(first: Rect2i?, second: Rect2i): Rect2i {
    if (first == null || first.isEmpty) return second
    if (second.isEmpty) return first
    val left = if (first.left < second.left) first.left else second.left
    val top = if (first.top < second.top) first.top else second.top
    val right = if (first.right > second.right) first.right else second.right
    val bottom = if (first.bottom > second.bottom) first.bottom else second.bottom
    return Rect2i(Vector2i(left, top), Vector2i(right - left, bottom - top))
}

private fun uiGlobal(origin: Position2i, local: Position2i): Position2i {
    val x = origin.x.toLong() + local.x
    val y = origin.y.toLong() + local.y
    require(x >= Int.MIN_VALUE.toLong() && x <= Int.MAX_VALUE.toLong() && y >= Int.MIN_VALUE.toLong() && y <= Int.MAX_VALUE.toLong())
    return Vector2i(x.toInt(), y.toInt())
}
