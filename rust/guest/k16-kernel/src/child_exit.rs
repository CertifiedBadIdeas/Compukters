use k16_abi::syscall as abi_syscall;

use crate::{control, fs, process, user_buffer};

pub fn complete_child_exit(status: u32) -> ! {
    let exiting_pid = unsafe { process::current_process_slot() };
    unsafe { fs::close_file_fds_for_process(exiting_pid) };
    let mut resume = process::ParentResume::empty();
    if unsafe { process::finish_child_for_exit_into(status, &mut resume) }.is_ok() {
        if resume.wait_status_ptr != 0 && write_wait_status(resume.wait_status_ptr, status).is_err()
        {
            resume.wait_status_ptr = 0;
            resume.child_exit_status = abi_syscall::ERROR_FAULT;
        }
        if unsafe { process::destroy_exited_address_space(&resume) }.is_err() {
            control::set_panic();
            control::set_panic_code(abi_syscall::ERROR_FAULT as i32);
            control::wait_forever()
        }
        unsafe { process::resume_parent_context(&resume) }
    }
    control::set_exit_code(status);
    control::set_halted();
    control::wait_forever()
}

fn write_wait_status(out_status: u32, status: u32) -> Result<(), u32> {
    user_buffer::copy_to_user(out_status, &status.to_le_bytes()).map(|_| ())
}
