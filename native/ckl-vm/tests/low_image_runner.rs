use ckl_vm::low_image::{Function, Image, Instruction, Register};
use ckl_vm::low_image_runner::{LowImageSignal, LowImageVm};

#[test]
fn runner_executes_i32_arithmetic_without_value_objects() {
    let image = image(
        vec![
            Instruction::I32Const { dst: 0, value: 2 },
            Instruction::I32Const { dst: 1, value: 5 },
            Instruction::I32Add {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::Return {
                src: Register::I32(2),
            },
        ],
        3,
        0,
        0,
        0,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(7));
}

#[test]
fn runner_loads_and_stores_i32_in_linear_ram() {
    let image = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const {
                dst: 0,
                value: 0x11223344,
            },
            Instruction::Store32 { addr: 0, src: 0 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::Return {
                src: Register::I32(1),
            },
        ],
        2,
        0,
        1,
        0,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(
        vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0x11223344),
    );
    assert_eq!(vm.memory_bytes()[128..132], [0x44, 0x33, 0x22, 0x11]);
}

#[test]
fn runner_rejects_out_of_bounds_memory_access() {
    let image = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: 1022,
            },
            Instruction::Load32 { dst: 0, addr: 0 },
            Instruction::Return {
                src: Register::I32(0),
            },
        ],
        1,
        0,
        1,
        0,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    let error = vm.run_until_signal().unwrap_err();

    assert!(
        error.contains("memory access 1022..1026 is outside 1024 bytes"),
        "{error}",
    );
}

fn image(
    instructions: Vec<Instruction>,
    i32_count: usize,
    i64_count: usize,
    addr_count: usize,
    bool_count: usize,
) -> Image {
    Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            i32_register_count: i32_count,
            i64_register_count: i64_count,
            addr_register_count: addr_count,
            bool_register_count: bool_count,
            parameters: Vec::new(),
            instructions,
        }],
    }
}
