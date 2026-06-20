#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const BAD_PTR: usize = 0xffff_f000;

#[no_mangle]
pub extern "C" fn main() -> ! {
    match run() {
        Ok(()) => process::exit(k16_abi::syscall::ERROR_FAULT),
        Err(status) => process::exit(status),
    }
}

fn run() -> Result<(), u32> {
    let bad_const = BAD_PTR as *const u8;
    let bad_mut = BAD_PTR as *mut u8;

    expect_fault(k16_rt::write_syscall(
        k16_abi::syscall::FD_STDOUT,
        bad_const,
        1,
    ))?;
    let fd = k16_rt::open_syscall(b"/etc/motd".as_ptr(), 9, k16_abi::syscall::OPEN_READ_ONLY);
    if fd & 0x8000_0000 != 0 {
        return Err(1);
    }
    expect_fault(k16_rt::read_syscall(fd, bad_mut, 1))?;
    let _ = k16_rt::close_syscall(fd);
    expect_fault(k16_rt::open_syscall(
        bad_const,
        1,
        k16_abi::syscall::OPEN_READ_ONLY,
    ))?;
    expect_fault(k16_rt::stat_syscall(b"/".as_ptr(), 1, bad_mut))?;
    expect_fault(k16_rt::game_ticks_syscall(bad_mut))?;
    expect_fault(k16_rt::read_dir_syscall(bad_const, 16))?;
    expect_fault(k16_rt::rename_syscall(bad_const, 12))?;
    expect_fault(k16_rt::unlink_syscall(bad_const, 1))?;
    expect_fault(k16_rt::mkdir_syscall(bad_const, 1))?;
    expect_fault(k16_rt::rmdir_syscall(bad_const, 1))?;
    expect_fault(k16_rt::run_syscall(bad_const, 1))?;
    expect_fault(k16_rt::spawn_argv_syscall(bad_const, 1))?;

    io::stdout()
        .write_all(b"SYSCALL FAULTS OK\n")
        .map_err(|_| 1u32)?;
    Ok(())
}

fn expect_fault(status: u32) -> Result<(), u32> {
    if status == k16_abi::syscall::ERROR_FAULT {
        Ok(())
    } else {
        Err(1)
    }
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
