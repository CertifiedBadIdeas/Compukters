use k16_rt::cpu;

use crate::{control, debug, syscall, timer};

pub fn initialize() {
    let interrupt_mask = timer::register_driver();
    unsafe {
        k16_rt::install_trap_vector(kernel_trap_vector as *const () as usize as u32);
        k16_rt::set_interrupt_mask(interrupt_mask);
        k16_rt::enable_interrupts();
    }
}

extern "C" fn kernel_trap_vector() -> ! {
    let trap_cause = k16_rt::trap_cause();
    if cpu::trap_cause::is_interrupt(trap_cause) {
        dispatch_interrupt(cpu::trap_cause::interrupt_source(trap_cause));
        unsafe { k16_rt::iret_once() }
    }

    dispatch_synchronous_trap(trap_cause);
}

fn dispatch_interrupt(source: u32) {
    if timer::handles_interrupt(source) {
        timer::handle_interrupt();
        return;
    }

    kernel_trap();
}

fn dispatch_synchronous_trap(cause: u32) -> ! {
    if cause == cpu::trap_cause::EXPLICIT_TRAP {
        syscall::dispatch(k16_rt::trap_value());
    }

    kernel_trap();
}

pub fn kernel_trap() -> ! {
    debug::print_kernel_trap();
    control::set_panic();
    control::wait_forever();
}
