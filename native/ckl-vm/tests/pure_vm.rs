use ckl_vm::abi::{Class, ClassField, Function, Instruction, Local, MethodRef, Module};
use ckl_vm::value::VmValue;
use ckl_vm::vm::{VmInstance, VmSignal};

#[test]
fn executes_integer_addition_and_return() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Int".to_string(),
            instructions: vec![
                Instruction::PushInt(1),
                Instruction::PushInt(2),
                Instruction::Binary(0),
                Instruction::Return,
            ],
        }],
    };

    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(3))
    );
}

#[test]
fn evaluates_greater_equals_binary_operator() {
    let mut vm = VmInstance::new(
        binary_module(
            vec![
                Instruction::PushInt(2),
                Instruction::PushInt(1),
                Instruction::Binary(9),
            ],
            "Bool",
        ),
        64,
    );

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Bool(true))
    );
}

#[test]
fn evaluates_integer_binary_operator_subset() {
    let cases = vec![
        (
            vec![
                Instruction::PushInt(5),
                Instruction::PushInt(3),
                Instruction::Binary(1),
            ],
            "Int",
            VmValue::Int(2),
        ),
        (
            vec![
                Instruction::PushInt(5),
                Instruction::PushInt(3),
                Instruction::Binary(2),
            ],
            "Int",
            VmValue::Int(15),
        ),
        (
            vec![
                Instruction::PushInt(6),
                Instruction::PushInt(3),
                Instruction::Binary(3),
            ],
            "Int",
            VmValue::Int(2),
        ),
        (
            vec![
                Instruction::PushInt(6),
                Instruction::PushInt(6),
                Instruction::Binary(4),
            ],
            "Bool",
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushInt(6),
                Instruction::PushInt(5),
                Instruction::Binary(5),
            ],
            "Bool",
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushInt(4),
                Instruction::PushInt(5),
                Instruction::Binary(6),
            ],
            "Bool",
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushInt(5),
                Instruction::PushInt(5),
                Instruction::Binary(7),
            ],
            "Bool",
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushInt(6),
                Instruction::PushInt(5),
                Instruction::Binary(8),
            ],
            "Bool",
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushInt(0b1100),
                Instruction::PushInt(0b1010),
                Instruction::Binary(12),
            ],
            "Int",
            VmValue::Int(0b1000),
        ),
        (
            vec![
                Instruction::PushInt(0b1100),
                Instruction::PushInt(0b1010),
                Instruction::Binary(13),
            ],
            "Int",
            VmValue::Int(0b1110),
        ),
        (
            vec![
                Instruction::PushInt(0b1100),
                Instruction::PushInt(0b1010),
                Instruction::Binary(14),
            ],
            "Int",
            VmValue::Int(0b0110),
        ),
        (
            vec![
                Instruction::PushInt(1),
                Instruction::PushInt(3),
                Instruction::Binary(15),
            ],
            "Int",
            VmValue::Int(8),
        ),
        (
            vec![
                Instruction::PushInt(8),
                Instruction::PushInt(1),
                Instruction::Binary(16),
            ],
            "Int",
            VmValue::Int(4),
        ),
    ];

    for (instructions, return_type, expected) in cases {
        let mut vm = VmInstance::new(binary_module(instructions, return_type), 64);

        assert_eq!(vm.run_until_signal().unwrap(), VmSignal::Halt(expected));
    }
}

#[test]
fn evaluates_boolean_binary_operators() {
    let cases = vec![
        (
            vec![
                Instruction::PushBool(true),
                Instruction::PushBool(true),
                Instruction::Binary(10),
            ],
            VmValue::Bool(true),
        ),
        (
            vec![
                Instruction::PushBool(false),
                Instruction::PushBool(true),
                Instruction::Binary(11),
            ],
            VmValue::Bool(true),
        ),
    ];

    for (instructions, expected) in cases {
        let mut vm = VmInstance::new(binary_module(instructions, "Bool"), 64);

        assert_eq!(vm.run_until_signal().unwrap(), VmSignal::Halt(expected));
    }
}

#[test]
fn executes_control_flow_jumps_and_unary_operators() {
    let module = single_function_module(
        "main",
        "Int",
        vec![
            Local {
                name: "i".to_string(),
                type_name: "Int".to_string(),
            },
            Local {
                name: "sum".to_string(),
                type_name: "Int".to_string(),
            },
        ],
        vec![
            Instruction::PushInt(0),
            Instruction::StoreLocal(0),
            Instruction::PushInt(0),
            Instruction::StoreLocal(1),
            Instruction::LoadLocal(0),
            Instruction::PushInt(3),
            Instruction::Binary(6),
            Instruction::JumpIfFalse(17),
            Instruction::LoadLocal(1),
            Instruction::LoadLocal(0),
            Instruction::Binary(0),
            Instruction::StoreLocal(1),
            Instruction::LoadLocal(0),
            Instruction::PushInt(1),
            Instruction::Binary(0),
            Instruction::StoreLocal(0),
            Instruction::Jump(4),
            Instruction::LoadLocal(1),
            Instruction::Return,
        ],
    );
    let mut vm = VmInstance::new(module, 128);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(3))
    );

    let module = single_function_module(
        "main",
        "Int",
        vec![],
        vec![
            Instruction::PushBool(true),
            Instruction::JumpIfTrue(4),
            Instruction::PushInt(0),
            Instruction::Return,
            Instruction::PushInt(9),
            Instruction::Unary(0),
            Instruction::Return,
        ],
    );
    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(-9))
    );
}

