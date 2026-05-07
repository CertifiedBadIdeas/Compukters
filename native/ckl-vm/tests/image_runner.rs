use ckl_vm::image_runner::ImageVmHandle;
use ckl_vm::signal::encode_value;
use ckl_vm::value::VmValue;

const OP_PUSH_UNIT: u8 = 1;
const OP_RETURN: u8 = 2;
const OP_PUSH_CONSTANT: u8 = 3;
const OP_CALL_HOST: u8 = 4;
const OP_POP: u8 = 5;
const OP_PUSH_BOOL: u8 = 6;
const OP_PUSH_NULL: u8 = 7;
const OP_LOAD_LOCAL: u8 = 8;
const OP_STORE_LOCAL: u8 = 9;
const OP_JUMP: u8 = 10;
const OP_JUMP_IF_FALSE: u8 = 11;
const OP_JUMP_IF_TRUE: u8 = 12;
const OP_BINARY: u8 = 13;
const OP_UNARY: u8 = 14;
const OP_CALL_FUNCTION: u8 = 15;
const OP_CONSTRUCT_RECORD: u8 = 16;
const OP_GET_FIELD: u8 = 17;

fn halt_signal(value: &VmValue) -> Vec<u8> {
    let mut signal = vec![0];
    signal.extend_from_slice(&encode_value(value));
    signal
}

#[test]
fn stores_and_loads_local_value() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_STORE_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn supports_null_values() {
    let code = vec![OP_PUSH_NULL, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn jumps_over_unreachable_code() {
    let code = vec![
        OP_JUMP,
        7,
        0,
        0,
        0,
        OP_PUSH_UNIT,
        OP_RETURN,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn conditional_false_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL,
        0,
        OP_JUMP_IF_FALSE,
        10,
        0,
        0,
        0,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
        OP_PUSH_NULL,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn conditional_true_jump_takes_branch() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_JUMP_IF_TRUE,
        9,
        0,
        0,
        0,
        OP_PUSH_NULL,
        OP_RETURN,
        OP_PUSH_BOOL,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn rejects_non_bool_condition() {
    let code = vec![OP_PUSH_UNIT, OP_JUMP_IF_FALSE, 0, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires Bool condition"));
}

#[test]
fn rejects_out_of_range_jump_target() {
    let code = vec![OP_JUMP, 99, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("outside function code"));
}

#[test]
fn rejects_out_of_range_local_slot() {
    let code = vec![OP_LOAD_LOCAL, 1, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("local slot 1 is out of bounds"));
}

#[test]
fn rejects_store_local_stack_underflow() {
    let code = vec![OP_STORE_LOCAL, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(1, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("stack underflow"));
}

#[test]
fn executes_int_arithmetic_and_comparison() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        0,
        OP_PUSH_CONSTANT,
        2,
        0,
        0,
        0,
        OP_BINARY,
        9,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::Int(7),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn executes_bool_logic_and_unary_not() {
    let code = vec![
        OP_PUSH_BOOL,
        1,
        OP_PUSH_BOOL,
        0,
        OP_UNARY,
        1,
        OP_BINARY,
        10,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn executes_string_concatenation() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("hello ".to_string()),
                ConstantFixture::Int(42),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        vec![0, 5, 8, 0, 0, 0, b'h', b'e', b'l', b'l', b'o', b' ', b'4', b'2'],
    );
}

#[test]
fn executes_long_bitwise_and_unary_bit_not() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_UNARY,
        2,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        12,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Long(0), ConstantFixture::Long(255)],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 4, 255, 0, 0, 0, 0, 0, 0, 0]);
}

#[test]
fn rejects_binary_wrong_operand_type() {
    let code = vec![OP_PUSH_UNIT, OP_PUSH_BOOL, 1, OP_BINARY, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("requires"));
}

#[test]
fn rejects_division_by_zero() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        3,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Int(1), ConstantFixture::Int(0)],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("division by zero"));
}

#[test]
fn rejects_unknown_operator_tag() {
    let code = vec![OP_PUSH_BOOL, 1, OP_PUSH_BOOL, 1, OP_BINARY, 99, OP_RETURN];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("unknown CkVmImage binary operator tag 99"));
}

