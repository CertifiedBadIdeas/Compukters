use ckl_vm::image_runner::ImageVmHandle;
use ckl_vm::runtime_kernel::{DeviceRuntimeKernel, DeviceRuntimeKernelHandle};
use ckl_vm::signal::encode_value;
use ckl_vm::value::VmValue;
use std::sync::Arc;

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
const OP_CONSTRUCT_ARRAY: u8 = 18;
const OP_CONSTRUCT_LIST: u8 = 19;
const OP_CONSTRUCT_MAP: u8 = 20;
const OP_INDEX_GET: u8 = 21;
const OP_INDEX_SET: u8 = 22;
const OP_CALL_COLLECTION_METHOD: u8 = 23;
const OP_YIELD: u8 = 24;
const OP_SLEEP: u8 = 25;

const STRINGS_TRIM_IMPORT_ID: i32 = 7000;
const STRINGS_BEFORE_SPACE_IMPORT_ID: i32 = 7001;
const STRINGS_AFTER_SPACE_IMPORT_ID: i32 = 7002;
const STRINGS_IS_BLANK_IMPORT_ID: i32 = 7003;
const STRINGS_TO_INT_IMPORT_ID: i32 = 7004;
const STRINGS_LENGTH_IMPORT_ID: i32 = 7005;
const STRINGS_CHAR_AT_IMPORT_ID: i32 = 7006;
const STRINGS_REPEAT_IMPORT_ID: i32 = 7007;
const STRINGS_SLICE_IMPORT_ID: i32 = 7008;
const STRINGS_REPLACE_RANGE_IMPORT_ID: i32 = 7009;
const STRINGS_CHAR_CODE_AT_IMPORT_ID: i32 = 7010;

fn halt_signal(value: &VmValue) -> Vec<u8> {
    let mut signal = vec![0];
    signal.extend_from_slice(&encode_value(value));
    signal
}

#[test]
fn device_kernel_accepts_event_and_ipc_setup() {
    let mut kernel = DeviceRuntimeKernel::new(64, 4096);

    assert!(kernel.enqueue_event("boot", vec![]));

    let channel = kernel.open_ipc_channel().unwrap();

    assert!(channel > 0);
}

#[test]
fn kernel_handle_advances_wake_sequence_for_poll_visible_mutations() {
    let handle = DeviceRuntimeKernelHandle::new(8, 64);

    assert_eq!(handle.wake_sequence().unwrap(), 0);
    handle
        .with_kernel_mut(|kernel| {
            assert!(kernel.enqueue_event("key", vec![VmValue::String("x".to_string())]));
        })
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 1);

    let channel = handle
        .with_kernel_mut(|kernel| kernel.open_ipc_channel())
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 1);

    handle
        .with_kernel_mut(|kernel| kernel.write_ipc(channel, "ready"))
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 2);

    handle
        .with_kernel_mut(|kernel| kernel.try_read_ipc(channel))
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 2);

    handle
        .with_kernel_mut(|kernel| kernel.close_ipc(channel))
        .unwrap()
        .unwrap();
    assert_eq!(handle.wake_sequence().unwrap(), 3);
}

#[test]
fn kernel_handle_wait_returns_after_timeout_without_wake() {
    let handle = DeviceRuntimeKernelHandle::new(8, 64);
    let started = std::time::Instant::now();

    let sequence = handle
        .wait_for_wake(0, std::time::Duration::from_millis(1))
        .unwrap();

    assert_eq!(sequence, 0);
    assert!(started.elapsed() < std::time::Duration::from_secs(1));
}

#[test]
fn native_kernel_pulls_filtered_events_and_reads_arguments() {
    let mut kernel = DeviceRuntimeKernel::new(8, 64);
    assert!(kernel.enqueue_event(
        "mouse",
        vec![VmValue::Int(4), VmValue::String("left".to_string()),],
    ));
    assert!(kernel.enqueue_event("key", vec![VmValue::Bool(true)]));

    let event = kernel.try_pull_event(Some("mouse")).expect("mouse event");

    assert_eq!(event.name, "mouse");
    assert_eq!(event.arg_count, 2);
    assert_eq!(kernel.event_arg_int(event.id, 0), 4);
    assert_eq!(kernel.event_arg_string(event.id, 1), "left");
    assert!(!kernel.event_arg_bool(event.id, 0));
    assert!(kernel.try_pull_event(Some("missing")).is_none());
    assert_eq!(kernel.try_pull_event(None).expect("next event").name, "key");
}

