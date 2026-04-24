package ru.lazyhat.compukterkraft.core.ui.program

import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Position

/**
 * Runtime-side counterpart to [ScreenProgram].
 *
 * Per render tick the executor:
 *
 * 1. Iterates [ScreenProgram.frames] in order.
 * 2. For each frame, evaluates (at most once) `visible` to decide skip/draw
 *    and `origin` to translate the baked relative coordinates of its ops.
 * 3. Evaluates op-level [ru.lazyhat.compukterkraft.core.ui.foundation.ValueExpression]s
 *    (text, snapshots) in place while emitting backend calls.
 *
 * Hit-testing walks [ScreenProgram.hitRegions] in compile-time z-order
 * (descending), evaluating the owning frame's origin/visibility as needed.
 *
 * No allocations, no map lookups, no recursion.
 */
class ScreenRuntimeExecutor(
    private val program: ScreenProgram,
) {
    fun render(backend: RenderBackend) {
        for (frame in program.frames) {
            if (frame.visible != null && !frame.visible.evaluate()) continue
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val ox = origin.x
            val oy = origin.y
            for (op in frame.ops) {
                when (op) {
                    is RenderOp.FillRect -> {
                        backend.fillRect(op.x + ox, op.y + oy, op.width, op.height, op.color)
                    }

                    is RenderOp.DrawText -> {
                        backend.drawText(op.x + ox, op.y + oy, op.value.evaluate(), op.color)
                    }

                    is RenderOp.DrawTerminalSurface -> {
                        backend.drawTerminalSurface(op.x + ox, op.y + oy, op.snapshot.evaluate())
                    }
                }
            }
        }
    }

    fun mouseClicked(
        x: Int,
        y: Int,
    ): Boolean {
        for (region in program.hitRegions) {
            val frame = program.frames[region.frameIndex]
            if (frame.visible != null && !frame.visible.evaluate()) continue
            val origin = frame.origin?.evaluate() ?: Position.Zero
            val rx = region.x + origin.x
            val ry = region.y + origin.y
            if (x >= rx && y >= ry && x < rx + region.width && y < ry + region.height) {
                region.onClick.invoke()
                return true
            }
        }
        return false
    }

    fun keyPressed(keyCode: Int): Boolean = program.keyHandler?.invoke(keyCode) ?: false
}
