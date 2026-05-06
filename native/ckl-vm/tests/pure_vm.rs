use ckl_vm::abi::{Function, Instruction, Local, Module};
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

    assert_eq!(vm.run_until_signal(), VmSignal::Halt(VmValue::Int(3)));
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
        vm.run_until_signal(),
        VmSignal::HostCall {
            module_name: "system".to_string(),
            function_name: "log".to_string(),
            arguments: vec![VmValue::String("hello".to_string())],
        },
    );
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
                    Instruction::CallFunction { function_index: 1, argument_count: 1 },
                    Instruction::Return,
                ],
            },
            Function {
                name: "increment".to_string(),
                parameters: vec![Local { name: "value".to_string(), type_name: "Int".to_string() }],
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

    assert_eq!(vm.run_until_signal(), VmSignal::Halt(VmValue::Int(42)));
}
