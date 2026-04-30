import process
import terminal

fun main() {
    val path: String = process.currentDirectory()
    if (path == "") {
        terminal.println("/")
    } else {
        terminal.println("/" + path)
    }
}
