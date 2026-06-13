use k16_rt::cpu;

use crate::{control, debug, syscall, timer};

pub fn initialize() {
    timer::register_driver();
    unsafe {
        k16_rt::install_trap_vector(kernel_trap_vector as *const () as usize as u32);
        k16_rt::set_interrupt_mask(0);
        k16_rt::disable_interrupts();
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

    unknown_interrupt(source);
}

fn dispatch_synchronous_trap(cause: u32) -> ! {
    if cause == cpu::trap_cause::EXPLICIT_TRAP {
        syscall::dispatch(k16_rt::trap_value());
    }

    unknown_synchronous_trap(cause);
}

fn unknown_interrupt(_source: u32) -> ! {
    enter_kernel_trap()
}

fn unknown_synchronous_trap(_cause: u32) -> ! {
    enter_kernel_trap()
}

pub fn unknown_syscall(_number: u32) -> ! {
    enter_kernel_trap()
}

fn enter_kernel_trap() -> ! {
    debug::print_kernel_trap();
    control::set_panic();
    control::wait_forever();
}
