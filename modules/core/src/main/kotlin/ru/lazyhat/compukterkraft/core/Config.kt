/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core

/**
 * ComputerCraft's global config.
 *
 * @see ConfigSpec The definition of our config values.
 */
object Config {
    var computerSpaceLimit: Int = 1000 * 1000
    var floppySpaceLimit: Int = 125 * 1000
    var uploadMaxSize: Int = 512 * 1024 // 512 KB
    var commandRequireCreative: Boolean = true

    var enableCommandBlock: Boolean = false
    var modemRange: Int = 64
    var modemHighAltitudeRange: Int = 384
    var modemRangeDuringStorm: Int = 64
    var modemHighAltitudeRangeDuringStorm: Int = 384
    var maxNotesPerTick: Int = 8

    // var monitorRenderer: MonitorRenderer? = MonitorRenderer.BEST
    var monitorDistance: Int = 65
    var monitorBandwidth: Long = 1000000

    var turtlesNeedFuel: Boolean = true
    var turtleFuelLimit: Int = 20000
    var advancedTurtleFuelLimit: Int = 100000
    var turtlesCanPush: Boolean = true

    const val DEFAULT_COMPUTER_TERM_WIDTH: Int = 51
    const val DEFAULT_COMPUTER_TERM_HEIGHT: Int = 19

    const val TURTLE_TERM_WIDTH: Int = 39
    const val TURTLE_TERM_HEIGHT: Int = 13

    const val DEFAULT_POCKET_TERM_WIDTH: Int = 26
    const val DEFAULT_POCKET_TERM_HEIGHT: Int = 20

    var monitorWidth: Int = 8
    var monitorHeight: Int = 6

    var uploadNagDelay: Int = 5
}