#[test]
fn constructs_record_with_ordered_fields() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let expected = VmValue::Record {
        type_name: "Point".to_string(),
        fields: vec![
            ("x".to_string(), VmValue::Int(2)),
            ("y".to_string(), VmValue::Int(5)),
        ],
    };
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), halt_signal(&expected));
}

#[test]
fn gets_record_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_GET_FIELD,
        4,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 5, 0, 0, 0]);
}

#[test]
fn preserves_record_field_order_for_get_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
        4,
        0,
        0,
        0,
        OP_STORE_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_GET_FIELD,
        3,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_GET_FIELD,
        4,
        0,
        0,
        0,
        OP_BINARY,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            1,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 253, 255, 255, 255]);
}

#[test]
fn rejects_record_type_metadata_that_is_not_string() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(99),
                ConstantFixture::String("x".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record type name constant index 1 must be String"));
}

#[test]
fn rejects_record_field_metadata_that_is_not_string() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record field name constant index 0 must be String"));
}

#[test]
fn rejects_missing_record_field() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        OP_GET_FIELD,
        3,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("record `Point` has no field `y`"));
}

#[test]
fn rejects_get_field_on_non_record() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_GET_FIELD, 1, 0, 0, 0];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Int(2), ConstantFixture::String("x".to_string())],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("GET_FIELD requires Record receiver"));
}

#[test]
fn rejects_construct_record_stack_underflow() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_RECORD,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        3,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::String("Point".to_string()),
                ConstantFixture::String("x".to_string()),
                ConstantFixture::String("y".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("need 2 arguments but stack has 1"));
}

#[test]
fn rejects_string_concatenation_with_record_value() {
    let code = vec![
        OP_CALL_HOST,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_BINARY,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_code(
            vec![ConstantFixture::String("suffix".to_string())],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal()[0], 4);
    let record = VmValue::Record {
        type_name: "Box".to_string(),
        fields: vec![("value".to_string(), VmValue::Int(1))],
    };
    vm.resume_with_value_bytes(&encode_value(&record)).unwrap();
    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("string concatenation with records"));
}

#[test]
fn calls_function_and_returns_value_to_entry_frame() {
    let main_code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CALL_FUNCTION,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let add_code = vec![
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        1,
        0,
        0,
        0,
        OP_BINARY,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            vec![ConstantFixture::Int(2), ConstantFixture::Int(5)],
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code: main_code,
                },
                FunctionFixture {
                    name: "add".to_string(),
                    frame_size: 2,
                    code: add_code,
                },
            ],
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn preserves_function_argument_order() {
    let main_code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CALL_FUNCTION,
        1,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let subtract_code = vec![
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        1,
        0,
        0,
        0,
        OP_BINARY,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            vec![ConstantFixture::Int(2), ConstantFixture::Int(5)],
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code: main_code,
                },
                FunctionFixture {
                    name: "subtract".to_string(),
                    frame_size: 2,
                    code: subtract_code,
                },
            ],
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 253, 255, 255, 255]);
}

#[test]
fn restores_caller_locals_after_return() {
    let main_code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_STORE_LOCAL,
        0,
        0,
        0,
        0,
        OP_CALL_FUNCTION,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        OP_POP,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let callee_code = vec![OP_PUSH_CONSTANT, 1, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            vec![ConstantFixture::Int(9), ConstantFixture::Int(1)],
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 1,
                    code: main_code,
                },
                FunctionFixture {
                    name: "callee".to_string(),
                    frame_size: 0,
                    code: callee_code,
                },
            ],
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 9, 0, 0, 0]);
}

#[test]
fn supports_nested_function_calls() {
    let main_code = vec![OP_CALL_FUNCTION, 1, 0, 0, 0, 0, 0, 0, 0, OP_RETURN];
    let first_code = vec![
        OP_CALL_FUNCTION,
        2,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_BINARY,
        0,
        OP_RETURN,
    ];
    let second_code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            vec![ConstantFixture::Int(3), ConstantFixture::Int(4)],
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code: main_code,
                },
                FunctionFixture {
                    name: "first".to_string(),
                    frame_size: 0,
                    code: first_code,
                },
                FunctionFixture {
                    name: "second".to_string(),
                    frame_size: 0,
                    code: second_code,
                },
            ],
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn resumes_host_call_inside_callee() {
    let main_code = vec![OP_CALL_FUNCTION, 1, 0, 0, 0, 0, 0, 0, 0, OP_RETURN];
    let callee_code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CALL_HOST,
        1,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        OP_POP,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            vec![
                ConstantFixture::String("callee".to_string()),
                ConstantFixture::Int(7),
            ],
            true,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code: main_code,
                },
                FunctionFixture {
                    name: "callee".to_string(),
                    frame_size: 0,
                    code: callee_code,
                },
            ],
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal()[0], 4);
    vm.resume_with_value_bytes(&encode_value(&VmValue::Unit))
        .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn rejects_call_function_out_of_bounds() {
    let code = vec![OP_CALL_FUNCTION, 99, 0, 0, 0, 0, 0, 0, 0];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("function index 99 is out of bounds"));
}

