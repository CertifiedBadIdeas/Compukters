use ckl_vm::low_image::{Function, Image, Instruction};
use ckl_vm::low_image_runner::{LowImageSignal, LowImageVm};
use ckl_vm::low_machine::{MachineMemory, MemoryBus, MemoryFault};

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
        language_version: "ckl-low-1".to_string(),
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
        language_version: "ckl-low-1".to_string(),
        memory_size: 1024,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![Function {
            name: "main".to_string(),
            register_count: 1,
            parameters: vec![1],
            instructions: vec![Instruction::ReturnUnit],
        }],
    };

    let error = create_error(image);

    assert!(
        error.contains("function main parameter 0 register 1 outside register count 1"),
        "{error}",
    );
}

#[test]
fn runner_rejects_invalid_return_register_at_create_time() {
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
    let mut memory = ckl_vm::low_machine::MachineMemory::zeroed(1024).unwrap();
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

fn image(instructions: Vec<Instruction>, register_count: usize) -> Image {
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

fn create_error(image: Image) -> String {
    match LowImageVm::create(image, 128) {
        Ok(_) => panic!("expected low VM image creation to fail"),
        Err(error) => error,
    }
}