#[test]
fn native_kernel_ipc_buffers_and_closes_channels() {
    let mut kernel = DeviceRuntimeKernel::new(8, 5);
    let channel = kernel.open_ipc_channel().expect("channel");

    kernel.write_ipc(channel, "hello").expect("write hello");
    kernel
        .write_ipc(channel, " world")
        .expect("write world truncated by quota");

    assert_eq!(kernel.try_read_ipc(channel).expect("read"), "hello");
    assert_eq!(kernel.try_read_ipc(channel).expect("empty read"), "");

    kernel.close_ipc(channel).expect("close");
    assert_eq!(kernel.try_read_ipc(channel).expect("closed read"), "");
    assert!(kernel.write_ipc(channel, "x").is_err());
}

#[test]
fn device_kernel_owns_display_registry() {
    let mut kernel = DeviceRuntimeKernel::new(64, 4096);

    kernel
        .displays
        .attach(12, 18, 18, ckl_vm::display::PixelFormat::Rgb565)
        .unwrap();

    assert_eq!(kernel.displays.first_display_id(), Some(12));
    assert_eq!(kernel.displays.drain_frames().len(), 1);
}

#[test]
fn attached_kernel_handles_display_fill_rect_and_present_imports() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(64, 4096));
    kernel
        .with_kernel_mut(|kernel| {
            kernel
                .displays
                .attach(1, 18, 18, ckl_vm::display::PixelFormat::Rgb565)
        })
        .unwrap()
        .unwrap();
    let _ = kernel
        .with_kernel_mut(|kernel| kernel.displays.drain_frames())
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    push_constant(&mut code, 4);
    push_constant(&mut code, 5);
    call_host(&mut code, 1003, 6);
    code.push(OP_POP);
    push_constant(&mut code, 0);
    call_host(&mut code, 1011, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(0),
                ConstantFixture::Int(0),
                ConstantFixture::Int(2),
                ConstantFixture::Int(2),
                ConstantFixture::Int(2016),
            ],
            vec![
                HostImportFixture {
                    id: 1003,
                    module_name: "display".to_string(),
                    function_name: "fillRect".to_string(),
                    parameter_types: vec![
                        "Int".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                    ],
                    return_type: "Unit".to_string(),
                },
                HostImportFixture {
                    id: 1011,
                    module_name: "display".to_string(),
                    function_name: "present".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Unit".to_string(),
                },
            ],
            0,
            code,
        ),
        512,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(
        signal[0], 0,
        "program should halt instead of emitting display host calls"
    );
    assert_eq!(
        kernel
            .with_kernel_mut(|kernel| kernel.displays.drain_frames())
            .unwrap()
            .len(),
        1
    );
}

#[test]
fn attached_kernel_handles_display_text_run_import() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(64, 4096));
    kernel
        .with_kernel_mut(|kernel| {
            kernel
                .displays
                .attach(1, 18, 18, ckl_vm::display::PixelFormat::Rgb565)
        })
        .unwrap()
        .unwrap();
    let _ = kernel
        .with_kernel_mut(|kernel| kernel.displays.drain_frames())
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    push_constant(&mut code, 4);
    push_constant(&mut code, 5);
    call_host(&mut code, 1012, 6);
    code.push(OP_POP);
    push_constant(&mut code, 0);
    call_host(&mut code, 1011, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(0),
                ConstantFixture::Int(1),
                ConstantFixture::String("AB".to_string()),
                ConstantFixture::Int(2016),
                ConstantFixture::Int(-1),
            ],
            vec![
                HostImportFixture {
                    id: 1012,
                    module_name: "display".to_string(),
                    function_name: "blitMono5x7Text".to_string(),
                    parameter_types: vec![
                        "Int".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                        "String".to_string(),
                        "Int".to_string(),
                        "Int".to_string(),
                    ],
                    return_type: "Unit".to_string(),
                },
                HostImportFixture {
                    id: 1011,
                    module_name: "display".to_string(),
                    function_name: "present".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Unit".to_string(),
                },
            ],
            0,
            code,
        ),
        512,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    let signal = vm.run_until_signal();
    let frames = kernel
        .with_kernel_mut(|kernel| kernel.displays.drain_frames())
        .unwrap();

    assert_eq!(
        signal[0], 0,
        "program should halt instead of emitting display host calls"
    );
    assert_eq!(frames.len(), 1);
    assert!(!frames[0].tiles.is_empty());
}

