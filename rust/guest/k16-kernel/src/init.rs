use k16_boot_chain::LoadError;

use crate::{control, debug, process};

pub fn launch() -> ! {
    let boot_info = unsafe { k16_abi::computer::profile::read_boot_info() };
    match boot_info {
        Some(boot_info) => {
            let launch =
                unsafe { process::begin_translated_init_from_storage0(b"/bin/init.kx", boot_info) };
            match launch {
                Ok(launch) => unsafe { process::enter_child_context(launch) },
                Err(error) => fail_process_load(error),
            }
        }
        None => fail(LoadError::INVALID_BOOT_INFO),
    }
}

fn fail_process_load(error: process::ProcessLoadError) -> ! {
    let load_error = match error {
        process::ProcessLoadError::InvalidPath => LoadError::PATH_NOT_FOUND,
        process::ProcessLoadError::InvalidImage => LoadError::INVALID_EXECUTABLE,
        process::ProcessLoadError::AddressOverflow
        | process::ProcessLoadError::InvalidArena
        | process::ProcessLoadError::ProgramTooLarge => LoadError::INVALID_BOOT_INFO,
        process::ProcessLoadError::Storage => LoadError::PATH_NOT_FOUND,
    };
    fail(load_error)
}

fn fail(error: LoadError) -> ! {
    debug::print_byte(b'!');
    control::set_panic_code(error.code());
    control::set_halted();
    control::wait_forever()
}
