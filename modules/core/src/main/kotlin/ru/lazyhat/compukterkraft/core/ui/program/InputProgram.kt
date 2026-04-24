package ru.lazyhat.compukterkraft.core.ui.program

enum class InputEventType {
    Click,
}

data class InputProgram(
    val routes: List<InputRoute>,
)

data class InputRoute(
    val regionId: String,
    val eventType: InputEventType,
    val handlerId: String,
)
