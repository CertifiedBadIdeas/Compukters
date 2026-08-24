import compukter.terminal.Terminal

suspend fun main() {
    Terminal.write("Your name: ")
    var name = ""
    var reading = true
    while (reading) {
        val event = Terminal.awaitEvent()
        if (event == 1) {
            name = name + Terminal.eventText()
        } else if (Terminal.eventKey() == 13 && Terminal.eventAction() == 1) {
            reading = false
        }
        Terminal.finishEvent()
    }
    Terminal.write("Hello, " + name + "!\n")
}
