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

package ru.lazyhat.compukterkraft.common.computer.client.retained

internal inline fun <T> cleanupAll(
    resources: Iterable<T>,
    cleanup: (T) -> Unit,
) {
    var firstFailure: Throwable? = null
    for (resource in resources) {
        try {
            cleanup(resource)
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else if (firstFailure !== failure) {
                firstFailure.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}
