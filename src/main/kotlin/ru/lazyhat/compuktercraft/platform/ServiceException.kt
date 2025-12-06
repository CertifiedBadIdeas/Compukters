// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.platform

import org.jetbrains.annotations.ApiStatus
import java.io.Serial

/**
 * A ComputerCraft-related service failed to load.
 *
 *
 * Do **NOT** directly reference this class. It exists for internal use by the API.
 */
@ApiStatus.Internal
internal class ServiceException(
    message: String?,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    companion object {
        @Serial
        private val serialVersionUID = -8392300691666423882L
    }
}