#[test]
fn attached_kernel_handles_ipc_events_and_display_metadata_imports() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(8, 64));
    kernel
        .with_kernel_mut(|kernel| {
            kernel
                .displays
                .attach(7, 20, 10, ckl_vm::display::PixelFormat::Rgb565)
                .unwrap();
            kernel.enqueue_event("char", vec![VmValue::String("a".to_string())]);
        })
        .unwrap();

    let mut code = Vec::new();
    call_host(&mut code, 5000, 0);
    store_local(&mut code, 1);
    load_local(&mut code, 1);
    push_constant(&mut code, 0);
    call_host(&mut code, 5001, 2);
    load_local(&mut code, 1);
    call_host(&mut code, 5003, 1);
    code.push(OP_POP);
    call_host(&mut code, 4002, 0);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    push_constant(&mut code, 1);
    call_host(&mut code, 4007, 2);
    code.push(OP_POP);
    call_host(&mut code, 1000, 0);
    call_host(&mut code, 1002, 1);
    code.push(OP_POP);
    push_constant(&mut code, 2);
    call_host(&mut code, 1003, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::String("hello".to_string()),
                ConstantFixture::Int(0),
                ConstantFixture::Int(7),
            ],
            vec![
                HostImportFixture {
                    id: 5000,
                    module_name: "ipc".to_string(),
                    function_name: "open".to_string(),
                    parameter_types: vec![],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 5001,
                    module_name: "ipc".to_string(),
                    function_name: "write".to_string(),
                    parameter_types: vec!["Int".to_string(), "String".to_string()],
                    return_type: "Unit".to_string(),
                },
                HostImportFixture {
                    id: 5003,
                    module_name: "ipc".to_string(),
                    function_name: "tryRead".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "String".to_string(),
                },
                HostImportFixture {
                    id: 4002,
                    module_name: "events".to_string(),
                    function_name: "tryPull".to_string(),
                    parameter_types: vec![],
                    return_type: "Event".to_string(),
                },
                HostImportFixture {
                    id: 4007,
                    module_name: "events".to_string(),
                    function_name: "argString".to_string(),
                    parameter_types: vec!["Event".to_string(), "Int".to_string()],
                    return_type: "String".to_string(),
                },
                HostImportFixture {
                    id: 1000,
                    module_name: "display".to_string(),
                    function_name: "primary".to_string(),
                    parameter_types: vec![],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 1002,
                    module_name: "display".to_string(),
                    function_name: "width".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 1003,
                    module_name: "display".to_string(),
                    function_name: "height".to_string(),
                    parameter_types: vec!["Int".to_string()],
                    return_type: "Int".to_string(),
                },
            ],
            2,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(
        signal[0], 0,
        "program should halt instead of emitting native-kernel host calls",
    );
}

#[test]
fn attached_kernel_waits_poll_without_generic_host_call() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(8, 64));
    let channel = kernel
        .with_kernel_mut(|kernel| kernel.open_ipc_channel())
        .unwrap()
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, 8000, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::Int(channel)],
            vec![HostImportFixture {
                id: 8000,
                module_name: "runtime".to_string(),
                function_name: "poll".to_string(),
                parameter_types: vec!["Int".to_string()],
                return_type: "Poll".to_string(),
            }],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    assert_eq!(
        vm.run_until_signal(),
        vec![6, channel as u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
    );

    kernel
        .with_kernel_mut(|kernel| kernel.write_ipc(channel, "ready"))
        .unwrap()
        .expect("write ipc");
    let signal = vm.run_until_signal();

    assert_eq!(
        signal[0], 0,
        "poll should halt with Poll value after native wake"
    );
}

#[test]
fn attached_kernel_handles_completed_process_wait_without_generic_host_call() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(8, 64));
    kernel
        .with_kernel_mut(|kernel| {
            kernel.register_process(7, 1, "child.ck".to_string());
            kernel.complete_process(7, 0);
        })
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, 6007, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::Int(7)],
            vec![HostImportFixture {
                id: 6007,
                module_name: "process".to_string(),
                function_name: "wait".to_string(),
                parameter_types: vec!["Int".to_string()],
                return_type: "Int".to_string(),
            }],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 0, 0, 0, 0]);
}

#[test]
fn attached_kernel_parks_on_running_process_wait() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new(8, 64));
    kernel
        .with_kernel_mut(|kernel| {
            kernel.register_process(7, 1, "child.ck".to_string());
        })
        .unwrap();

    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, 6007, 1);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::Int(7)],
            vec![HostImportFixture {
                id: 6007,
                module_name: "process".to_string(),
                function_name: "wait".to_string(),
                parameter_types: vec!["Int".to_string()],
                return_type: "Int".to_string(),
            }],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    assert_eq!(
        vm.run_until_signal(),
        vec![7, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
    );
}

#[test]
fn attached_kernel_handles_system_identity_without_generic_host_call() {
    let kernel = Arc::new(DeviceRuntimeKernelHandle::new_with_system_identity(
        8,
        64,
        7,
        "Normal".to_string(),
    ));

    let mut code = Vec::new();
    call_host(&mut code, 3000, 0);
    call_host(&mut code, 3003, 0);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![],
            vec![
                HostImportFixture {
                    id: 3000,
                    module_name: "system".to_string(),
                    function_name: "deviceId".to_string(),
                    parameter_types: vec![],
                    return_type: "Int".to_string(),
                },
                HostImportFixture {
                    id: 3003,
                    module_name: "system".to_string(),
                    function_name: "profileName".to_string(),
                    parameter_types: vec![],
                    return_type: "String".to_string(),
                },
            ],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::clone(&kernel)).unwrap();

    assert_eq!(
        vm.run_until_signal(),
        halt_signal(&VmValue::String("7Normal".to_string()))
    );
}

