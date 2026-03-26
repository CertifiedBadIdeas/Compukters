import terminal;
import events;

fun main() {
    terminal.printLine("Compukter Kraft ready");
    while true {
        val event: Event = events.pull();
        terminal.printLine(event.name);
    }
}