#[test]
fn constructs_records_and_reads_fields() {
    let module = single_function_module(
        "main",
        "String",
        vec![],
        vec![
            Instruction::PushInt(1),
            Instruction::PushString("ok".to_string()),
            Instruction::ConstructRecord {
                type_name: "Pair".to_string(),
                field_names: vec!["a".to_string(), "b".to_string()],
            },
            Instruction::GetField("b".to_string()),
            Instruction::Return,
        ],
    );
    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::String("ok".to_string()))
    );
}

#[test]
fn constructs_collections_indexes_and_calls_collection_methods() {
    let module = single_function_module(
        "main",
        "Int",
        vec![
            Local {
                name: "array".to_string(),
                type_name: "Array<Int>".to_string(),
            },
            Local {
                name: "list".to_string(),
                type_name: "List<Int>".to_string(),
            },
            Local {
                name: "map".to_string(),
                type_name: "Map<String, Int>".to_string(),
            },
        ],
        vec![
            Instruction::PushInt(3),
            Instruction::PushInt(0),
            Instruction::ConstructArray,
            Instruction::StoreLocal(0),
            Instruction::LoadLocal(0),
            Instruction::PushInt(1),
            Instruction::PushInt(7),
            Instruction::IndexSet,
            Instruction::Pop,
            Instruction::PushInt(1),
            Instruction::PushInt(2),
            Instruction::ConstructList(2),
            Instruction::StoreLocal(1),
            Instruction::LoadLocal(1),
            Instruction::PushInt(3),
            Instruction::CallCollectionMethod {
                method_name: "add".to_string(),
                argument_count: 1,
            },
            Instruction::Pop,
            Instruction::PushString("a".to_string()),
            Instruction::PushInt(4),
            Instruction::ConstructMap(1),
            Instruction::StoreLocal(2),
            Instruction::LoadLocal(2),
            Instruction::PushString("b".to_string()),
            Instruction::PushInt(5),
            Instruction::CallCollectionMethod {
                method_name: "set".to_string(),
                argument_count: 2,
            },
            Instruction::Pop,
            Instruction::LoadLocal(0),
            Instruction::PushInt(1),
            Instruction::IndexGet,
            Instruction::LoadLocal(1),
            Instruction::PushInt(2),
            Instruction::CallCollectionMethod {
                method_name: "get".to_string(),
                argument_count: 1,
            },
            Instruction::Binary(0),
            Instruction::LoadLocal(2),
            Instruction::PushString("b".to_string()),
            Instruction::CallCollectionMethod {
                method_name: "get".to_string(),
                argument_count: 1,
            },
            Instruction::Binary(0),
            Instruction::Return,
        ],
    );
    let mut vm = VmInstance::new(module, 128);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(15))
    );
}

#[test]
fn constructs_classes_and_dispatches_methods() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![Class {
            name: "Counter".to_string(),
            fields: vec![ClassField {
                name: "value".to_string(),
                type_name: "Int".to_string(),
                mutable: true,
            }],
            init_function_index: None,
            instance_methods: vec![MethodRef {
                name: "inc".to_string(),
                function_index: 1,
            }],
            static_methods: vec![MethodRef {
                name: "answer".to_string(),
                function_index: 2,
            }],
        }],
        functions: vec![
            Function {
                name: "main".to_string(),
                parameters: vec![],
                locals: vec![Local {
                    name: "counter".to_string(),
                    type_name: "Counter".to_string(),
                }],
                return_type: "Int".to_string(),
                instructions: vec![
                    Instruction::PushInt(41),
                    Instruction::ConstructClass {
                        class_name: "Counter".to_string(),
                        field_names: vec!["value".to_string()],
                    },
                    Instruction::StoreLocal(0),
                    Instruction::LoadLocal(0),
                    Instruction::PushInt(5),
                    Instruction::SetField("value".to_string()),
                    Instruction::Pop,
                    Instruction::LoadLocal(0),
                    Instruction::CallMethod {
                        method_name: "inc".to_string(),
                        argument_count: 0,
                    },
                    Instruction::CallStaticMethod {
                        class_name: "Counter".to_string(),
                        method_name: "answer".to_string(),
                        argument_count: 0,
                    },
                    Instruction::Binary(0),
                    Instruction::Return,
                ],
            },
            Function {
                name: "inc".to_string(),
                parameters: vec![Local {
                    name: "self".to_string(),
                    type_name: "Counter".to_string(),
                }],
                locals: vec![],
                return_type: "Int".to_string(),
                instructions: vec![
                    Instruction::LoadLocal(0),
                    Instruction::GetField("value".to_string()),
                    Instruction::PushInt(1),
                    Instruction::Binary(0),
                    Instruction::Return,
                ],
            },
            Function {
                name: "answer".to_string(),
                parameters: vec![],
                locals: vec![],
                return_type: "Int".to_string(),
                instructions: vec![Instruction::PushInt(36), Instruction::Return],
            },
        ],
    };
    let mut vm = VmInstance::new(module, 128);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(42))
    );
}

