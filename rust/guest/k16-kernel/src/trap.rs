use k16_rt::cpu;

use crate::{control, debug, fs, process, syscall, timer, trap_policy};

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

    match trap_policy::classify_synchronous_trap(
        cause,
        k16_rt::syscall_arg0(),
        k16_rt::syscall_arg1(),
    ) {
        trap_policy::SynchronousTrapAction::ExitCurrentChild(status) => {
            exit_current_child_after_user_fault(status)
        }
        trap_policy::SynchronousTrapAction::KernelTrap => unknown_synchronous_trap(cause),
    }
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

fn exit_current_child_after_user_fault(status: u32) -> ! {
    let exiting_pid = unsafe { process::current_process_slot() };
    unsafe { fs::close_file_fds_for_process(exiting_pid) };
    let mut resume = process::ParentResume::empty();
    if unsafe { process::finish_child_for_exit_into(status, &mut resume) }.is_ok() {
        if unsafe { process::destroy_exited_address_space(&resume) }.is_err() {
            control::set_panic();
            control::set_panic_code(k16_abi::syscall::ERROR_FAULT as i32);
            control::wait_forever()
        }
        unsafe { process::resume_parent_context(&resume) }
    }
    control::set_exit_code(status);
    control::set_halted();
    control::wait_forever()
}

fn enter_kernel_trap() -> ! {
    debug::print_kernel_trap();
    control::set_panic();
    control::wait_forever();
}
