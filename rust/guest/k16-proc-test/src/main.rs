#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const CAT_PATH: &str = "/bin/cat.kx";
const MOTD_PATH: &str = "/etc/motd";

#[no_mangle]
pub extern "C" fn main() -> ! {
    let stdout = io::stdout();
    match run_proc_test(stdout) {
        Ok(()) => process::exit(0),
        Err(status) => process::exit(status),
    }
}

fn run_proc_test(stdout: io::Fd) -> Result<(), u32> {
    let child = process::spawn_with_args(CAT_PATH, &[MOTD_PATH]).map_err(|_| 1_u32)?;
    let waited = process::wait(child).map_err(|_| 2_u32)?;
    if waited.pid() != child {
        return Err(3);
    }
    if !waited.status().success() {
        return Err(4);
    }
    stdout.write_all(b"PROC OK\n").map_err(|_| 5_u32)?;
    Ok(())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(255)
}