fn binary_module(mut instructions: Vec<Instruction>, return_type: &str) -> Module {
    instructions.push(Instruction::Return);
    Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: return_type.to_string(),
            instructions,
        }],
    }
}

fn single_function_module(
    name: &str,
    return_type: &str,
    locals: Vec<Local>,
    instructions: Vec<Instruction>,
) -> Module {
    Module {
        name: name.to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: name.to_string(),
            parameters: vec![],
            locals,
            return_type: return_type.to_string(),
            instructions,
        }],
    }
}

#[test]
fn emits_host_call_signal_for_unknown_builtin() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Unit".to_string(),
            instructions: vec![
                Instruction::PushString("hello".to_string()),
                Instruction::CallBuiltin {
                    module_name: "system".to_string(),
                    function_name: "log".to_string(),
                    argument_count: 1,
                },
            ],
        }],
    };

    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::HostCall {
            module_name: "system".to_string(),
            function_name: "log".to_string(),
            arguments: vec![VmValue::String("hello".to_string())],
        },
    );
}

#[test]
fn emits_wait_event_signal_for_events_pull_builtin() {
    let module = single_function_module(
        "main",
        "Event",
        vec![],
        vec![
            Instruction::PushString("boot".to_string()),
            Instruction::CallBuiltin {
                module_name: "events".to_string(),
                function_name: "pull".to_string(),
                argument_count: 1,
            },
            Instruction::Return,
        ],
    );
    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::WaitEvent(Some("boot".to_string())),
    );
}

#[test]
fn resumes_after_host_call_with_return_value() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Int".to_string(),
            instructions: vec![
                Instruction::CallBuiltin {
                    module_name: "display".to_string(),
                    function_name: "primary".to_string(),
                    argument_count: 0,
                },
                Instruction::PushInt(1),
                Instruction::Binary(0),
                Instruction::Return,
            ],
        }],
    };
    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::HostCall {
            module_name: "display".to_string(),
            function_name: "primary".to_string(),
            arguments: vec![],
        },
    );
    vm.resume_with(VmValue::Int(7)).unwrap();
    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(8))
    );
}

#[test]
fn rejects_invalid_resume_order() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![Function {
            name: "main".to_string(),
            parameters: vec![],
            locals: vec![],
            return_type: "Int".to_string(),
            instructions: vec![Instruction::PushInt(1), Instruction::Return],
        }],
    };
    let mut vm = VmInstance::new(module, 64);

    assert!(vm
        .resume_with(VmValue::Unit)
        .unwrap_err()
        .to_string()
        .contains("not waiting for resume"));
    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(1))
    );
    assert!(vm
        .run_until_signal()
        .unwrap_err()
        .to_string()
        .contains("halted"));
}

#[test]
fn calls_user_function_with_argument_and_returns_to_caller() {
    let module = Module {
        name: "main".to_string(),
        entry_function_index: 0,
        records: vec![],
        classes: vec![],
        functions: vec![
            Function {
                name: "main".to_string(),
                parameters: vec![],
                locals: vec![],
                return_type: "Int".to_string(),
                instructions: vec![
                    Instruction::PushInt(41),
                    Instruction::CallFunction {
                        function_index: 1,
                        argument_count: 1,
                    },
                    Instruction::Return,
                ],
            },
            Function {
                name: "increment".to_string(),
                parameters: vec![Local {
                    name: "value".to_string(),
                    type_name: "Int".to_string(),
                }],
                locals: vec![],
                return_type: "Int".to_string(),
                instructions: vec![
                    Instruction::LoadLocal(0),
                    Instruction::PushInt(1),
                    Instruction::Binary(0),
                    Instruction::Return,
                ],
            },
        ],
    };

    let mut vm = VmInstance::new(module, 64);

    assert_eq!(
        vm.run_until_signal().unwrap(),
        VmSignal::Halt(VmValue::Int(42))
    );
}