#[test]
fn rejects_call_function_argument_count_exceeding_frame_size() {
    let code = vec![OP_CALL_FUNCTION, 1, 0, 0, 0, 1, 0, 0, 0];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            Vec::new(),
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code,
                },
                FunctionFixture {
                    name: "callee".to_string(),
                    frame_size: 0,
                    code: vec![OP_RETURN],
                },
            ],
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("argument count 1 exceeds frame size 0"));
}

#[test]
fn rejects_call_function_stack_underflow() {
    let code = vec![OP_CALL_FUNCTION, 1, 0, 0, 0, 1, 0, 0, 0];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_import_and_functions(
            Vec::new(),
            false,
            0,
            vec![
                FunctionFixture {
                    name: "main".to_string(),
                    frame_size: 0,
                    code,
                },
                FunctionFixture {
                    name: "callee".to_string(),
                    frame_size: 1,
                    code: vec![OP_RETURN],
                },
            ],
        ),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("stack underflow"));
}

enum ConstantFixture {
    String(String),
    Int(i32),
    Long(i64),
}

struct FunctionFixture {
    name: String,
    frame_size: i32,
    code: Vec<u8>,
}

fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
    image_with_constants_and_code(Vec::new(), frame_size, code)
}

fn image_with_constants_and_code(
    constants: Vec<ConstantFixture>,
    frame_size: i32,
    code: Vec<u8>,
) -> Vec<u8> {
    image_with_constants_and_optional_host_import(constants, false, frame_size, code)
}

fn image_with_constants_host_import_and_code(
    constants: Vec<ConstantFixture>,
    frame_size: i32,
    code: Vec<u8>,
) -> Vec<u8> {
    image_with_constants_and_optional_host_import(constants, true, frame_size, code)
}

fn image_with_constants_and_optional_host_import(
    constants: Vec<ConstantFixture>,
    include_host_import: bool,
    frame_size: i32,
    code: Vec<u8>,
) -> Vec<u8> {
    image_with_constants_host_import_and_functions(
        constants,
        include_host_import,
        0,
        vec![FunctionFixture {
            name: "main".to_string(),
            frame_size,
            code,
        }],
    )
}

fn image_with_constants_host_import_and_functions(
    constants: Vec<ConstantFixture>,
    include_host_import: bool,
    entry_function_index: i32,
    functions: Vec<FunctionFixture>,
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(1);
    string(&mut out, "ckl-1");
    u16(&mut out, 1);
    list_len(&mut out, 0);
    list_len(&mut out, constants.len() as i32);
    for constant in constants {
        match constant {
            ConstantFixture::String(value) => {
                out.push(1);
                string(&mut out, &value);
            }
            ConstantFixture::Int(value) => {
                out.push(2);
                i32(&mut out, value);
            }
            ConstantFixture::Long(value) => {
                out.push(3);
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
    }
    if include_host_import {
        list_len(&mut out, 1);
        i32(&mut out, 1);
        string(&mut out, "test");
        string(&mut out, "log");
        list_len(&mut out, 1);
        string(&mut out, "String");
        string(&mut out, "Unit");
    } else {
        list_len(&mut out, 0);
    }
    i32(&mut out, entry_function_index);
    list_len(&mut out, functions.len() as i32);
    for function in functions {
        string(&mut out, &function.name);
        i32(&mut out, function.frame_size);
        list_len(&mut out, function.code.len() as i32);
        out.extend_from_slice(&function.code);
    }
    out
}

fn list_len(out: &mut Vec<u8>, value: i32) {
    i32(out, value);
}

fn string(out: &mut Vec<u8>, value: &str) {
    i32(out, value.len() as i32);
    out.extend_from_slice(value.as_bytes());
}

fn u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}
