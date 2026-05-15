use rux_vm::low_image::{Function, Image, Instruction};
use rux_vm::low_image_runner::{LowImageSignal, LowImageVm};
use rux_vm::low_machine::{MachineMemory, MemoryBus, MemoryFault};

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
fn runner_executes_i32_equality_comparison() {
    let equal = image(
        vec![
            Instruction::I32Const { dst: 0, value: 5 },
            Instruction::I32Const { dst: 1, value: 5 },
            Instruction::I32Eq {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
        3,
    );
    let not_equal = image(
        vec![
            Instruction::I32Const { dst: 0, value: 5 },
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::I32Eq {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
        3,
    );

    let mut equal_vm = LowImageVm::create(equal, 128).unwrap();
    let mut not_equal_vm = LowImageVm::create(not_equal, 128).unwrap();

    assert_eq!(
        equal_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltBool(true),
    );
    assert_eq!(
        not_equal_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltBool(false),
    );
}

#[test]
fn runner_executes_u32_less_than_comparison() {
    let high_not_less_than_low = image(
        vec![
            Instruction::I32Const {
                dst: 0,
                value: -65536,
            },
            Instruction::I32Const { dst: 1, value: 1 },
            Instruction::U32Lt {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
        3,
    );
    let low_less_than_high = image(
        vec![
            Instruction::I32Const { dst: 0, value: 1 },
            Instruction::I32Const {
                dst: 1,
                value: -65536,
            },
            Instruction::U32Lt {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnBool { src: 2 },
        ],
        3,
    );

    let mut high_vm = LowImageVm::create(high_not_less_than_low, 128).unwrap();
    let mut low_vm = LowImageVm::create(low_less_than_high, 128).unwrap();

    assert_eq!(
        high_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltBool(false),
    );
    assert_eq!(
        low_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltBool(true),
    );
}

#[test]
fn runner_executes_i32_remainder() {
    let image = image(
        vec![
            Instruction::I32Const { dst: 0, value: -13 },
            Instruction::I32Const { dst: 1, value: 5 },
            Instruction::I32Rem {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(-3));
}

#[test]
fn runner_executes_u32_division_and_remainder() {
    let image = image(
        vec![
            Instruction::I32Const { dst: 0, value: -2 },
            Instruction::I32Const { dst: 1, value: 3 },
            Instruction::U32Div {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::U32Rem {
                dst: 3,
                lhs: 0,
                rhs: 1,
            },
            Instruction::I32Add {
                dst: 4,
                lhs: 2,
                rhs: 3,
            },
            Instruction::ReturnI32 { src: 4 },
        ],
        5,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(
        vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(1_431_655_766)
    );
}

#[test]
fn runner_executes_i32_bitwise_and_or() {
    let image = image(
        vec![
            Instruction::I32Const {
                dst: 0,
                value: 0xff,
            },
            Instruction::I32Const {
                dst: 1,
                value: 0xf0,
            },
            Instruction::I32BitAnd {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::I32Const { dst: 3, value: 4 },
            Instruction::I32BitOr {
                dst: 4,
                lhs: 2,
                rhs: 3,
            },
            Instruction::ReturnI32 { src: 4 },
        ],
        5,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(
        vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0xf4)
    );
}

#[test]
fn runner_executes_unbounded_i32_shifts() {
    let left_shift_out_of_range = image(
        vec![
            Instruction::I32Const { dst: 0, value: 42 },
            Instruction::I32Const { dst: 1, value: 32 },
            Instruction::I32Shl {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );
    let arithmetic_right_shift_out_of_range = image(
        vec![
            Instruction::I32Const { dst: 0, value: -1 },
            Instruction::I32Const { dst: 1, value: 32 },
            Instruction::I32Shr {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );

    let mut shl_vm = LowImageVm::create(left_shift_out_of_range, 128).unwrap();
    let mut shr_vm = LowImageVm::create(arithmetic_right_shift_out_of_range, 128).unwrap();

    assert_eq!(
        shl_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0)
    );
    assert_eq!(
        shr_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(-1)
    );
}

#[test]
fn runner_executes_u32_logical_shifts() {
    let logical_right_shift = image(
        vec![
            Instruction::I32Const {
                dst: 0,
                value: i32::MIN,
            },
            Instruction::I32Const { dst: 1, value: 1 },
            Instruction::U32Shr {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );
    let right_shift_out_of_range = image(
        vec![
            Instruction::I32Const {
                dst: 0,
                value: i32::MIN,
            },
            Instruction::I32Const { dst: 1, value: 32 },
            Instruction::U32Shr {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );

    let mut logical_vm = LowImageVm::create(logical_right_shift, 128).unwrap();
    let mut out_of_range_vm = LowImageVm::create(right_shift_out_of_range, 128).unwrap();

    assert_eq!(
        logical_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0x40000000),
    );
    assert_eq!(
        out_of_range_vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0),
    );
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
fn runner_loads_and_stores_u16_in_linear_ram() {
    let image = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 129 },
            Instruction::I32Const {
                dst: 1,
                value: 0x0000_aabb,
            },
            Instruction::Store16 { addr: 0, src: 1 },
            Instruction::Load16 { dst: 2, addr: 0 },
            Instruction::ReturnI32 { src: 2 },
        ],
        3,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(
        vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI32(0x0000_aabb),
    );
    assert_eq!(vm.memory_bytes()[128..132], [0, 0xbb, 0xaa, 0]);
}

#[test]
fn runner_loads_and_stores_u64_in_linear_ram() {
    let image = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 129 },
            Instruction::U64Const {
                dst: 1,
                value: 0x1122_3344_5566_7788,
            },
            Instruction::Store64 { addr: 0, src: 1 },
            Instruction::Load64 { dst: 2, addr: 0 },
            Instruction::ReturnI64 { src: 2 },
        ],
        3,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(
        vm.run_until_signal().unwrap(),
        LowImageSignal::HaltI64(0x1122_3344_5566_7788),
    );
    assert_eq!(
        vm.memory_bytes()[128..138],
        [0, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0],
    );
}

#[test]
fn runner_executes_i64_arithmetic_and_bitwise_operations() {
    let image = image(
        vec![
            Instruction::U64Const {
                dst: 0,
                value: 0xffff_ffff_ffff_ffff,
            },
            Instruction::I64Const { dst: 1, value: 4 },
            Instruction::I64Shl {
                dst: 2,
                lhs: 1,
                rhs: 1,
            },
            Instruction::I64BitAnd {
                dst: 3,
                lhs: 0,
                rhs: 2,
            },
            Instruction::I64BitOr {
                dst: 4,
                lhs: 3,
                rhs: 1,
            },
            Instruction::I64BitXor {
                dst: 5,
                lhs: 4,
                rhs: 1,
            },
            Instruction::I64Add {
                dst: 6,
                lhs: 5,
                rhs: 1,
            },
            Instruction::I64Sub {
                dst: 7,
                lhs: 6,
                rhs: 1,
            },
            Instruction::I64Mul {
                dst: 8,
                lhs: 7,
                rhs: 1,
            },
            Instruction::ReturnI64 { src: 8 },
        ],
        9,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI64(256));
}

#[test]
fn runner_executes_i64_and_u64_division_remainder_and_comparison() {
    let image = image(
        vec![
            Instruction::I64Const { dst: 0, value: -9 },
            Instruction::I64Const { dst: 1, value: 2 },
            Instruction::I64Div {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::I64Rem {
                dst: 3,
                lhs: 0,
                rhs: 1,
            },
            Instruction::U64Const {
                dst: 4,
                value: u64::MAX,
            },
            Instruction::U64Div {
                dst: 5,
                lhs: 4,
                rhs: 1,
            },
            Instruction::U64Rem {
                dst: 6,
                lhs: 4,
                rhs: 1,
            },
            Instruction::I64Lt {
                dst: 7,
                lhs: 4,
                rhs: 1,
            },
            Instruction::U64Lt {
                dst: 8,
                lhs: 4,
                rhs: 1,
            },
            Instruction::I64Eq {
                dst: 9,
                lhs: 3,
                rhs: 4,
            },
            Instruction::I32Const { dst: 10, value: 1 },
            Instruction::I32BitAnd {
                dst: 11,
                lhs: 7,
                rhs: 10,
            },
            Instruction::I32BitAnd {
                dst: 12,
                lhs: 8,
                rhs: 10,
            },
            Instruction::I32Add {
                dst: 13,
                lhs: 11,
                rhs: 12,
            },
            Instruction::I32BitAnd {
                dst: 14,
                lhs: 9,
                rhs: 10,
            },
            Instruction::I32Add {
                dst: 15,
                lhs: 13,
                rhs: 14,
            },
            Instruction::ReturnI32 { src: 15 },
        ],
        16,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(2));
}

#[test]
fn runner_executes_i64_and_u64_shifts_and_casts() {
    let image = image(
        vec![
            Instruction::I32Const { dst: 0, value: -1 },
            Instruction::I32ToI64 { dst: 1, src: 0 },
            Instruction::U32ToU64 { dst: 2, src: 0 },
            Instruction::I64Const { dst: 3, value: 63 },
            Instruction::I64Shr {
                dst: 4,
                lhs: 1,
                rhs: 3,
            },
            Instruction::U64Shr {
                dst: 5,
                lhs: 1,
                rhs: 3,
            },
            Instruction::I64Const { dst: 8, value: 1 },
            Instruction::U64Shl {
                dst: 9,
                lhs: 8,
                rhs: 3,
            },
            Instruction::U64Shr {
                dst: 10,
                lhs: 9,
                rhs: 3,
            },
            Instruction::I64Add {
                dst: 6,
                lhs: 2,
                rhs: 5,
            },
            Instruction::I64Add {
                dst: 6,
                lhs: 6,
                rhs: 10,
            },
            Instruction::I64ToI32 { dst: 7, src: 6 },
            Instruction::ReturnI32 { src: 7 },
        ],
        11,
    );
    let mut vm = LowImageVm::create(image, 128).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(1));
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
fn runner_rejects_fallthrough_past_last_instruction() {
    let image = image(vec![Instruction::I32Const { dst: 0, value: 7 }], 1);

    let error = create_error(image);

    assert!(
        error.contains("function main must end with Jump or Return* instruction"),
        "{error}",
    );
}

#[test]
fn runner_rejects_jump_to_instruction_count() {
    let image = image(vec![Instruction::Jump { target: 1 }], 1);

    let error = create_error(image);

    assert!(
        error.contains("function main instruction 0 jump target 1 is outside instruction count 1"),
        "{error}",
    );
}

#[test]
fn runner_rejects_invalid_register_indices_at_create_time() {
    let image = image(
        vec![
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::ReturnUnit,
        ],
        1,
    );

    let error = create_error(image);

    assert!(
        error.contains("function main instruction 0 writes register 1 outside register count 1"),
        "{error}",
    );
}

#[test]
fn runner_rejects_invalid_call_target_at_create_time() {
    let image = image(
        vec![
            Instruction::CallStatic {
                return_register: None,
                function_index: 1,
                arguments: Vec::new(),
            },
            Instruction::ReturnUnit,
        ],
        1,
    );

    let error = create_error(image);

    assert!(
        error.contains("function main instruction 0 calls function 1 outside function count 1"),
        "{error}",
    );
}

#[test]
fn runner_rejects_static_call_argument_count_at_create_time() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 1,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::CallStatic {
                        return_register: None,
                        function_index: 1,
                        arguments: vec![0],
                    },
                    Instruction::ReturnUnit,
                ],
            },
            Function {
                name: "callee".to_string(),
                register_count: 1,
                parameters: vec![0, 0],
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    };

    let error = create_error(image);

    assert!(
        error.contains("function main instruction 0 calls function callee with 1 arguments but callee expects 2"),
        "{error}",
    );
}

#[test]
fn runner_rejects_invalid_parameter_register_at_create_time() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 0,
                parameters: Vec::new(),
                instructions: vec![Instruction::ReturnUnit],
            },
            Function {
                name: "callee".to_string(),
                register_count: 1,
                parameters: vec![1],
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    };

    let error = create_error(image);

    assert!(
        error.contains("function callee parameter 0 register 1 outside register count 1"),
        "{error}",
    );
}

#[test]
fn runner_rejects_entry_function_parameters_at_create_time() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 1,
            parameters: vec![0],
            instructions: vec![Instruction::ReturnUnit],
        }],
    };

    let error = create_error(image);

    assert!(
        error.contains("entry function main must not declare parameters"),
        "{error}",
    );
}

#[test]
fn runner_rejects_invalid_return_register_at_create_time() {
    let image = Image {
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 1,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::CallStatic {
                        return_register: Some(1),
                        function_index: 1,
                        arguments: Vec::new(),
                    },
                    Instruction::ReturnUnit,
                ],
            },
            Function {
                name: "callee".to_string(),
                register_count: 0,
                parameters: Vec::new(),
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    };

    let error = create_error(image);

    assert!(
        error.contains("function main instruction 0 return register 1 outside register count 1"),
        "{error}",
    );
}

#[test]
fn runner_calls_static_function_with_i32_arguments_and_return_value() {
    let image = Image {
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
    assert_eq!(metrics.run_invocations, 1);
    assert!(metrics.elapsed_nanos > 0, "{metrics:?}");
    assert_eq!(metrics.pause_signals, 0);
}

#[test]
fn runner_pauses_after_time_slice_budget() {
    let image = image(vec![Instruction::Jump { target: 0 }], 0);
    let mut vm = LowImageVm::create(image, 1).unwrap();

    assert_eq!(vm.run_until_signal().unwrap(), LowImageSignal::Pause);

    let metrics = vm.metrics_snapshot();
    assert_eq!(metrics.run_invocations, 1);
    assert_eq!(metrics.pause_signals, 1);
    assert!(metrics.elapsed_nanos > 0, "{metrics:?}");
}

#[test]
fn runner_can_be_created_with_external_machine_memory() {
    let writer = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 1, value: 77 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );
    let reader = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::ReturnI32 { src: 1 },
        ],
        2,
    );
    let mut memory = rux_vm::low_machine::MachineMemory::zeroed(1024).unwrap();
    {
        let mut writer_vm = LowImageVm::create_cpu_with_memory(writer, 128, &mut memory).unwrap();
        assert_eq!(
            writer_vm.run_until_signal().unwrap(),
            LowImageSignal::HaltUnit,
        );
    }
    {
        let mut reader_vm = LowImageVm::create_cpu_with_memory(reader, 128, &mut memory).unwrap();
        assert_eq!(
            reader_vm.run_until_signal().unwrap(),
            LowImageSignal::HaltI32(77),
        );
    }
}

#[test]
fn cpu_context_runs_against_shared_memory_without_owning_it() {
    let writer = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 1, value: 41 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );
    let reader = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::I32Const { dst: 2, value: 1 },
            Instruction::I32Add {
                dst: 3,
                lhs: 1,
                rhs: 2,
            },
            Instruction::ReturnI32 { src: 3 },
        ],
        4,
    );
    let mut memory = MachineMemory::zeroed(1024).unwrap();
    let mut writer_cpu = LowImageVm::create_cpu_context(writer, 128).unwrap();
    let mut reader_cpu = LowImageVm::create_cpu_context(reader, 128).unwrap();

    assert_eq!(
        writer_cpu.run_until_signal(&mut memory).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(
        reader_cpu.run_until_signal(&mut memory).unwrap(),
        LowImageSignal::HaltI32(42),
    );
}

#[test]
fn runner_can_execute_against_custom_memory_bus() {
    struct RecordingBus {
        memory: MachineMemory,
        stores: Vec<(u32, i32)>,
    }

    impl MemoryBus for RecordingBus {
        fn len(&self) -> usize {
            self.memory.len()
        }

        fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
            self.memory.load_i32(address)
        }

        fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
            self.stores.push((address, value));
            self.memory.store_i32(address, value)
        }

        fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
            self.memory.load_u8(address)
        }

        fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
            self.memory.store_u8(address, value)
        }
    }

    let image = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 1, value: 456 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );
    let mut bus = RecordingBus {
        memory: MachineMemory::zeroed(1024).unwrap(),
        stores: Vec::new(),
    };

    let mut cpu = LowImageVm::create_cpu_with_bus(image, 128, &mut bus).unwrap();

    assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
    drop(cpu);
    assert_eq!(bus.stores, vec![(128, 456)]);
    assert_eq!(bus.memory.load_i32(128).unwrap(), 456);
}

fn image(instructions: Vec<Instruction>, register_count: u16) -> Image {
    Image {
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

fn create_error(image: Image) -> String {
    match LowImageVm::create(image, 128) {
        Ok(_) => panic!("expected low VM image creation to fail"),
        Err(error) => error,
    }
}
