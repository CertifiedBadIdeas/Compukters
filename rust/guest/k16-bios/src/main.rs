#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;

use k16_abi::computer::{control, debug, gpu0, status};
use k16_boot_chain::{enter_loaded_image, load_k16e_from_storage0, K16eAbiKind};

#[no_mangle]
pub extern "C" fn _start() -> ! {
    set_booting();
    clear_display();
    print_bios_banner();
    print_bios_debug();
    k16_rt::sleep_ticks(20);

    let image = unsafe {
        load_k16e_from_storage0(
            b"BOOT",
            &[b"boot".as_slice(), b"loader.kb".as_slice()],
            K16eAbiKind::Bootloader,
        )
    };
    match image {
        Ok(image) => unsafe { enter_loaded_image(image) },
        Err(error) => {
            print_no_bootable_device();
            print_no_bootable_debug();
            set_halted(error.code());
            wait_forever()
        }
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_bios_panic_debug();
    unsafe {
        write_i32(control::PANIC_CODE, status::PANIC);
        write_i32(control::STATUS, status::PANIC);
    }
    wait_forever()
}

fn clear_display() {
    unsafe {
        write_i32(gpu0::COLOR, 0);
        write_i32(gpu0::COMMAND, gpu0::COMMAND_CLEAR);
    }
}

fn print_bios_banner() {
    draw_display_line(8, 8, b"K16 BIOS");
    present_display();
}

fn print_no_bootable_device() {
    draw_display_line(8, 24, b"NO BOOTABLE DEVICE");
    present_display();
}

fn draw_display_line(x: i32, y: i32, bytes: &[u8]) {
    let mut column = 0;
    while column < bytes.len() {
        draw_display_glyph(x + (column as i32) * 8, y, glyph(bytes[column]));
        column += 1;
    }
}

fn draw_display_glyph(x: i32, y: i32, rows: [u8; 7]) {
    let mut pixels = [0u16; 8 * 8];
    // Keep this explicit for K16 firmware codegen: relying only on the stack
    // array initializer can leak pixels from the previous glyph blit.
    let mut pixel_index = 0;
    while pixel_index < pixels.len() {
        pixels[pixel_index] = 0;
        pixel_index += 1;
    }
    let mut row = 0;
    while row < rows.len() {
        let bits = rows[row];
        let mut column = 0;
        while column < 5 {
            if (bits & (1 << (4 - column))) != 0 {
                pixels[row * 8 + column] = 0x07e0;
            }
            column += 1;
        }
        row += 1;
    }
    unsafe {
        write_i32(gpu0::X, x);
        write_i32(gpu0::Y, y);
        write_i32(gpu0::RECT_WIDTH, 8);
        write_i32(gpu0::RECT_HEIGHT, 8);
        write_i32(gpu0::BUFFER_ADDR, pixels.as_ptr() as u32 as i32);
        write_i32(gpu0::BUFFER_STRIDE_BYTES, 16);
        write_i32(gpu0::COMMAND, gpu0::COMMAND_BLIT_BUFFER);
    }
}

fn present_display() {
    unsafe {
        write_i32(gpu0::COMMAND, gpu0::COMMAND_PRESENT);
    }
}

fn glyph(byte: u8) -> [u8; 7] {
    // Keep BIOS glyphs table-driven; the current K16 firmware path miscompiles
    // the equivalent match with inline array literals.
    let mut index = 0;
    while index < GLYPH_CODES.len() {
        if GLYPH_CODES[index] == byte {
            return GLYPH_ROWS[index];
        }
        index += 1;
    }
    FALLBACK_GLYPH
}

const FALLBACK_GLYPH: [u8; 7] = [0b11111, 0b00001, 0b00010, 0b00100, 0b00100, 0, 0b00100];

const GLYPH_CODES: [u8; 16] = [
    b'1', b'6', b'A', b'B', b'C', b'D', b'E', b'I', b'K', b'L', b'N', b'O', b'S', b'T', b'V', b' ',
];

const GLYPH_ROWS: [[u8; 7]; 16] = [
    [
        0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110,
    ],
    [
        0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110,
    ],
    [
        0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001,
    ],
    [
        0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110,
    ],
    [
        0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111,
    ],
    [
        0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110,
    ],
    [
        0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111,
    ],
    [
        0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111,
    ],
    [
        0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001,
    ],
    [
        0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111,
    ],
    [
        0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001,
    ],
    [
        0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110,
    ],
    [
        0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110,
    ],
    [
        0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100,
    ],
    [
        0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100,
    ],
    [0, 0, 0, 0, 0, 0, 0],
];

fn set_booting() {
    unsafe {
        write_i32(control::STATUS, status::BOOTING);
    }
}

fn print_bios_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'I');
    print_debug_byte(b'O');
    print_debug_byte(b'S');
    print_debug_byte(b'\n');
}

fn print_no_bootable_debug() {
    print_debug_byte(b'N');
    print_debug_byte(b'O');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'O');
    print_debug_byte(b'O');
    print_debug_byte(b'T');
    print_debug_byte(b'A');
    print_debug_byte(b'B');
    print_debug_byte(b'L');
    print_debug_byte(b'E');
    print_debug_byte(b' ');
    print_debug_byte(b'D');
    print_debug_byte(b'E');
    print_debug_byte(b'V');
    print_debug_byte(b'I');
    print_debug_byte(b'C');
    print_debug_byte(b'E');
    print_debug_byte(b'\n');
}

fn print_bios_panic_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'B');
    print_debug_byte(b'I');
    print_debug_byte(b'O');
    print_debug_byte(b'S');
    print_debug_byte(b' ');
    print_debug_byte(b'P');
    print_debug_byte(b'A');
    print_debug_byte(b'N');
    print_debug_byte(b'I');
    print_debug_byte(b'C');
    print_debug_byte(b'\n');
}

fn print_debug_byte(byte: u8) {
    unsafe {
        write_u8(debug::WRITE, byte);
    }
}

fn set_halted(code: i32) {
    unsafe {
        write_i32(control::PANIC_CODE, code);
        write_i32(control::STATUS, status::HALTED);
    }
}

unsafe fn write_i32(address: u32, value: i32) {
    unsafe { core::ptr::write_volatile(address as usize as *mut i32, value) }
}

unsafe fn write_u8(address: u32, value: u8) {
    unsafe { core::ptr::write_volatile(address as usize as *mut u8, value) }
}

fn wait_forever() -> ! {
    k16_rt::halt_forever()
}