#[test]
fn native_owned_unresolved_host_import_fails_fast_instead_of_falling_back() {
    let mut code = Vec::new();
    call_host(&mut code, 9000, 0);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![],
            vec![HostImportFixture {
                id: 9000,
                module_name: "filesystem".to_string(),
                function_name: "unknown".to_string(),
                parameter_types: vec![],
                return_type: "Unit".to_string(),
            }],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::new(DeviceRuntimeKernelHandle::new(8, 64)))
        .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    let message = String::from_utf8_lossy(&signal);
    assert!(message.contains("filesystem.unknown"), "{message}");
    assert!(message.contains("Kotlin fallback is disabled"), "{message}");
}

#[test]
fn jvm_owned_unresolved_host_import_still_emits_host_call_signal() {
    let mut code = Vec::new();
    call_host(&mut code, 3001, 0);
    code.push(OP_RETURN);

    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![],
            vec![HostImportFixture {
                id: 3001,
                module_name: "system".to_string(),
                function_name: "currentTick".to_string(),
                parameter_types: vec![],
                return_type: "Long".to_string(),
            }],
            0,
            code,
        ),
        4096,
    )
    .unwrap();
    vm.attach_device_kernel(Arc::new(DeviceRuntimeKernelHandle::new(8, 64)))
        .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 4);
    let encoded = String::from_utf8_lossy(&signal);
    assert!(encoded.contains("system"), "{encoded}");
    assert!(encoded.contains("currentTick"), "{encoded}");
}

#[test]
fn process_registration_and_completion_report_success() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);

    assert!(kernel.register_process(2, 1, "shell.ck".to_string()));
    assert_eq!(
        kernel.process_status(2),
        ckl_vm::runtime_kernel::ProcessStatus::Running
    );
    assert!(kernel.complete_process(2, 0));
    assert_eq!(
        kernel.process_status(2),
        ckl_vm::runtime_kernel::ProcessStatus::Completed(0)
    );
}

#[test]
fn process_registration_rejects_duplicates_and_completion_rejects_stale_pid() {
    let mut kernel = ckl_vm::runtime_kernel::DeviceRuntimeKernel::new(8, 64);

    assert!(kernel.register_process(2, 1, "first.ck".to_string()));
    assert!(!kernel.register_process(2, 1, "second.ck".to_string()));
    assert!(!kernel.complete_process(99, 1));
    assert!(kernel.complete_process(2, 0));
    assert!(!kernel.complete_process(2, 1));

    assert_eq!(
        kernel.process_status(2),
        ckl_vm::runtime_kernel::ProcessStatus::Completed(0)
    );
    assert_eq!(
        kernel.process_status(99),
        ckl_vm::runtime_kernel::ProcessStatus::Missing
    );
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
fn executes_array_index_set_and_get() {
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
        OP_CONSTRUCT_ARRAY,
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
        OP_PUSH_CONSTANT,
        2,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        3,
        0,
        0,
        0,
        OP_INDEX_SET,
        OP_POP,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        2,
        0,
        0,
        0,
        OP_INDEX_GET,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(0),
                ConstantFixture::Int(1),
                ConstantFixture::Int(7),
            ],
            1,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn executes_array_collection_methods() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    code.push(OP_CONSTRUCT_ARRAY);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    call_collection_method(&mut code, 5, 2);
    code.push(OP_POP);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 6, 0);
    push_constant(&mut code, 4);
    code.push(OP_BINARY);
    code.push(2);
    load_local(&mut code, 0);
    push_constant(&mut code, 2);
    call_collection_method(&mut code, 7, 1);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(1),
                ConstantFixture::Int(0),
                ConstantFixture::Int(7),
                ConstantFixture::Int(10),
                ConstantFixture::String("set".to_string()),
                ConstantFixture::String("size".to_string()),
                ConstantFixture::String("get".to_string()),
            ],
            1,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 27, 0, 0, 0]);
}

