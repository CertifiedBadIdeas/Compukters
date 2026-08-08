use crate::boot_chain::LoadError;

use crate::{control, debug, process};

const INIT_PROGRAM_PATH: &[u8] = b"/bin/init.kx";

pub fn launch() -> ! {
    if let Err(error) = unsafe { crate::vfs::initialize() } {
        fail_vfs_init(error);
    }
    let boot_info = unsafe { k16_abi::computer::profile::read_boot_info() };
    match boot_info {
        Some(boot_info) => {
            let launch = unsafe {
                process::begin_translated_init_from_storage0(INIT_PROGRAM_PATH, boot_info)
            };
            match launch {
                Ok(launch) => unsafe { process::enter_child_context(launch) },
                Err(error) => fail_process_load(error),
            }
        }
        None => fail(LoadError::INVALID_BOOT_INFO),
    }
}

fn fail_vfs_init(error: crate::vfs::VfsInitError) -> ! {
    match error {
        crate::vfs::VfsInitError::InvalidStorage1Profile => {
            fail(LoadError::INVALID_STORAGE1_PROFILE)
        }
        crate::vfs::VfsInitError::InvalidSdkFilesystem(_) => {
            fail(LoadError::INVALID_SDK_FILESYSTEM)
        }
        crate::vfs::VfsInitError::AlreadyInitialized => fail(LoadError::INVALID_BOOT_INFO),
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
