package ru.lazyhat.compukterkraft.core.computer.runtime

import java.util.UUID

/** Platform-neutral handle to a player, used by runtime devices for access checks
 *  without depending on net.minecraft.* types. */
interface PlayerHandle {
    val uuid: UUID
    val isStillValid: Boolean
}
