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

package ru.lazyhat.compukters.impl.ide

/** Shared painter ordering for all IDE presentation components. */
internal object IdeDrawLayers {
    const val CONTROL_BACKGROUND_OFFSET = IdeControls.BACKGROUND_OFFSET
    const val Z_BACKGROUND = 0
    const val Z_PANEL = 10
    const val Z_CONTENT = 20
    const val Z_SELECTION = 24
    const val Z_CLIP = 25
    const val Z_TEXT = 30
    const val Z_CARET = 35
    const val Z_TARGET = 40
    const val Z_POPUP = 50
    const val Z_POPUP_TARGET = Z_POPUP + CONTROL_BACKGROUND_OFFSET + 1
    const val Z_DRAG = 60
    const val Z_PROJECT_SWITCHER = 70
    const val Z_PROJECT_SWITCHER_SELECTION = 72
    const val Z_PROJECT_SWITCHER_TEXT = 75
    const val Z_PROJECT_SWITCHER_TARGET = Z_PROJECT_SWITCHER + CONTROL_BACKGROUND_OFFSET + 1
    const val Z_TOOLTIP = 82
    const val Z_TOOLTIP_TEXT = 85
    const val Z_POPUP_TEXT = 55
    const val Z_DIALOG_SCRIM = 90
    const val Z_DIALOG = 100
    const val Z_DIALOG_TEXT = 110
    const val Z_DIALOG_TARGET = 120
}
