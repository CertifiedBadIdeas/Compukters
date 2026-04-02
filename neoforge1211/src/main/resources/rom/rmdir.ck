import filesystem;
import process;
import strings;
import terminal;

fun main() {
    val target: String = strings.trim(process.argument());
    if (strings.isBlank(target)) {
        terminal.printLine("Usage: rmdir <path>");
        return;
    }
    if (!filesystem.remove(target)) {
        terminal.printLine("rmdir failed: " + target);
    }
}
