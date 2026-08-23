suspend fun main() {
    terminalWrite("Your name: ")
    var name = ""
    var reading = true
    while (reading) {
        val event = terminalAwaitEvent()
        if (event == 1) {
            name = name + terminalEventText()
        } else if (terminalEventKey() == 13 && terminalEventAction() == 1) {
            reading = false
        }
        terminalFinishEvent()
    }
    terminalWrite("Hello, " + name + "!\n")
}
