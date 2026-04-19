package ru.lazyhat.compukterkraft.core.ui.program

data class SlotValues(
    private val values: Map<String, Any?> = emptyMap(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(slotId: String): T = values.getValue(slotId) as T
}
