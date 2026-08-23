@file:Suppress("UNUSED_PARAMETER")

suspend fun terminalAwaitEvent(): Int = 0

fun terminalClear(): Unit = Unit

fun terminalErasePrevious(): Unit = Unit

fun terminalEventAction(): Int = 0

fun terminalEventKey(): Int = 0

fun terminalEventModifiers(): Int = 0

fun terminalEventText(): String = ""

fun terminalFinishEvent(): Unit = Unit

fun terminalWrite(value: String): Unit = Unit