#[test]
fn executes_array_get_or_null_for_missing_index() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    code.push(OP_CONSTRUCT_ARRAY);
    push_constant(&mut code, 2);
    call_collection_method(&mut code, 3, 1);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(0),
                ConstantFixture::Int(9),
                ConstantFixture::String("getOrNull".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn executes_list_methods_and_preserves_alias_identity() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_LIST,
        1,
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
        OP_STORE_LOCAL,
        1,
        0,
        0,
        0,
        OP_LOAD_LOCAL,
        1,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_CALL_COLLECTION_METHOD,
        2,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        OP_POP,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        3,
        0,
        0,
        0,
        OP_INDEX_GET,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        4,
        0,
        0,
        0,
        OP_INDEX_GET,
        OP_BINARY,
        1,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(2),
                ConstantFixture::Int(5),
                ConstantFixture::String("add".to_string()),
                ConstantFixture::Int(0),
                ConstantFixture::Int(1),
            ],
            2,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 253, 255, 255, 255]);
}

#[test]
fn executes_list_collection_methods() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    code.push(OP_CONSTRUCT_LIST);
    i32(&mut code, 2);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    call_collection_method(&mut code, 8, 2);
    code.push(OP_POP);
    load_local(&mut code, 0);
    push_constant(&mut code, 4);
    push_constant(&mut code, 5);
    call_collection_method(&mut code, 9, 2);
    code.push(OP_POP);
    load_local(&mut code, 0);
    push_constant(&mut code, 6);
    call_collection_method(&mut code, 10, 1);
    code.push(OP_POP);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 11, 0);
    code.push(OP_POP);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 12, 0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(3),
                ConstantFixture::Int(1),
                ConstantFixture::Int(2),
                ConstantFixture::Int(0),
                ConstantFixture::Int(4),
                ConstantFixture::Int(2),
                ConstantFixture::Int(9),
                ConstantFixture::String("insert".to_string()),
                ConstantFixture::String("set".to_string()),
                ConstantFixture::String("removeAt".to_string()),
                ConstantFixture::String("clear".to_string()),
                ConstantFixture::String("isEmpty".to_string()),
            ],
            1,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn executes_list_get_or_null_for_missing_index() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    code.push(OP_CONSTRUCT_LIST);
    i32(&mut code, 1);
    push_constant(&mut code, 1);
    call_collection_method(&mut code, 2, 1);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(-1),
                ConstantFixture::String("getOrNull".to_string()),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 1]);
}

#[test]
fn executes_map_index_set_and_get_or_default() {
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
        OP_CONSTRUCT_MAP,
        1,
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
        OP_PUSH_CONSTANT,
        2,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        3,
        0,
        0,
        0,
        OP_INDEX_SET,
        OP_POP,
        OP_LOAD_LOCAL,
        0,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        4,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        5,
        0,
        0,
        0,
        OP_CALL_COLLECTION_METHOD,
        6,
        0,
        0,
        0,
        2,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("x".to_string()),
                ConstantFixture::Int(3),
                ConstantFixture::String("y".to_string()),
                ConstantFixture::Int(4),
                ConstantFixture::String("missing".to_string()),
                ConstantFixture::Int(9),
                ConstantFixture::String("getOrDefault".to_string()),
            ],
            1,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 9, 0, 0, 0]);
}

#[test]
fn executes_map_contains_key() {
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
        OP_CONSTRUCT_MAP,
        1,
        0,
        0,
        0,
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CALL_COLLECTION_METHOD,
        2,
        0,
        0,
        0,
        1,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("x".to_string()),
                ConstantFixture::Int(3),
                ConstantFixture::String("containsKey".to_string()),
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
fn executes_map_duplicate_key_replacement_with_numeric_widening() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    code.push(OP_CONSTRUCT_MAP);
    i32(&mut code, 2);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 5, 0);
    push_constant(&mut code, 4);
    code.push(OP_BINARY);
    code.push(2);
    load_local(&mut code, 0);
    push_constant(&mut code, 2);
    call_collection_method(&mut code, 6, 1);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::Int(1),
                ConstantFixture::Int(3),
                ConstantFixture::Long(1),
                ConstantFixture::Int(4),
                ConstantFixture::Int(10),
                ConstantFixture::String("size".to_string()),
                ConstantFixture::String("get".to_string()),
            ],
            1,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 14, 0, 0, 0]);
}

#[test]
fn executes_map_keys_and_values_as_lists() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    code.push(OP_CONSTRUCT_MAP);
    i32(&mut code, 2);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 4, 0);
    store_local(&mut code, 1);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 5, 0);
    store_local(&mut code, 2);
    load_local(&mut code, 2);
    push_constant(&mut code, 6);
    code.push(OP_INDEX_GET);
    load_local(&mut code, 1);
    call_collection_method(&mut code, 7, 0);
    code.push(OP_BINARY);
    code.push(1);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("a".to_string()),
                ConstantFixture::Int(2),
                ConstantFixture::String("b".to_string()),
                ConstantFixture::Int(5),
                ConstantFixture::String("keys".to_string()),
                ConstantFixture::String("values".to_string()),
                ConstantFixture::Int(1),
                ConstantFixture::String("size".to_string()),
            ],
            3,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 3, 0, 0, 0]);
}

