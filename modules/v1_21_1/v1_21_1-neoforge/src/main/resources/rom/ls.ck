import filesystem;
import process;
import strings;
import terminal;

fun main() {
    val target: String = strings.trim(process.argument());
    if (strings.isBlank(target)) {
        terminal.printLine(filesystem.list());
    } else {
        terminal.printLine(filesystem.list(target));
    }
}
