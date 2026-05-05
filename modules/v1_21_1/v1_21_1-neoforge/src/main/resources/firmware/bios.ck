pub fun main() {
    terminal::println("Compukter Kraft BIOS")
    terminal::println("Searching for boot.ck...")

    if (!filesystem::exists("boot.ck")) {
        terminal::println("No boot.ck found. Create boot.ck and reboot.")
    } else {
        val code: Int = process::run("boot.ck")
        if (code == 0) {
            terminal::println("boot.ck exited with code 0")
        } else {
            terminal::println("boot.ck failed with code " + code)
            terminal::println("Fix boot.ck and reboot.")
        }
     }



    while true {
        sleep(20L)
    }
}