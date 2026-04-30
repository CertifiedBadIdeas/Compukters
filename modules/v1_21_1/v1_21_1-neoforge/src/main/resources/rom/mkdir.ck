import filesystem
import process
import strings
import terminal

fun main() {
    val target: String = strings.trim(process.argument())
    if (strings.isBlank(target)) {
        terminal.println("Usage: mkdir <path>")
        return
    }
    if (!filesystem.makeDir(target)) {
        terminal.println("mkdir failed: " + target)
    }
}