#[test]
fn executes_map_remove_clear_and_is_empty() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    code.push(OP_CONSTRUCT_MAP);
    i32(&mut code, 1);
    store_local(&mut code, 0);
    load_local(&mut code, 0);
    push_constant(&mut code, 0);
    call_collection_method(&mut code, 2, 1);
    code.push(OP_POP);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 3, 0);
    code.push(OP_POP);
    load_local(&mut code, 0);
    call_collection_method(&mut code, 4, 0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![
                ConstantFixture::String("a".to_string()),
                ConstantFixture::Int(2),
                ConstantFixture::String("remove".to_string()),
                ConstantFixture::String("clear".to_string()),
                ConstantFixture::String("isEmpty".to_string()),
            ],
            1,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 2, 1]);
}

#[test]
fn rejects_array_negative_size() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_PUSH_UNIT,
        OP_CONSTRUCT_ARRAY,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(-1)], 0, code),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("negative CkVmImage array size -1"));
}

#[test]
fn rejects_index_get_on_non_collection_receiver() {
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
        OP_INDEX_GET,
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
    assert!(String::from_utf8_lossy(&signal).contains("requires collection ObjectRef receiver"));
}

#[test]
fn rejects_null_map_key() {
    let code = vec![
        OP_PUSH_NULL,
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_CONSTRUCT_MAP,
        1,
        0,
        0,
        0,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(1)], 0, code),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("Map keys cannot be null"));
}

#[test]
fn rejects_null_map_key_for_index_set() {
    let code = vec![
        OP_CONSTRUCT_MAP,
        0,
        0,
        0,
        0,
        OP_PUSH_NULL,
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_INDEX_SET,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(1)], 0, code),
        64,
    )
    .unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("Map keys cannot be null"));
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
    assert!(String::from_utf8_lossy(&signal)
        .contains("record type name constant index 1 must be String"));
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
    assert!(String::from_utf8_lossy(&signal)
        .contains("record field name constant index 0 must be String"));
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
            vec![
                ConstantFixture::Int(2),
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
fn executes_yield_signal_and_resumes_with_unit() {
    let code = vec![OP_YIELD, OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_RETURN];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(7)], 0, code),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![2]);
    vm.resume_with_value_bytes(&encode_value(&VmValue::Unit))
        .unwrap();
    assert_eq!(vm.run_until_signal(), vec![0, 3, 7, 0, 0, 0]);
}

#[test]
fn executes_sleep_signal_and_resumes_with_unit() {
    let code = vec![
        OP_PUSH_CONSTANT,
        0,
        0,
        0,
        0,
        OP_SLEEP,
        OP_PUSH_CONSTANT,
        1,
        0,
        0,
        0,
        OP_RETURN,
    ];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(
            vec![ConstantFixture::Long(9), ConstantFixture::Int(3)],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![3, 9, 0, 0, 0, 0, 0, 0, 0]);
    vm.resume_with_value_bytes(&encode_value(&VmValue::Unit))
        .unwrap();
    assert_eq!(vm.run_until_signal(), vec![0, 3, 3, 0, 0, 0]);
}

#[test]
fn executes_sleep_signal_with_int_ticks() {
    let code = vec![OP_PUSH_CONSTANT, 0, 0, 0, 0, OP_SLEEP];
    let mut vm = ImageVmHandle::create(
        &image_with_constants_and_code(vec![ConstantFixture::Int(1)], 0, code),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![3, 1, 0, 0, 0, 0, 0, 0, 0]);
}

#[test]
fn rejects_sleep_with_non_long_ticks() {
    let code = vec![OP_PUSH_UNIT, OP_SLEEP];
    let mut vm = ImageVmHandle::create(&image_with_code(0, code), 64).unwrap();

    let signal = vm.run_until_signal();

    assert_eq!(signal[0], 255);
    assert!(String::from_utf8_lossy(&signal).contains("CkVmImage SLEEP requires Long ticks"));
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
fn native_strings_length_handles_ascii_without_host_signal() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, STRINGS_LENGTH_IMPORT_ID, 1);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![ConstantFixture::String("terminal".to_string())],
            vec![strings_import(
                STRINGS_LENGTH_IMPORT_ID,
                "length",
                vec!["String"],
                "Int",
            )],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(vm.run_until_signal(), vec![0, 3, 8, 0, 0, 0]);
}

