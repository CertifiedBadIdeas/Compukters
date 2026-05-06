fun glyphPattern(ch: String): String {
    if (ch == "A" || ch == "a") { return "01110100011000111111100011000110001" }
    if (ch == "B" || ch == "b") { return "11110100011000111110100011000111110" }
    if (ch == "C" || ch == "c") { return "01111100001000010000100001000001111" }
    if (ch == "D" || ch == "d") { return "11110100011000110001100011000111110" }
    if (ch == "E" || ch == "e") { return "11111100001000011110100001000011111" }
    if (ch == "F" || ch == "f") { return "11111100001000011110100001000010000" }
    if (ch == "G" || ch == "g") { return "01111100001000010011100011000101111" }
    if (ch == "H" || ch == "h") { return "10001100011000111111100011000110001" }
    if (ch == "I" || ch == "i") { return "11111001000010000100001000010011111" }
    if (ch == "J" || ch == "j") { return "00111000100001000010100101001001100" }
    if (ch == "K" || ch == "k") { return "10001100101010011000101001001010001" }
    if (ch == "L" || ch == "l") { return "10000100001000010000100001000011111" }
    if (ch == "M" || ch == "m") { return "10001110111010110101100011000110001" }
    if (ch == "N" || ch == "n") { return "10001110011010110011100011000110001" }
    if (ch == "O" || ch == "o") { return "01110100011000110001100011000101110" }
    if (ch == "P" || ch == "p") { return "11110100011000111110100001000010000" }
    if (ch == "Q" || ch == "q") { return "01110100011000110001101011001001101" }
    if (ch == "R" || ch == "r") { return "11110100011000111110101001001010001" }
    if (ch == "S" || ch == "s") { return "01111100001000001110000010000111110" }
    if (ch == "T" || ch == "t") { return "11111001000010000100001000010000100" }
    if (ch == "U" || ch == "u") { return "10001100011000110001100011000101110" }
    if (ch == "V" || ch == "v") { return "10001100011000110001100010101000100" }
    if (ch == "W" || ch == "w") { return "10001100011000110101101011010101010" }
    if (ch == "X" || ch == "x") { return "10001010100010000100001000101010001" }
    if (ch == "Y" || ch == "y") { return "10001010100010000100001000010000100" }
    if (ch == "Z" || ch == "z") { return "11111000010001000100010001000011111" }
    if (ch == "0") { return "01110100011001110101110011000101110" }
    if (ch == "1") { return "00100011000010000100001000010001110" }
    if (ch == "2") { return "01110100010000100010001000100011111" }
    if (ch == "3") { return "11110000010000100110000010000111110" }
    if (ch == "4") { return "00010001100101010010111110001000010" }
    if (ch == "5") { return "11111100001111000001000010000111110" }
    if (ch == "6") { return "01111100001000011110100011000101110" }
    if (ch == "7") { return "11111000010001000100010000100001000" }
    if (ch == "8") { return "01110100011000101110100011000101110" }
    if (ch == "9") { return "01110100011000101111000010000111110" }
    if (ch == ".") { return "00000000000000000000000000000000100" }
    if (ch == ":") { return "00000001000010000000001000010000000" }
    if (ch == "/") { return "00001000100001000100010000100010000" }
    if (ch == "-") { return "00000000000000011111000000000000000" }
    if (ch == "_") { return "00000000000000000000000000000011111" }
    if (ch == ">") { return "10000010000010000010001001000000000" }
    if (ch == "`" || ch == "'") { return "00100001000000000000000000000000000" }
    if (ch == "!") { return "00100001000010000100001000000000100" }
    if (ch == "?") { return "01110100010000100010001000000000100" }
    if (ch == "#") { return "01010111110101011111010100000000000" }
    return "11111100011000110001100011000111111"
}

fun draw_glyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    if (ch == " ") {
        return
    }
    display::blitMono(displayId, column * 6, row * 9, 5, 7, glyphPattern(ch), color, -1)
}

fun draw_text(displayId: Int, row: Int, text: String, color: Int) {
    var x: Int = 0
    var i: Int = 0
    while i < strings::length(text) {
        draw_glyph(displayId, x, row, strings::charAt(text, i), color)
        x = x + 1
        i = i + 1
    }
}

fun draw_sprite(displayId: Int, x: Int, y: Int, width: Int, height: Int, pattern: String, color: Int) {
    display::blitMono(displayId, x, y, width, height, pattern, color, -1)
}

fun draw_splash(displayId: Int) {
    display::clear(displayId, 0)
    display::fillRect(displayId, 0, 0, display::width(displayId), 2, 2016)
    display::fillRect(displayId, 0, display::height(displayId) - 3, display::width(displayId), 3, 2016)
    draw_text(displayId, 1, "Compukter", 2016)
    draw_text(displayId, 3, "KRAFT BIOS", 65535)
    draw_sprite(displayId, 6, 52, 17, 9, "111111111111111111000000000000000110111100111100101101000001000001011011110011110010110000000000000001111111111111111110000001111100000000001111111110000", 63488)
    draw_text(displayId, 10, "Loading boot.ck...", 65535)
    display::present(displayId)
}

fun draw_splash_frame() {
    val id: Int = display::primary()
    if (id >= 0) {
        draw_splash(id)
    }
}

fun hold_splash(ticks: Int) {
    draw_splash_frame()
    var remaining: Int = ticks
    while remaining > 0 {
        val event: Event = events::tryPull()
        if (event.name == "display_attach" || event.name == "display_resize") {
            draw_splash_frame()
        }
        remaining = remaining - 1
        sleep(1)
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
    hold_splash(20)

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