use ckl_vm::low_image::{Function, Image, Instruction};
use ckl_vm::low_image_runner::{low_opcode, LowImageSignal, LowImageVm};

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
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
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
                dst: 1,
                value: 0x11223344,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::Load32 { dst: 2, addr: 0 },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
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
            Instruction::ReturnI32 { src: 0 },
        ],
        1,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    let error = vm.run_until_signal().unwrap_err();

    assert!(
        error.contains("memory access 1022..1026 is outside 1024 bytes"),
        "{error}",
    );
}

#[test]
fn runner_calls_static_function_with_i32_arguments_and_return_value() {
    let image = Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 3,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 7 },
                    Instruction::I32Const { dst: 1, value: 5 },
                    Instruction::CallStatic {
                        return_register: Some(2),
                        function_index: 1,
                        arguments: vec![0, 1],
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
            Function {
                name: "add".to_string(),
                register_count: 3,
                parameters: vec![0, 1],
                instructions: vec![
                    Instruction::I32Add {
                        dst: 2,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
        ],
    };
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(12));
}

#[test]
fn runner_supports_recursive_static_calls() {
    let image = Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 2,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 6 },
                    Instruction::CallStatic {
                        return_register: Some(1),
                        function_index: 1,
                        arguments: vec![0],
                    },
                    Instruction::ReturnI32 { src: 1 },
                ],
            },
            Function {
                name: "fib".to_string(),
                register_count: 8,
                parameters: vec![0],
                instructions: vec![
                    Instruction::I32Const { dst: 1, value: 2 },
                    Instruction::I32Lt {
                        dst: 7,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::JumpIfFalse { cond: 7, target: 4 },
                    Instruction::ReturnI32 { src: 0 },
                    Instruction::I32Const { dst: 2, value: 1 },
                    Instruction::I32Sub {
                        dst: 3,
                        lhs: 0,
                        rhs: 2,
                    },
                    Instruction::CallStatic {
                        return_register: Some(4),
                        function_index: 1,
                        arguments: vec![3],
                    },
                    Instruction::I32Const { dst: 2, value: 2 },
                    Instruction::I32Sub {
                        dst: 3,
                        lhs: 0,
                        rhs: 2,
                    },
                    Instruction::CallStatic {
                        return_register: Some(5),
                        function_index: 1,
                        arguments: vec![3],
                    },
                    Instruction::I32Add {
                        dst: 6,
                        lhs: 4,
                        rhs: 5,
                    },
                    Instruction::ReturnI32 { src: 6 },
                ],
            },
        ],
    };
    let mut vm = LowImageVm::create(image, 4096).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(8));
}

#[test]
fn runner_records_execution_metrics() {
    let image = Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 3,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::AddrConst { dst: 0, value: 128 },
                    Instruction::I32Const { dst: 0, value: 7 },
                    Instruction::I32Const { dst: 1, value: 5 },
                    Instruction::Store32 { addr: 0, src: 0 },
                    Instruction::Load32 { dst: 0, addr: 0 },
                    Instruction::CallStatic {
                        return_register: Some(2),
                        function_index: 1,
                        arguments: vec![0, 1],
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
            Function {
                name: "add".to_string(),
                register_count: 3,
                parameters: vec![0, 1],
                instructions: vec![
                    Instruction::I32Add {
                        dst: 2,
                        lhs: 0,
                        rhs: 1,
                    },
                    Instruction::ReturnI32 { src: 2 },
                ],
            },
        ],
    };
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(12));

    let metrics = vm.metrics_snapshot();
    assert_eq!(metrics.executed_instructions, 9);
    assert_eq!(metrics.function_calls, 1);
    assert_eq!(metrics.function_returns, 2);
    assert_eq!(metrics.pause_signals, 0);
    assert_eq!(metrics.memory_loads, 1);
    assert_eq!(metrics.memory_stores, 1);
    assert_eq!(metrics.opcode_counts[low_opcode::I32_CONST], 2);
    assert_eq!(metrics.opcode_counts[low_opcode::ADDR_CONST], 1);
    assert_eq!(metrics.opcode_counts[low_opcode::STORE32], 1);
    assert_eq!(metrics.opcode_counts[low_opcode::LOAD32], 1);
    assert_eq!(metrics.opcode_counts[low_opcode::I32_ADD], 1);
    assert_eq!(metrics.opcode_counts[low_opcode::CALL_STATIC], 1);
    assert_eq!(metrics.opcode_counts[low_opcode::RETURN_I32], 2);
}

fn image(
    instructions: Vec<Instruction>,
    register_count: usize,
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
            register_count,
            parameters: Vec::new(),
            instructions,
        }],
    }
}
