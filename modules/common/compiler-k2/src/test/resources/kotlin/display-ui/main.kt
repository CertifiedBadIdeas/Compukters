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

import compukter.display.*
import kotlin.math.*

fun main() {
    var first = 0
    var second = 0
    var draws = 0
    val button: UiButton
    val slider: UiSlider
    val canvas: UiCanvas
    val builder: UiBuilder
    var localPoint = Vector2i(0, 0)
    var cancelled = 0
    val ui = displayUi(Rect2i(100 xy 50, 80 by 80)) {
        builder = this
        column(spacing = 4) {
            row(spacing = 2) {
                button("A", 10 by 10) { onClick = { first++ } }
                button = button("B", 10 by 10) { onClick = { second++ } }
            }
            slider = slider(21 by 5, -1f, 1f)
        }
        at(40 xy 0) {
            canvas = canvas(10 by 10) {
                onDraw = { draws++ }
                onPointer = { event ->
                    localPoint = event.position
                    if (event.kind == DisplayPointerKind.CANCEL) cancelled++
                }
            }
        }
        at(0 xy 40) {
            button("under", 10 by 10) { onClick = { first++ } }
            button("disabled", 10 by 10) { enabled = false; onClick = { second++ } }
        }
        at(20 xy 40) {
            button("covered", 10 by 10) { onClick = { first++ } }
            text("decoration", 10 by 10)
        }
    }
    check(first == 0 && second == 0 && draws == 0)
    ui.draw(GraphicalDisplay.named("screen"))
    check(draws == 1)
    check(ui.contentSize == 50 by 50)
    check(ui.dispatch(DisplayClick(100 xy 50)) && first == 1)
    check(!ui.dispatch(DisplayClick(110 xy 50))) // row gap, half-open edge
    check(ui.dispatch(DisplayClick(112 xy 50)) && second == 1)
    button.enabled = false
    check(ui.dispatch(DisplayClick(112 xy 50)) && second == 1)
    check(ui.dispatch(DisplayClick(100 xy 90)) && first == 1 && second == 1)
    check(ui.dispatch(DisplayClick(120 xy 90)) && first == 1)
    check(!ui.dispatch(DisplayClick(180 xy 50))) // viewport right edge
    check(draws == 1)
    button.label = "updated"
    check(button.label == "updated")
    var changes = 0
    slider.onValueChange = { changes++ }
    slider.value = -100f
    check(slider.value == -1f && changes == 0)
    slider.value = 100f
    check(slider.value == 1f && changes == 0)
    var invalidValue = false
    try { slider.value = Float.NaN } catch (error: IllegalArgumentException) { invalidValue = true }
    check(invalidValue && slider.value == 1f)
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DOWN, 1, 110 xy 64)))
    check(slider.value == 0f)
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DRAG, 1, 179 xy 100)))
    check(slider.value == 1f && changes == 2) // capture outside slider bounds
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.UP, 1, 179 xy 100)))
    check(!ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DRAG, 1, 100 xy 64)))
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DOWN, 2, 141 xy 52)))
    check(localPoint == 1 xy 2)
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DRAG, 2, 155 xy 63)))
    check(localPoint == 15 xy 13)
    ui.bounds = Rect2i(0 xy 0, 30 by 30)
    check(cancelled == 1)
    check(!ui.dispatch(DisplayPointerEvent(DisplayPointerKind.UP, 2, 0 xy 0)))
    check(!ui.dispatch(DisplayClick(40 xy 0))) // leaf clipped by resized root
    check(ui.dispatch(DisplayPointerEvent(DisplayPointerKind.DOWN, 3, 0 xy 0)))
    ui.dispatch(DisplayPointerEvent(DisplayPointerKind.CANCEL, 0, 0 xy 0))
    check(!ui.dispatch(DisplayPointerEvent(DisplayPointerKind.UP, 3, 0 xy 0)))
    var rejected = false
    try { builder.button("late", 1 by 1) } catch (error: IllegalStateException) { rejected = true }
    check(rejected)
    val failures = displayUi(Rect2i(0 xy 0, 10 by 10)) {
        button("error", 10 by 10) { onClick = { throw IllegalArgumentException("handler") } }
    }
    try { failures.dispatch(DisplayPointerEvent(DisplayPointerKind.DOWN, 4, 0 xy 0)); error("handler must fail") }
    catch (error: IllegalArgumentException) { check(error.message == "handler") }
    check(!failures.dispatch(DisplayPointerEvent(DisplayPointerKind.UP, 4, 0 xy 0)))
    val history = DisplayHistory(96)
    history.add(Float.NaN)
    val scada = displayUi(Rect2i(0 xy 0, 384 by 384)) {
        at(12 xy 10) { text("CREATE DRIVE", 360 by 24) { scale = 2 } }
        at(12 xy 42) { indicator("NO TELEMETRY", 360 by 14) }
        at(12 xy 158) { gauge(360 by 14) { value = Float.NaN } }
        at(12 xy 208) { chart(360 by 84, history) { minimum = -256f; maximum = 256f } }
        at(12 xy 308) {
            row(spacing = 8) {
                button("-16 RPM", 84 by 26) { enabled = false }
                button("+16 RPM", 84 by 26) { enabled = false }
                button("STOP", 84 by 26) { enabled = false }
                button("RESUME", 84 by 26) { enabled = false }
            }
        }
    }
    val screen = GraphicalDisplay.named("screen")
    repeat(50) { renderDashboard(scada, screen) }
    repeat(96) { history.add((it - 48).toFloat()) }
    repeat(2) { renderDashboard(scada, screen) }
    println("display ui ok")
}

fun renderDashboard(ui: DisplayUi, screen: GraphicalDisplay) {
    screen.frame { ui.draw(this) }
}