#[test]
fn native_strings_char_at_handles_ascii_without_host_signal() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    call_host(&mut code, STRINGS_CHAR_AT_IMPORT_ID, 2);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::String("prompt".to_string()),
                ConstantFixture::Int(2),
            ],
            vec![strings_import(
                STRINGS_CHAR_AT_IMPORT_ID,
                "charAt",
                vec!["String", "Int"],
                "String",
            )],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        halt_signal(&VmValue::String("o".to_string()))
    );
}

#[test]
fn native_strings_bulk_helpers_handle_ascii_without_host_signal() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    call_host(&mut code, STRINGS_REPEAT_IMPORT_ID, 2);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    call_host(&mut code, STRINGS_REPLACE_RANGE_IMPORT_ID, 3);
    push_constant(&mut code, 4);
    push_constant(&mut code, 5);
    call_host(&mut code, STRINGS_SLICE_IMPORT_ID, 3);
    push_constant(&mut code, 6);
    push_constant(&mut code, 7);
    call_host(&mut code, STRINGS_CHAR_CODE_AT_IMPORT_ID, 2);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::String(".".to_string()),
                ConstantFixture::Int(6),
                ConstantFixture::Int(2),
                ConstantFixture::String("XY".to_string()),
                ConstantFixture::Int(1),
                ConstantFixture::Int(5),
                ConstantFixture::String("Az".to_string()),
                ConstantFixture::Int(1),
            ],
            vec![
                strings_import(
                    STRINGS_REPEAT_IMPORT_ID,
                    "repeat",
                    vec!["String", "Int"],
                    "String",
                ),
                strings_import(
                    STRINGS_REPLACE_RANGE_IMPORT_ID,
                    "replaceRange",
                    vec!["String", "Int", "String"],
                    "String",
                ),
                strings_import(
                    STRINGS_SLICE_IMPORT_ID,
                    "slice",
                    vec!["String", "Int", "Int"],
                    "String",
                ),
                strings_import(
                    STRINGS_CHAR_CODE_AT_IMPORT_ID,
                    "charCodeAt",
                    vec!["String", "Int"],
                    "Int",
                ),
            ],
            0,
            code,
        ),
        64,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        halt_signal(&VmValue::String(".XY.122".to_string()))
    );
}

#[test]
fn native_strings_whitespace_helpers_handle_ascii_without_host_signal() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, STRINGS_BEFORE_SPACE_IMPORT_ID, 1);
    push_constant(&mut code, 1);
    call_host(&mut code, STRINGS_AFTER_SPACE_IMPORT_ID, 1);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 2);
    call_host(&mut code, STRINGS_TRIM_IMPORT_ID, 1);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 3);
    call_host(&mut code, STRINGS_IS_BLANK_IMPORT_ID, 1);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 4);
    call_host(&mut code, STRINGS_TO_INT_IMPORT_ID, 1);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::String("  alpha beta".to_string()),
                ConstantFixture::String("  alpha   beta".to_string()),
                ConstantFixture::String("\ttrimmed\n".to_string()),
                ConstantFixture::String(" \t\n".to_string()),
                ConstantFixture::String(" 42 ".to_string()),
            ],
            vec![
                strings_import(
                    STRINGS_BEFORE_SPACE_IMPORT_ID,
                    "beforeSpace",
                    vec!["String"],
                    "String",
                ),
                strings_import(
                    STRINGS_AFTER_SPACE_IMPORT_ID,
                    "afterSpace",
                    vec!["String"],
                    "String",
                ),
                strings_import(STRINGS_TRIM_IMPORT_ID, "trim", vec!["String"], "String"),
                strings_import(
                    STRINGS_IS_BLANK_IMPORT_ID,
                    "isBlank",
                    vec!["String"],
                    "Bool",
                ),
                strings_import(STRINGS_TO_INT_IMPORT_ID, "toInt", vec!["String"], "Int"),
            ],
            0,
            code,
        ),
        128,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        halt_signal(&VmValue::String("alphabetatrimmedtrue42".to_string())),
    );
}

