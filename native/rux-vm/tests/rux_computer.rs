use rux_vm::computer_machine::ComputerMachine;
use rux_vm::low_image::{encode_image, Function, Image, Instruction};
use rux_vm::low_image_runner::LowImageSignal;
use rux_vm::rux_computer::{RuxComputerControl, RuxComputerHandle};

fn terminal_firmware_image() -> Vec<u8> {
    let image = Image {
        memory_size: 64 * 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 4,
            parameters: Vec::new(),
            instructions: vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DEBUG_WRITE,
                },
                Instruction::I32Const { dst: 1, value: 82 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 85 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 1, value: 88 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
        }],
    };
    encode_image(&image).expect("test image encodes")
}

#[test]
fn rux_computer_handle_boots_firmware_and_exposes_machine_state() {
    let image = terminal_firmware_image();
    let mut handle =
        RuxComputerHandle::create(&image, 64 * 1024, 1_000_000).expect("computer handle creates");

    assert_eq!(
        handle.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0)
    );
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0,
        },
    );
}

#[test]
fn rux_computer_handle_fails_when_memory_is_too_small() {
    let image = terminal_firmware_image();
    let error: String = match RuxComputerHandle::create(&image, 128, 1_000_000) {
        Ok(_) => panic!("computer handle should reject undersized memory"),
        Err(error) => error,
    };

    assert!(
        error.contains("smaller than profile page size"),
        "unexpected error: {error}",
    );
}
