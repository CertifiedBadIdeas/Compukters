import filesystem
import process
import strings
import terminal

fun main() {
    val target: String = strings.trim(process.argument())
    if (strings.isBlank(target)) {
        terminal.println(filesystem.list())
    } else {
        terminal.println(filesystem.list(target))
    }
}
