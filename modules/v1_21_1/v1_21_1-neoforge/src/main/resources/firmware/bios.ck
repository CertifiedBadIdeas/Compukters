fun draw_text(displayId: Int, row: Int, text: String, color: Int) {
    var x: Int = 0
    var i: Int = 0
    while i < strings::length(text) {
        if (strings::charAt(text, i) != " ") {
            display::fillRect(displayId, x * 6, row * 9, 5, 8, color)
        }
        x = x + 1
        i = i + 1
    }
}

fun draw_boot_frame(status: String) {
    val id: Int = display::primary()
    if (id >= 0) {
        display::clear(id, 0)
        draw_text(id, 0, "Compukter Kraft BIOS", 2016)
        draw_text(id, 2, status, 63488)
        display::present(id)
    }
}

pub fun main() {
    var status: String = "Searching for boot.ck..."
    draw_boot_frame(status)

    if (!filesystem::exists("boot.ck")) {
        status = "No boot.ck found. Create boot.ck and reboot."
        draw_boot_frame(status)
    } else {
        val input: Int = ipc::open()
        val output: Int = ipc::open()
        val error: Int = ipc::open()
        val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
        if (code == 0) {
            status = "boot.ck exited with code 0"
        } else {
            val processErrors: String = ipc::tryRead(error)
            if (processErrors != "") {
                status = processErrors
            } else {
                status = "boot.ck failed with code " + code
            }
        }
        draw_boot_frame(status)
     }

    while true {
        val event: Event = events::pull()
        if (event.name == "display_attach" || event.name == "display_resize") {
            draw_boot_frame(status)
        }
    }
}