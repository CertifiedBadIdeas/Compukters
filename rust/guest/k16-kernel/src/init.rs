use k16_boot_chain::{
    enter_loaded_image, load_k16e_from_storage0, user_memory_end_from_current_boot_info,
    K16eAbiKind, LoadError,
};

use crate::{control, debug, process};

pub fn launch() -> ! {
    let image = unsafe {
        load_k16e_from_storage0(
            b"ROOT",
            &[b"bin".as_slice(), b"init.kx".as_slice()],
            K16eAbiKind::Program,
        )
    };
    match image {
        Ok(image) => {
            let memory_end = unsafe { user_memory_end_from_current_boot_info(image) };
            match memory_end {
                Ok(memory_end) => {
                    if unsafe { process::initialize_init_process(image, memory_end) }.is_err() {
                        fail(LoadError::INVALID_EXECUTABLE);
                    }
                }
                Err(error) => fail(error),
            }
            unsafe { enter_loaded_image(image) }
        }
        Err(error) => fail(error),
    }
}

fn fail(error: LoadError) -> ! {
    debug::print_byte(b'!');
    control::set_panic_code(error.code());
    control::set_halted();
    control::wait_forever()
}