#[test]
fn native_strings_handle_unicode_scalars_without_host_signal() {
    let mut code = Vec::new();
    push_constant(&mut code, 0);
    call_host(&mut code, STRINGS_LENGTH_IMPORT_ID, 1);
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    call_host(&mut code, STRINGS_CHAR_AT_IMPORT_ID, 2);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 0);
    push_constant(&mut code, 2);
    push_constant(&mut code, 3);
    call_host(&mut code, STRINGS_SLICE_IMPORT_ID, 3);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    push_constant(&mut code, 4);
    call_host(&mut code, STRINGS_REPLACE_RANGE_IMPORT_ID, 3);
    code.push(OP_BINARY);
    code.push(0);
    push_constant(&mut code, 0);
    push_constant(&mut code, 1);
    call_host(&mut code, STRINGS_CHAR_CODE_AT_IMPORT_ID, 2);
    code.push(OP_BINARY);
    code.push(0);
    code.push(OP_RETURN);
    let mut vm = ImageVmHandle::create(
        &image_with_constants_host_imports_and_code(
            vec![
                ConstantFixture::String("Aя🦀Z".to_string()),
                ConstantFixture::Int(2),
                ConstantFixture::Int(1),
                ConstantFixture::Int(3),
                ConstantFixture::String("中".to_string()),
            ],
            vec![
                strings_import(STRINGS_LENGTH_IMPORT_ID, "length", vec!["String"], "Int"),
                strings_import(
                    STRINGS_CHAR_AT_IMPORT_ID,
                    "charAt",
                    vec!["String", "Int"],
                    "String",
                ),
                strings_import(
                    STRINGS_SLICE_IMPORT_ID,
                    "slice",
                    vec!["String", "Int", "Int"],
                    "String",
                ),
                strings_import(
                    STRINGS_REPLACE_RANGE_IMPORT_ID,
                    "replaceRange",
                    vec!["String", "Int", "String"],
                    "String",
                ),
                strings_import(
                    STRINGS_CHAR_CODE_AT_IMPORT_ID,
                    "charCodeAt",
                    vec!["String", "Int"],
                    "Int",
                ),
            ],
            0,
            code,
        ),
        256,
    )
    .unwrap();

    assert_eq!(
        vm.run_until_signal(),
        halt_signal(&VmValue::String("4🦀я🦀Aя中Z129408".to_string()))
    );
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

struct HostImportFixture {
    id: i32,
    module_name: String,
    function_name: String,
    parameter_types: Vec<String>,
    return_type: String,
}

fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
    image_with_constants_and_code(Vec::new(), frame_size, code)
}

fn push_constant(out: &mut Vec<u8>, constant_index: i32) {
    out.push(OP_PUSH_CONSTANT);
    i32(out, constant_index);
}

fn load_local(out: &mut Vec<u8>, local_index: i32) {
    out.push(OP_LOAD_LOCAL);
    i32(out, local_index);
}

fn store_local(out: &mut Vec<u8>, local_index: i32) {
    out.push(OP_STORE_LOCAL);
    i32(out, local_index);
}

fn call_collection_method(out: &mut Vec<u8>, method_name_index: i32, argument_count: i32) {
    out.push(OP_CALL_COLLECTION_METHOD);
    i32(out, method_name_index);
    i32(out, argument_count);
}

fn call_host(out: &mut Vec<u8>, import_id: i32, argument_count: i32) {
    out.push(OP_CALL_HOST);
    i32(out, import_id);
    i32(out, argument_count);
}

fn strings_import(
    id: i32,
    function_name: &str,
    parameter_types: Vec<&str>,
    return_type: &str,
) -> HostImportFixture {
    HostImportFixture {
        id,
        module_name: "strings".to_string(),
        function_name: function_name.to_string(),
        parameter_types: parameter_types
            .into_iter()
            .map(|parameter_type| parameter_type.to_string())
            .collect(),
        return_type: return_type.to_string(),
    }
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
    image_with_constants_host_imports_and_code(
        constants,
        vec![HostImportFixture {
            id: 1,
            module_name: "system".to_string(),
            function_name: "log".to_string(),
            parameter_types: vec!["String".to_string()],
            return_type: "Unit".to_string(),
        }],
        frame_size,
        code,
    )
}

fn image_with_constants_host_imports_and_code(
    constants: Vec<ConstantFixture>,
    host_imports: Vec<HostImportFixture>,
    frame_size: i32,
    code: Vec<u8>,
) -> Vec<u8> {
    image_with_constants_host_imports_and_functions(
        constants,
        host_imports,
        0,
        vec![FunctionFixture {
            name: "main".to_string(),
            frame_size,
            code,
        }],
    )
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
    let host_imports = if include_host_import {
        vec![HostImportFixture {
            id: 1,
            module_name: "system".to_string(),
            function_name: "log".to_string(),
            parameter_types: vec!["String".to_string()],
            return_type: "Unit".to_string(),
        }]
    } else {
        Vec::new()
    };
    image_with_constants_host_imports_and_functions(
        constants,
        host_imports,
        entry_function_index,
        functions,
    )
}

fn image_with_constants_host_imports_and_functions(
    constants: Vec<ConstantFixture>,
    host_imports: Vec<HostImportFixture>,
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
    list_len(&mut out, host_imports.len() as i32);
    for host_import in host_imports {
        i32(&mut out, host_import.id);
        string(&mut out, &host_import.module_name);
        string(&mut out, &host_import.function_name);
        list_len(&mut out, host_import.parameter_types.len() as i32);
        for parameter_type in host_import.parameter_types {
            string(&mut out, &parameter_type);
        }
        string(&mut out, &host_import.return_type);
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
