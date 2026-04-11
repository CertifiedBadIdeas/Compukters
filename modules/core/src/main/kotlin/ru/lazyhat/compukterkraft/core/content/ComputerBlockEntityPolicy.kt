package ru.lazyhat.compukterkraft.core.content

object ComputerBlockEntityPolicy {
    fun shouldUpdateVisualState(
        current: ComputerVisualStateModel,
        next: ComputerVisualStateModel,
    ): Boolean = current != next

    fun shouldPersistLabel(current: String?, requested: String?): Boolean =
        requested != null && current != requested

    fun shouldPersistComputerId(current: Int?, requested: Int?): Boolean =
        requested != null && current != requested

    fun resolveComputerId(current: Int?, allocate: () -> Int): Int = current ?: allocate()

    fun shouldRunServerTick(levelIsClientSide: Boolean, computerId: Int?): Boolean =
        !levelIsClientSide && computerId != null

    fun desiredVisualState(isComputerOn: Boolean): ComputerVisualStateModel =
        if (isComputerOn) ComputerVisualStateModel.ON else ComputerVisualStateModel.OFF
}