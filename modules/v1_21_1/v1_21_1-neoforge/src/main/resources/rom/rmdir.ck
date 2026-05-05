pub fun main() {
    val target: String = strings::trim(process::argument())
    if (strings::isBlank(target)) {
        terminal::println("Usage: rmdir <path>")
        return
    }
    if (!filesystem::remove(target)) {
        terminal::println("rmdir failed: " + target)
    }
}
