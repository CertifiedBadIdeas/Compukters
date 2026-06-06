#![no_std]
#![no_main]

extern crate k16_rt;

use core::panic::PanicInfo;
use k16_abi::computer::{control, debug, display0, hardware_id, profile, status};
use k16_rt::cpu;

static mut KERNEL_TIMER0_TICKS: u32 = 0;
static mut TIMER0_IRQ_SOURCE: u32 = 0;

#[no_mangle]
pub extern "C" fn _start() -> ! {
    clear_display();
    print_kernel_ok_display();
    print_kernel_ok_debug();
    install_timer0_interrupt_handler();
    set_ready();
    idle_forever()
}

extern "C" fn timer0_interrupt_handler() -> ! {
    let trap_cause = k16_rt::trap_cause();
    let timer0_irq_source = unsafe { TIMER0_IRQ_SOURCE };
    if !cpu::trap_cause::is_interrupt(trap_cause)
        || cpu::trap_cause::interrupt_source(trap_cause) != timer0_irq_source
    {
        print_kernel_trap_debug();
        set_panic();
        wait_forever();
    }

    unsafe {
        KERNEL_TIMER0_TICKS = KERNEL_TIMER0_TICKS.wrapping_add(1);
    }
    print_debug_byte(b'|');
    unsafe { k16_rt::iret_once() }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    print_kernel_panic_debug();
    set_panic();
    wait_forever()
}

fn clear_display() {
    unsafe {
        write_i32(display0::COMMAND, display0::COMMAND_CLEAR);
    }
}

fn print_kernel_ok_display() {
    unsafe {
        write_i32(display0::CURSOR_X, 0);
        write_i32(display0::CURSOR_Y, 0);
    }
    print_display_byte(b'K');
    print_display_byte(b'E');
    print_display_byte(b'R');
    print_display_byte(b'N');
    print_display_byte(b'E');
    print_display_byte(b'L');
    print_display_byte(b' ');
    print_display_byte(b'O');
    print_display_byte(b'K');
}

fn print_display_byte(byte: u8) {
    unsafe {
        write_u8(display0::DATA, byte);
        write_i32(display0::COMMAND, display0::COMMAND_PUT_BYTE_AT_CURSOR);
    }
}

fn print_kernel_ok_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'E');
    print_debug_byte(b'R');
    print_debug_byte(b'N');
    print_debug_byte(b'E');
    print_debug_byte(b'L');
    print_debug_byte(b' ');
    print_debug_byte(b'O');
    print_debug_byte(b'K');
    print_debug_byte(b'\n');
}

fn print_kernel_panic_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'K');
    print_debug_byte(b'E');
    print_debug_byte(b'R');
    print_debug_byte(b'N');
    print_debug_byte(b'E');
    print_debug_byte(b'L');
    print_debug_byte(b' ');
    print_debug_byte(b'P');
    print_debug_byte(b'A');
    print_debug_byte(b'N');
    print_debug_byte(b'I');
    print_debug_byte(b'C');
    print_debug_byte(b'\n');
}

fn print_kernel_trap_debug() {
    print_debug_byte(b'K');
    print_debug_byte(b'1');
    print_debug_byte(b'6');
    print_debug_byte(b' ');
    print_debug_byte(b'K');
    print_debug_byte(b'E');
    print_debug_byte(b'R');
    print_debug_byte(b'N');
    print_debug_byte(b'E');
    print_debug_byte(b'L');
    print_debug_byte(b' ');
    print_debug_byte(b'T');
    print_debug_byte(b'R');
    print_debug_byte(b'A');
    print_debug_byte(b'P');
    print_debug_byte(b'\n');
}

fn print_debug_byte(byte: u8) {
    unsafe {
        write_u8(debug::WRITE, byte);
    }
}

fn install_timer0_interrupt_handler() {
    let timer0_irq_source = timer0_irq_source_or_panic();
    unsafe {
        TIMER0_IRQ_SOURCE = timer0_irq_source;
        k16_rt::install_trap_vector(timer0_interrupt_handler as *const () as usize as u32);
        k16_rt::set_interrupt_mask(timer0_irq_source);
        k16_rt::enable_interrupts();
    }
}

fn timer0_irq_source_or_panic() -> u32 {
    let timer0 = unsafe { profile::find_hardware_entry(hardware_id::TIMER0) };
    let Some(timer0) = timer0 else {
        print_kernel_panic_debug();
        set_panic();
        wait_forever();
    };
    if timer0.irq_source == 0 {
        print_kernel_panic_debug();
        set_panic();
        wait_forever();
    }
    timer0.irq_source
}

fn set_ready() {
    unsafe {
        write_i32(control::PANIC_CODE, 0);
        write_i32(control::STATUS, status::READY);
    }
}

fn set_panic() {
    unsafe {
        write_i32(control::PANIC_CODE, status::PANIC);
        write_i32(control::STATUS, status::PANIC);
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

fn idle_forever() -> ! {
    loop {
        k16_rt::yield_once();
    }
}
