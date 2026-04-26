import process
import terminal

fun main() {
    val path: String = process.currentDirectory()
    if (path == "") {
        terminal.printLine("/")
    } else {
        terminal.printLine("/" + path)
    }
}
