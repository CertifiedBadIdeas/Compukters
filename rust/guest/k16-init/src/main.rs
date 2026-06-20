#![no_std]
#![no_main]

use core::panic::PanicInfo;

use kraft_std::prelude::*;

const SHELL_PATH: &str = "/bin/shell.kx";
const SHELL_ARGS: [&str; 1] = [SHELL_PATH];

#[no_mangle]
pub extern "C" fn main() -> ! {
    loop {
        match run_shell_once() {
            Ok(status) if status.success() => {}
            Ok(status) => process::exit(status.code()),
            Err(_) => process::exit(1),
        }
    }
}

fn run_shell_once() -> Result<process::ExitStatus, process::Error> {
    let shell = process::spawn_with_args(SHELL_PATH, &SHELL_ARGS)?;
    let waited = process::wait(shell)?;
    Ok(waited.status())
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    process::exit(1)
}
