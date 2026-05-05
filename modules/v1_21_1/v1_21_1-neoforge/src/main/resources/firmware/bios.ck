fun draw_boot_frame() {
    val id: Int = display::primary()
    if (id >= 0) {
        display::clear(id, 0)
        display::fillRect(id, 0, 0, display::width(id), display::height(id), 2016)
        display::fillRect(id, 8, 8, 48, 24, 63488)
        display::present(id)
    }
}

pub fun main() {
    terminal::println("Compukter Kraft BIOS")
    draw_boot_frame()
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