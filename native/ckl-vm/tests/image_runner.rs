use ckl_vm::image_runner::ImageVmHandle;
use ckl_vm::runtime_kernel::{DeviceRuntimeKernel, DeviceRuntimeKernelHandle};
use ckl_vm::signal::VmSignal;
use ckl_vm::value::VmValue;
use std::sync::Arc;

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
        vec![VmValue::Int(4), VmValue::String("left".to_string())],
    ));
    assert!(kernel.enqueue_event("key", vec![VmValue::Bool(true)]));

    let event = kernel.try_pull_event(Some("key")).unwrap();

    assert_eq!(event.name, "key");
    assert_eq!(kernel.event_arg_count(event.id), 1);
    assert!(kernel.event_arg_bool(event.id, 0));
}

#[test]
fn register_runner_halts_with_unit() {
    let image = image(
        vec![],
        vec![],
        vec![function("main", 0, 0, vec![return_unit()])],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Unit),
    );
}

#[test]
fn register_runner_executes_integer_arithmetic() {
    let image = image(
        vec![ConstantFixture::Int(2), ConstantFixture::Int(5)],
        vec![],
        vec![function(
            "main",
            3,
            0,
            vec![
                load_const(0, 0),
                load_const(1, 1),
                i32_add(2, 0, 1),
                return_register(2),
            ],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Int(7)),
    );
}

#[test]
fn register_runner_jumps_by_instruction_index() {
    let image = image(
        vec![ConstantFixture::Int(1), ConstantFixture::Int(2)],
        vec![],
        vec![function(
            "main",
            3,
            0,
            vec![
                load_bool(0, false),
                jump_if_false(0, 4),
                load_const(1, 0),
                return_register(1),
                load_const(2, 1),
                return_register(2),
            ],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Int(2)),
    );
}

#[test]
fn register_runner_calls_static_functions() {
    let image = image(
        vec![ConstantFixture::Int(2), ConstantFixture::Int(5)],
        vec![],
        vec![
            function(
                "main",
                3,
                0,
                vec![
                    load_const(0, 0),
                    load_const(1, 1),
                    call_static(Some(2), 1, &[0, 1]),
                    return_register(2),
                ],
            ),
            function("add", 3, 2, vec![i32_add(2, 0, 1), return_register(2)]),
        ],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Int(7)),
    );
}

#[test]
fn register_runner_pauses_after_instruction_budget() {
    let image = image(
        vec![ConstantFixture::Int(7)],
        vec![],
        vec![function(
            "main",
            1,
            0,
            vec![load_const(0, 0), return_register(0)],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 1).unwrap();

    assert_eq!(handle.run_until_signal_decoded().unwrap(), VmSignal::Pause);
    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Int(7)),
    );
}

#[test]
fn register_runner_yields_and_resumes() {
    let image = image(
        vec![],
        vec![],
        vec![function(
            "main",
            1,
            0,
            vec![yield_instruction(0), return_unit()],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(handle.run_until_signal_decoded().unwrap(), VmSignal::Yield);
    handle.resume_with_value(VmValue::Unit).unwrap();
    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Unit),
    );
}

#[test]
fn register_runner_sleeps_with_int_ticks_and_resumes() {
    let image = image(
        vec![ConstantFixture::Int(3)],
        vec![],
        vec![function(
            "main",
            2,
            0,
            vec![load_const(0, 0), sleep_instruction(1, 0), return_unit()],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Sleep(3)
    );
    handle.resume_with_value(VmValue::Unit).unwrap();
    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Unit),
    );
}

#[test]
fn register_runner_emits_jvm_owned_hostcall_and_resumes() {
    let image = image(
        vec![ConstantFixture::String("hello".to_string())],
        vec![HostImportFixture::new(
            3004,
            "system",
            "log",
            vec!["String"],
            "Unit",
        )],
        vec![function(
            "main",
            2,
            0,
            vec![
                load_const(0, 0),
                call_host(Some(1), 3004, &[0]),
                return_unit(),
            ],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::HostCall {
            module_name: "system".to_string(),
            function_name: "log".to_string(),
            arguments: vec![VmValue::String("hello".to_string())],
        },
    );
    handle.resume_with_value(VmValue::Unit).unwrap();
    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Unit),
    );
}

#[test]
fn attached_kernel_handles_system_device_id_without_hostcall_signal() {
    let image = image(
        vec![],
        vec![HostImportFixture::new(
            3000,
            "system",
            "deviceId",
            vec![],
            "Int",
        )],
        vec![function(
            "main",
            1,
            0,
            vec![call_host(Some(0), 3000, &[]), return_register(0)],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();
    handle
        .attach_device_kernel(Arc::new(
            DeviceRuntimeKernelHandle::new_with_system_identity(8, 1024, 42, "test".to_string()),
        ))
        .unwrap();

    assert_eq!(
        handle.run_until_signal_decoded().unwrap(),
        VmSignal::Halt(VmValue::Int(42)),
    );
}

#[test]
fn native_owned_unknown_host_import_fails_fast() {
    let image = image(
        vec![],
        vec![HostImportFixture::new(
            1,
            "filesystem",
            "unknown",
            vec![],
            "Unit",
        )],
        vec![function(
            "main",
            1,
            0,
            vec![call_host(Some(0), 1, &[]), return_unit()],
        )],
        0,
    );
    let mut handle = ImageVmHandle::create(&image, 128).unwrap();

    let error = handle.run_until_signal_decoded().unwrap_err();

    assert!(error.contains("Kotlin fallback is disabled"));
}

fn image(
    constants: Vec<ConstantFixture>,
    host_imports: Vec<HostImportFixture>,
    functions: Vec<FunctionFixture>,
    entry_function_index: i32,
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"CKIM");
    out.push(2);
    string(&mut out, "ckl-1");
    i32(&mut out, constants.len() as i32);
    for constant in constants {
        constant.write_to(&mut out);
    }
    i32(&mut out, host_imports.len() as i32);
    for host_import in host_imports {
        host_import.write_to(&mut out);
    }
    i32(&mut out, entry_function_index);
    i32(&mut out, functions.len() as i32);
    for function in functions {
        function.write_to(&mut out);
    }
    out
}

fn function(
    name: &'static str,
    register_count: u16,
    parameter_count: u16,
    instructions: Vec<Vec<u8>>,
) -> FunctionFixture {
    FunctionFixture {
        name,
        register_count,
        parameter_count,
        instructions,
    }
}

struct FunctionFixture {
    name: &'static str,
    register_count: u16,
    parameter_count: u16,
    instructions: Vec<Vec<u8>>,
}

impl FunctionFixture {
    fn write_to(self, out: &mut Vec<u8>) {
        string(out, self.name);
        u16(out, self.register_count);
        u16(out, self.parameter_count);
        i32(out, self.instructions.len() as i32);
        for instruction in self.instructions {
            out.extend_from_slice(&instruction);
        }
    }
}

enum ConstantFixture {
    Int(i32),
    String(String),
}

impl ConstantFixture {
    fn write_to(self, out: &mut Vec<u8>) {
        match self {
            ConstantFixture::Int(value) => {
                out.push(2);
                i32(out, value);
            }
            ConstantFixture::String(value) => {
                out.push(1);
                string(out, &value);
            }
        }
    }
}

struct HostImportFixture {
    id: i32,
    module_name: &'static str,
    function_name: &'static str,
    parameter_types: Vec<&'static str>,
    return_type: &'static str,
}

impl HostImportFixture {
    fn new(
        id: i32,
        module_name: &'static str,
        function_name: &'static str,
        parameter_types: Vec<&'static str>,
        return_type: &'static str,
    ) -> Self {
        Self {
            id,
            module_name,
            function_name,
            parameter_types,
            return_type,
        }
    }

    fn write_to(self, out: &mut Vec<u8>) {
        i32(out, self.id);
        string(out, self.module_name);
        string(out, self.function_name);
        i32(out, self.parameter_types.len() as i32);
        for parameter_type in self.parameter_types {
            string(out, parameter_type);
        }
        string(out, self.return_type);
    }
}

fn load_const(dst: u16, constant_index: i32) -> Vec<u8> {
    let mut out = vec![1];
    u16(&mut out, dst);
    i32(&mut out, constant_index);
    out
}

fn load_bool(dst: u16, value: bool) -> Vec<u8> {
    let mut out = vec![4];
    u16(&mut out, dst);
    out.push(if value { 1 } else { 0 });
    out
}

fn i32_add(dst: u16, lhs: u16, rhs: u16) -> Vec<u8> {
    binary(6, dst, lhs, rhs)
}

fn jump_if_false(cond: u16, target: i32) -> Vec<u8> {
    let mut out = vec![27];
    u16(&mut out, cond);
    i32(&mut out, target);
    out
}

fn call_static(return_register: Option<u16>, function_index: i32, arguments: &[u16]) -> Vec<u8> {
    let mut out = vec![29];
    optional_register(&mut out, return_register);
    i32(&mut out, function_index);
    register_list(&mut out, arguments);
    out
}

fn return_register(src: u16) -> Vec<u8> {
    let mut out = vec![30];
    u16(&mut out, src);
    out
}

fn return_unit() -> Vec<u8> {
    vec![31]
}

fn call_host(return_register: Option<u16>, import_id: i32, arguments: &[u16]) -> Vec<u8> {
    let mut out = vec![32];
    optional_register(&mut out, return_register);
    i32(&mut out, import_id);
    register_list(&mut out, arguments);
    out
}

fn yield_instruction(dst: u16) -> Vec<u8> {
    let mut out = vec![33];
    u16(&mut out, dst);
    out
}

fn sleep_instruction(dst: u16, ticks: u16) -> Vec<u8> {
    let mut out = vec![34];
    u16(&mut out, dst);
    u16(&mut out, ticks);
    out
}

fn binary(tag: u8, dst: u16, lhs: u16, rhs: u16) -> Vec<u8> {
    let mut out = vec![tag];
    u16(&mut out, dst);
    u16(&mut out, lhs);
    u16(&mut out, rhs);
    out
}

fn optional_register(out: &mut Vec<u8>, register: Option<u16>) {
    match register {
        Some(register) => {
            out.push(1);
            u16(out, register);
        }
        None => out.push(0),
    }
}

fn register_list(out: &mut Vec<u8>, registers: &[u16]) {
    i32(out, registers.len() as i32);
    for register in registers {
        u16(out, *register);
    }
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
