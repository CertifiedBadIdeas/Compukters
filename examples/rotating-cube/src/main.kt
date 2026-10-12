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

import compukter.concurrent.Tasks
import compukter.display.*
import kotlin.math.*

// Assemble nine displays into one 3x3 screen and connect it to the computer.
// Resolve Dependencies, then Run. Ctrl+C stops the animation.
const val BACKGROUND = 0x080E1B
const val CAMERA_DISTANCE = 4f

fun isNinePanelScreen(screen: GraphicalDisplay): Boolean {
    val pixelsPerBlock =
        when (screen.mode) {
            DisplayMode.ULTRA_LOW -> 16
            DisplayMode.LOW -> 32
            DisplayMode.NORMAL -> 64
            DisplayMode.HIGH -> 128
        }
    return screen.width == pixelsPerBlock * 3 && screen.height == pixelsPerBlock * 3
}

// The fixed camera keeps the whole cube inside the frustum throughout its rotation.
fun project(
    clip: Vector4f,
    viewport: Vector2f,
): Vector2i {
    val ndc = clip.perspectiveDivide()
    return Vector2f(
        (ndc.x + 1f) * 0.5f * viewport.x,
        (1f - ndc.y) * 0.5f * viewport.y,
    ).roundToInt()
}

fun main() {
    val screen =
        GraphicalDisplay.firstOrNull { isNinePanelScreen(it) }
            ?: throw IllegalStateException("Connect one assembled 3x3 display screen")
    screen.setMode(DisplayMode.NORMAL)
    val uiSize = screen.size - Vector2i(24, 24)
    val drawingSize = uiSize - Vector2i(0, 46)
    val viewport = (drawingSize - Vector2i(1, 1)).toFloat()
    val projection =
        Matrix4f.perspective(
            (PI * 70.0 / 180.0).toFloat(),
            drawingSize.x.toFloat() / drawingSize.y.toFloat(),
            0.1f,
            100f,
        )
    val camera = Matrix4f.translation(Vector3f(0f, 0f, -CAMERA_DISTANCE))
    // The camera and projection stay fixed; only the cube orientation changes per frame.
    val viewProjection = projection * camera
    val corners =
        arrayOf(
            Vector3f(-1f, -1f, -1f),
            Vector3f(1f, -1f, -1f),
            Vector3f(-1f, 1f, -1f),
            Vector3f(1f, 1f, -1f),
            Vector3f(-1f, -1f, 1f),
            Vector3f(1f, -1f, 1f),
            Vector3f(-1f, 1f, 1f),
            Vector3f(1f, 1f, 1f),
        )
    val projected =
        Array<Vector2i>(8) {
            Vector2i(0, 0)
        }
    val axisBits = intArrayOf(1, 2, 4)
    val axisColors = intArrayOf(0xFF6688, 0x66EEAA, 0x66BBFF)
    var angle = 0f
    val ui = displayUi(Rect2i(12 xy 12, uiSize)) {
        column(spacing = 6) {
            text("Hold RMB and aim to rotate", uiSize.x by 14) { color = 0x66BBFF }
            canvas(drawingSize) {
                onDraw = {
                    clear(BACKGROUND)
                    for (index in 0 until 8) {
                        for (axis in 0 until 3) {
                            val bit = axisBits[axis]
                            if ((index and bit) == 0) line(projected[index], projected[index or bit], axisColors[axis])
                        }
                    }
                }
            }
            slider(uiSize.x by 20, 0f, (PI * 2.0).toFloat()) {
                onValueChange = { angle = it }
            }
        }
    }
    println("Cube slider on ${screen.width}x${screen.height}; hold RMB and aim; Ctrl+C stops")
    while (true) {
        var count = 0
        while (count < 64) {
            val event = screen.pollPointer() ?: break
            ui.dispatch(event)
            count++
        }
        val orientation = Rotation3f.fromAxisAngle(Vector3f(0.7f, 1f, 0.3f), angle)
        val transform = viewProjection * orientation.toMatrix4f()
        for (index in 0 until 8) projected[index] = project(transform * corners[index], viewport)
        screen.frame {
            clear(BACKGROUND)
            ui.draw(this)
        }
        Tasks.sleepTicks(1)
    }
}
