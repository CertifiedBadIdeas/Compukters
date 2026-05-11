use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::image::{
    decode_image, Constant, Function, HostImport, Image, Instruction, TypedRegister,
};
use crate::runtime_kernel::{DeviceRuntimeKernelHandle, ProcessStatus};
use crate::signal::{decode_value, encode_error, encode_signal, VmSignal};
use crate::value::VmValue;

const TIME_CHECK_INTERVAL: usize = 1024;

const DISPLAY_PRIMARY_IMPORT_ID: i32 = 1000;
const DISPLAY_IS_ATTACHED_IMPORT_ID: i32 = 1001;
const DISPLAY_WIDTH_IMPORT_ID: i32 = 1002;
const DISPLAY_HEIGHT_IMPORT_ID: i32 = 1003;
const DISPLAY_CLEAR_IMPORT_ID: i32 = 1004;
const DISPLAY_SET_PIXEL_IMPORT_ID: i32 = 1005;
const DISPLAY_FILL_RECT_IMPORT_ID: i32 = 1006;
const DISPLAY_COPY_RECT_IMPORT_ID: i32 = 1007;
const DISPLAY_BLIT_MONO_IMPORT_ID: i32 = 1008;
const DISPLAY_BLIT_MONO_5X7_IMPORT_ID: i32 = 1009;
const DISPLAY_BLIT_MONO_5X7_PACKED_IMPORT_ID: i32 = 1010;
const DISPLAY_PRESENT_IMPORT_ID: i32 = 1011;
const DISPLAY_BLIT_MONO_5X7_TEXT_IMPORT_ID: i32 = 1012;

const FILESYSTEM_EXISTS_IMPORT_ID: i32 = 2000;
const FILESYSTEM_READ_TEXT_IMPORT_ID: i32 = 2001;
const FILESYSTEM_IS_DIRECTORY_IMPORT_ID: i32 = 2002;
const FILESYSTEM_WRITE_TEXT_IMPORT_ID: i32 = 2003;
const FILESYSTEM_MAKE_DIR_IMPORT_ID: i32 = 2004;
const FILESYSTEM_REMOVE_IMPORT_ID: i32 = 2005;
const FILESYSTEM_LIST_IMPORT_ID: i32 = 2006;
const FILESYSTEM_LIST_PATH_IMPORT_ID: i32 = 2007;

const SYSTEM_DEVICE_ID_IMPORT_ID: i32 = 3000;
const SYSTEM_CURRENT_TICK_IMPORT_ID: i32 = 3001;
const SYSTEM_LABEL_IMPORT_ID: i32 = 3002;
const SYSTEM_PROFILE_NAME_IMPORT_ID: i32 = 3003;
const SYSTEM_LOG_IMPORT_ID: i32 = 3004;
const SYSTEM_SHUTDOWN_IMPORT_ID: i32 = 3005;
const SYSTEM_REBOOT_IMPORT_ID: i32 = 3006;

const EVENTS_TRY_PULL_IMPORT_ID: i32 = 4002;
const EVENTS_TRY_PULL_FILTER_IMPORT_ID: i32 = 4003;
const EVENTS_ARG_COUNT_IMPORT_ID: i32 = 4004;
const EVENTS_ARG_INT_IMPORT_ID: i32 = 4005;
const EVENTS_ARG_BOOL_IMPORT_ID: i32 = 4006;
const EVENTS_ARG_STRING_IMPORT_ID: i32 = 4007;

const IPC_OPEN_IMPORT_ID: i32 = 5000;
const IPC_WRITE_IMPORT_ID: i32 = 5001;
const IPC_READ_IMPORT_ID: i32 = 5002;
const IPC_TRY_READ_IMPORT_ID: i32 = 5003;
const IPC_CLOSE_IMPORT_ID: i32 = 5004;

const PROCESS_CURRENT_DIRECTORY_IMPORT_ID: i32 = 6000;
const PROCESS_ARGUMENT_IMPORT_ID: i32 = 6001;
const PROCESS_CHANGE_DIRECTORY_IMPORT_ID: i32 = 6002;
const PROCESS_RUN_IMPORT_ID: i32 = 6003;
const PROCESS_RUN_WITH_ARGUMENT_IMPORT_ID: i32 = 6004;
const PROCESS_SPAWN_IMPORT_ID: i32 = 6005;
const PROCESS_SPAWN_WITH_ARGUMENT_IMPORT_ID: i32 = 6006;
const PROCESS_WAIT_IMPORT_ID: i32 = 6007;

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

const RUNTIME_POLL_IMPORT_ID: i32 = 8000;

pub const IMAGE_OPCODE_METRIC_COUNT: usize = 42;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageVmMetrics {
    pub executed_instructions: u64,
    pub instruction_clones: u64,
    pub opcode_counts: [u64; IMAGE_OPCODE_METRIC_COUNT],
    pub value_clones: u64,
    pub register_reads: u64,
    pub register_writes: u64,
    pub function_calls: u64,
    pub function_returns: u64,
    pub host_call_attempts: u64,
    pub native_host_calls: u64,
    pub jvm_host_call_signals: u64,
    pub pause_signals: u64,
    pub string_allocations: u64,
    pub record_allocations: u64,
}

impl Default for ImageVmMetrics {
    fn default() -> Self {
        Self {
            executed_instructions: 0,
            instruction_clones: 0,
            opcode_counts: [0; IMAGE_OPCODE_METRIC_COUNT],
            value_clones: 0,
            register_reads: 0,
            register_writes: 0,
            function_calls: 0,
            function_returns: 0,
            host_call_attempts: 0,
            native_host_calls: 0,
            jvm_host_call_signals: 0,
            pause_signals: 0,
            string_allocations: 0,
            record_allocations: 0,
        }
    }
}

impl ImageVmMetrics {
    pub fn opcode_count(&self, opcode: u8) -> u64 {
        self.opcode_counts
            .get(usize::from(opcode))
            .copied()
            .unwrap_or(0)
    }

    fn record_opcode(&mut self, opcode: u8) {
        self.executed_instructions = self.executed_instructions.saturating_add(1);
        if let Some(count) = self.opcode_counts.get_mut(usize::from(opcode)) {
            *count = count.saturating_add(1);
        }
    }
}

struct CallFrame {
    function_index: usize,
    instruction_pointer: usize,
    i32_base: usize,
    i64_base: usize,
    bool_base: usize,
    ref_base: usize,
    return_register: Option<TypedRegister>,
}

pub struct ImageVmHandle {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    i32_base: usize,
    i64_base: usize,
    bool_base: usize,
    ref_base: usize,
    i32_registers: Vec<i32>,
    i64_registers: Vec<i64>,
    bool_registers: Vec<bool>,
    ref_registers: Vec<VmValue>,
    call_stack: Vec<CallFrame>,
    attached_kernel: Option<Arc<DeviceRuntimeKernelHandle>>,
    working_directory: String,
    process_argument: Option<String>,
    slice_budget: Duration,
    instructions_since_time_check: usize,
    pending_resume_register: Option<TypedRegister>,
    state: ImageVmState,
    metrics: ImageVmMetrics,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ImageVmState {
    Ready,
    WaitingForResume,
    Halted,
}

enum NativeHostImportResult {
    Handled(VmValue),
    ExternalHostCall(Vec<VmValue>),
    SignalNoResume {
        signal: VmSignal,
        arguments: Vec<VmValue>,
    },
}

impl ImageVmHandle {
    pub fn create(image: &[u8], slice_budget_nanos: u64) -> Result<Self, String> {
        let image = decode_image(image).map_err(|error| error.to_string())?;
        let function_index = checked_entry_function_index(&image)?;
        let entry_function = &image.functions[function_index];
        let i32_register_count = entry_function.i32_register_count;
        let i64_register_count = entry_function.i64_register_count;
        let bool_register_count = entry_function.bool_register_count;
        let ref_register_count = entry_function.ref_register_count;
        Ok(Self {
            image,
            function_index,
            instruction_pointer: 0,
            i32_base: 0,
            i64_base: 0,
            bool_base: 0,
            ref_base: 0,
            i32_registers: vec![0; i32_register_count],
            i64_registers: vec![0; i64_register_count],
            bool_registers: vec![false; bool_register_count],
            ref_registers: vec![VmValue::Unit; ref_register_count],
            call_stack: Vec::new(),
            attached_kernel: None,
            working_directory: String::new(),
            process_argument: None,
            slice_budget: Duration::from_nanos(slice_budget_nanos.max(1)),
            instructions_since_time_check: 0,
            pending_resume_register: None,
            state: ImageVmState::Ready,
            metrics: ImageVmMetrics::default(),
        })
    }

    pub fn attach_device_kernel(
        &mut self,
        kernel: Arc<DeviceRuntimeKernelHandle>,
    ) -> Result<(), String> {
        self.attached_kernel = Some(kernel);
        Ok(())
    }

    pub fn set_working_directory(&mut self, working_directory: String) {
        self.working_directory = normalize_working_directory(&working_directory);
    }

    pub fn set_process_argument(&mut self, argument: String) {
        self.process_argument = Some(argument);
    }

    pub fn working_directory(&self) -> &str {
        &self.working_directory
    }

    pub fn run_until_signal(&mut self) -> Vec<u8> {
        match catch_unwind(AssertUnwindSafe(|| self.run_until_signal_inner())) {
            Ok(Ok(signal)) => encode_signal(&signal),
            Ok(Err(error)) => encode_error(error),
            Err(payload) => encode_error(panic_message(payload)),
        }
    }

    pub fn run_until_signal_decoded(&mut self) -> Result<VmSignal, String> {
        match catch_unwind(AssertUnwindSafe(|| self.run_until_signal_inner())) {
            Ok(result) => result,
            Err(payload) => Err(panic_message(payload)),
        }
    }

    pub fn resume_with_value_bytes(&mut self, value: &[u8]) -> Result<(), String> {
        self.resume_with_value(decode_value(value)?)
    }

    pub fn resume_with_value(&mut self, value: VmValue) -> Result<(), String> {
        if self.state != ImageVmState::WaitingForResume {
            return Err("native image VM is not waiting for resume".to_string());
        }
        if let Some(register) = self.pending_resume_register.take() {
            self.write_typed_register(register, value)?;
        }
        self.state = ImageVmState::Ready;
        Ok(())
    }

    pub fn metrics_snapshot(&self) -> ImageVmMetrics {
        self.metrics.clone()
    }

    fn try_native_host_import(
        &mut self,
        import_id: i32,
        arguments: Vec<VmValue>,
    ) -> Result<NativeHostImportResult, String> {
        match self.try_attached_kernel_host_import(import_id, arguments)? {
            NativeHostImportResult::Handled(value) => Ok(NativeHostImportResult::Handled(value)),
            NativeHostImportResult::ExternalHostCall(arguments) => {
                try_builtin_native_host_import(import_id, arguments)
            }
            signal @ NativeHostImportResult::SignalNoResume { .. } => Ok(signal),
        }
    }

    fn try_attached_kernel_host_import(
        &mut self,
        import_id: i32,
        arguments: Vec<VmValue>,
    ) -> Result<NativeHostImportResult, String> {
        let Some(kernel_handle) = self.attached_kernel.as_ref() else {
            return Ok(NativeHostImportResult::ExternalHostCall(arguments));
        };

        match import_id {
            SYSTEM_DEVICE_ID_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel.device_id(),
                )))
            }
            SYSTEM_PROFILE_NAME_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                Ok(NativeHostImportResult::Handled(VmValue::String(
                    kernel.profile_name().to_string(),
                )))
            }
            FILESYSTEM_EXISTS_IMPORT_ID
            | FILESYSTEM_IS_DIRECTORY_IMPORT_ID
            | FILESYSTEM_READ_TEXT_IMPORT_ID
            | FILESYSTEM_WRITE_TEXT_IMPORT_ID
            | FILESYSTEM_MAKE_DIR_IMPORT_ID
            | FILESYSTEM_REMOVE_IMPORT_ID
            | FILESYSTEM_LIST_IMPORT_ID
            | FILESYSTEM_LIST_PATH_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let Some(filesystem) = kernel.filesystem.as_ref() else {
                    return Ok(NativeHostImportResult::ExternalHostCall(arguments));
                };
                match import_id {
                    FILESYSTEM_EXISTS_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.exists path")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(
                            filesystem.exists(&self.working_directory, path)?,
                        )))
                    }
                    FILESYSTEM_IS_DIRECTORY_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.isDirectory path")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(
                            filesystem.is_directory(&self.working_directory, path)?,
                        )))
                    }
                    FILESYSTEM_READ_TEXT_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.readText path")?;
                        Ok(NativeHostImportResult::Handled(VmValue::String(
                            filesystem.read_text(&self.working_directory, path)?,
                        )))
                    }
                    FILESYSTEM_WRITE_TEXT_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.writeText path")?;
                        let text = string_argument(&arguments, 1, "filesystem.writeText text")?;
                        filesystem.write_text(&self.working_directory, path, text)?;
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    FILESYSTEM_MAKE_DIR_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.makeDir path")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(
                            filesystem.make_dir(&self.working_directory, path)?,
                        )))
                    }
                    FILESYSTEM_REMOVE_IMPORT_ID => {
                        let path = string_argument(&arguments, 0, "filesystem.remove path")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(
                            filesystem.remove(&self.working_directory, path)?,
                        )))
                    }
                    FILESYSTEM_LIST_IMPORT_ID | FILESYSTEM_LIST_PATH_IMPORT_ID => {
                        let path = arguments
                            .first()
                            .map(|_| string_argument(&arguments, 0, "filesystem.list path"))
                            .transpose()?
                            .unwrap_or("");
                        Ok(NativeHostImportResult::Handled(VmValue::String(
                            filesystem.list(&self.working_directory, path)?,
                        )))
                    }
                    _ => unreachable!("filesystem import id was pre-matched"),
                }
            }
            EVENTS_TRY_PULL_IMPORT_ID | EVENTS_TRY_PULL_FILTER_IMPORT_ID => {
                let mut kernel = kernel_handle.lock()?;
                let filter = arguments
                    .first()
                    .map(|_| string_argument(&arguments, 0, "events.tryPull filter"))
                    .transpose()?;
                let value = kernel
                    .try_pull_event(filter)
                    .map(|event| event_record(event.name, event.id, event.arg_count))
                    .unwrap_or_else(empty_event_record);
                Ok(NativeHostImportResult::Handled(value))
            }
            EVENTS_ARG_COUNT_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let event_id = event_id_argument(&arguments, 0, "events.argCount event")?;
                Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel.event_arg_count(event_id),
                )))
            }
            EVENTS_ARG_INT_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let event_id = event_id_argument(&arguments, 0, "events.argInt event")?;
                let index = int_argument(&arguments, 1, "events.argInt index")?;
                Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel.event_arg_int(event_id, index),
                )))
            }
            EVENTS_ARG_BOOL_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let event_id = event_id_argument(&arguments, 0, "events.argBool event")?;
                let index = int_argument(&arguments, 1, "events.argBool index")?;
                Ok(NativeHostImportResult::Handled(VmValue::Bool(
                    kernel.event_arg_bool(event_id, index),
                )))
            }
            EVENTS_ARG_STRING_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let event_id = event_id_argument(&arguments, 0, "events.argString event")?;
                let index = int_argument(&arguments, 1, "events.argString index")?;
                Ok(NativeHostImportResult::Handled(VmValue::String(
                    kernel.event_arg_string(event_id, index),
                )))
            }
            IPC_OPEN_IMPORT_ID => Ok(NativeHostImportResult::Handled(VmValue::Int(
                kernel_handle.with_kernel_mut(|kernel| kernel.open_ipc_channel())??,
            ))),
            IPC_WRITE_IMPORT_ID => {
                let channel = int_argument(&arguments, 0, "ipc.write channel")?;
                let text = string_argument(&arguments, 1, "ipc.write text")?;
                kernel_handle.with_kernel_mut(|kernel| kernel.write_ipc(channel, text))??;
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            IPC_READ_IMPORT_ID => {
                let channel = int_argument(&arguments, 0, "ipc.read channel")?;
                let mut kernel = kernel_handle.lock()?;
                let text = kernel.try_read_ipc(channel)?;
                if !text.is_empty() {
                    return Ok(NativeHostImportResult::Handled(VmValue::String(text)));
                }
                let wake_sequence = kernel.wake_sequence();
                Ok(NativeHostImportResult::SignalNoResume {
                    signal: VmSignal::WaitPoll {
                        channel,
                        wake_sequence,
                    },
                    arguments,
                })
            }
            IPC_TRY_READ_IMPORT_ID => {
                let channel = int_argument(&arguments, 0, "ipc.tryRead channel")?;
                let mut kernel = kernel_handle.lock()?;
                Ok(NativeHostImportResult::Handled(VmValue::String(
                    kernel.try_read_ipc(channel)?,
                )))
            }
            IPC_CLOSE_IMPORT_ID => {
                let channel = int_argument(&arguments, 0, "ipc.close channel")?;
                kernel_handle.with_kernel_mut(|kernel| kernel.close_ipc(channel))??;
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            RUNTIME_POLL_IMPORT_ID => {
                let mut kernel = kernel_handle.lock()?;
                let channel = int_argument(&arguments, 0, "runtime.poll channel")?;
                let text = kernel.try_read_ipc(channel)?;
                if !text.is_empty() {
                    return Ok(NativeHostImportResult::Handled(poll_record(
                        "ipc",
                        text,
                        empty_event_record(),
                    )));
                }
                if let Some(event) = kernel.try_pull_event(None) {
                    return Ok(NativeHostImportResult::Handled(poll_record(
                        "event",
                        String::new(),
                        event_record(event.name, event.id, event.arg_count),
                    )));
                }
                let wake_sequence = kernel.wake_sequence();
                Ok(NativeHostImportResult::SignalNoResume {
                    signal: VmSignal::WaitPoll {
                        channel,
                        wake_sequence,
                    },
                    arguments,
                })
            }
            PROCESS_ARGUMENT_IMPORT_ID => self
                .process_argument
                .as_ref()
                .map(|argument| NativeHostImportResult::Handled(VmValue::String(argument.clone())))
                .map(Ok)
                .unwrap_or_else(|| Ok(NativeHostImportResult::ExternalHostCall(arguments))),
            PROCESS_CURRENT_DIRECTORY_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                if kernel.filesystem.is_none() {
                    return Ok(NativeHostImportResult::ExternalHostCall(arguments));
                }
                Ok(NativeHostImportResult::Handled(VmValue::String(
                    self.working_directory.clone(),
                )))
            }
            PROCESS_CHANGE_DIRECTORY_IMPORT_ID => {
                let path = string_argument(&arguments, 0, "process.changeDirectory path")?;
                let candidate = resolve_working_directory(&self.working_directory, path);
                let kernel = kernel_handle.lock()?;
                let Some(filesystem) = kernel.filesystem.as_ref() else {
                    return Ok(NativeHostImportResult::ExternalHostCall(arguments));
                };
                if filesystem.is_directory("", &candidate)? {
                    self.working_directory = candidate;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(true)))
                } else {
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(false)))
                }
            }
            PROCESS_WAIT_IMPORT_ID => {
                let kernel = kernel_handle.lock()?;
                let pid = int_argument(&arguments, 0, "process.wait pid")?;
                match kernel.process_status(pid) {
                    ProcessStatus::Completed(exit_code) => {
                        Ok(NativeHostImportResult::Handled(VmValue::Int(exit_code)))
                    }
                    ProcessStatus::Missing => Ok(NativeHostImportResult::Handled(VmValue::Int(1))),
                    ProcessStatus::Running => {
                        let wake_sequence = kernel.wake_sequence();
                        Ok(NativeHostImportResult::SignalNoResume {
                            signal: VmSignal::WaitProcess { pid, wake_sequence },
                            arguments,
                        })
                    }
                }
            }
            DISPLAY_PRIMARY_IMPORT_ID
            | DISPLAY_IS_ATTACHED_IMPORT_ID
            | DISPLAY_WIDTH_IMPORT_ID
            | DISPLAY_HEIGHT_IMPORT_ID
            | DISPLAY_CLEAR_IMPORT_ID
            | DISPLAY_SET_PIXEL_IMPORT_ID
            | DISPLAY_FILL_RECT_IMPORT_ID
            | DISPLAY_COPY_RECT_IMPORT_ID
            | DISPLAY_BLIT_MONO_IMPORT_ID
            | DISPLAY_BLIT_MONO_5X7_IMPORT_ID
            | DISPLAY_BLIT_MONO_5X7_PACKED_IMPORT_ID
            | DISPLAY_PRESENT_IMPORT_ID
            | DISPLAY_BLIT_MONO_5X7_TEXT_IMPORT_ID => {
                let mut kernel = kernel_handle.lock()?;
                if kernel.displays.first_display_id().is_none() {
                    return Ok(NativeHostImportResult::ExternalHostCall(arguments));
                }
                match import_id {
                    DISPLAY_PRIMARY_IMPORT_ID => Ok(NativeHostImportResult::Handled(VmValue::Int(
                        kernel.displays.first_display_id().unwrap_or(0),
                    ))),
                    DISPLAY_IS_ATTACHED_IMPORT_ID => {
                        let display_id =
                            int_argument(&arguments, 0, "display.isAttached displayId")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(
                            kernel.displays.is_attached(display_id),
                        )))
                    }
                    DISPLAY_WIDTH_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.width displayId")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Int(
                            kernel.displays.width(display_id).unwrap_or(0),
                        )))
                    }
                    DISPLAY_HEIGHT_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.height displayId")?;
                        Ok(NativeHostImportResult::Handled(VmValue::Int(
                            kernel.displays.height(display_id).unwrap_or(0),
                        )))
                    }
                    DISPLAY_CLEAR_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.clear displayId")?;
                        let rgb565 = int_argument(&arguments, 1, "display.clear rgb565")? as u16;
                        kernel.displays.clear(display_id, rgb565);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_SET_PIXEL_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.setPixel displayId")?;
                        let x = int_argument(&arguments, 1, "display.setPixel x")?;
                        let y = int_argument(&arguments, 2, "display.setPixel y")?;
                        let rgb565 = int_argument(&arguments, 3, "display.setPixel rgb565")? as u16;
                        kernel.displays.set_pixel(display_id, x, y, rgb565);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_FILL_RECT_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.fillRect displayId")?;
                        let x = int_argument(&arguments, 1, "display.fillRect x")?;
                        let y = int_argument(&arguments, 2, "display.fillRect y")?;
                        let width = int_argument(&arguments, 3, "display.fillRect width")?;
                        let height = int_argument(&arguments, 4, "display.fillRect height")?;
                        let rgb565 = int_argument(&arguments, 5, "display.fillRect rgb565")? as u16;
                        kernel
                            .displays
                            .fill_rect(display_id, x, y, width, height, rgb565);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_COPY_RECT_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.copyRect displayId")?;
                        let src_x = int_argument(&arguments, 1, "display.copyRect srcX")?;
                        let src_y = int_argument(&arguments, 2, "display.copyRect srcY")?;
                        let width = int_argument(&arguments, 3, "display.copyRect width")?;
                        let height = int_argument(&arguments, 4, "display.copyRect height")?;
                        let dst_x = int_argument(&arguments, 5, "display.copyRect dstX")?;
                        let dst_y = int_argument(&arguments, 6, "display.copyRect dstY")?;
                        kernel
                            .displays
                            .copy_rect(display_id, src_x, src_y, width, height, dst_x, dst_y);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_BLIT_MONO_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.blitMono displayId")?;
                        let x = int_argument(&arguments, 1, "display.blitMono x")?;
                        let y = int_argument(&arguments, 2, "display.blitMono y")?;
                        let width = int_argument(&arguments, 3, "display.blitMono width")?;
                        let height = int_argument(&arguments, 4, "display.blitMono height")?;
                        let mask = string_argument(&arguments, 5, "display.blitMono mask")?;
                        let foreground =
                            int_argument(&arguments, 6, "display.blitMono foreground")? as u16;
                        let background =
                            match int_argument(&arguments, 7, "display.blitMono background")? {
                                value if value < 0 => None,
                                value => Some(value as u16),
                            };
                        kernel.displays.blit_mono(
                            display_id, x, y, width, height, mask, foreground, background,
                        );
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_BLIT_MONO_5X7_IMPORT_ID => {
                        let display_id =
                            int_argument(&arguments, 0, "display.blitMono5x7 displayId")?;
                        let x = int_argument(&arguments, 1, "display.blitMono5x7 x")?;
                        let y = int_argument(&arguments, 2, "display.blitMono5x7 y")?;
                        let mut glyph = 0_u64;
                        for index in 0..7 {
                            let row =
                                int_argument(&arguments, 3 + index, "display.blitMono5x7 row")?
                                    as u64;
                            glyph = (glyph << 5) | (row & 0b11111);
                        }
                        let foreground =
                            int_argument(&arguments, 10, "display.blitMono5x7 foreground")? as u16;
                        let background =
                            match int_argument(&arguments, 11, "display.blitMono5x7 background")? {
                                value if value < 0 => None,
                                value => Some(value as u16),
                            };
                        kernel
                            .displays
                            .blit_mono5x7_packed(display_id, x, y, glyph, foreground, background);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_BLIT_MONO_5X7_PACKED_IMPORT_ID => {
                        let display_id =
                            int_argument(&arguments, 0, "display.blitMono5x7Packed displayId")?;
                        let x = int_argument(&arguments, 1, "display.blitMono5x7Packed x")?;
                        let y = int_argument(&arguments, 2, "display.blitMono5x7Packed y")?;
                        let glyph =
                            long_argument(&arguments, 3, "display.blitMono5x7Packed glyph")? as u64;
                        let foreground =
                            int_argument(&arguments, 4, "display.blitMono5x7Packed foreground")?
                                as u16;
                        let background = match int_argument(
                            &arguments,
                            5,
                            "display.blitMono5x7Packed background",
                        )? {
                            value if value < 0 => None,
                            value => Some(value as u16),
                        };
                        kernel
                            .displays
                            .blit_mono5x7_packed(display_id, x, y, glyph, foreground, background);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_PRESENT_IMPORT_ID => {
                        let display_id = int_argument(&arguments, 0, "display.present displayId")?;
                        drop(kernel);
                        kernel_handle.present_display(display_id)?;
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    DISPLAY_BLIT_MONO_5X7_TEXT_IMPORT_ID => {
                        let display_id =
                            int_argument(&arguments, 0, "display.blitMono5x7Text displayId")?;
                        let x = int_argument(&arguments, 1, "display.blitMono5x7Text x")?;
                        let y = int_argument(&arguments, 2, "display.blitMono5x7Text y")?;
                        let text = string_argument(&arguments, 3, "display.blitMono5x7Text text")?;
                        let foreground =
                            int_argument(&arguments, 4, "display.blitMono5x7Text foreground")?
                                as u16;
                        let background = match int_argument(
                            &arguments,
                            5,
                            "display.blitMono5x7Text background",
                        )? {
                            value if value < 0 => None,
                            value => Some(value as u16),
                        };
                        kernel
                            .displays
                            .blit_mono5x7_text(display_id, x, y, text, foreground, background);
                        Ok(NativeHostImportResult::Handled(VmValue::Unit))
                    }
                    _ => unreachable!("display import id was pre-matched"),
                }
            }
            _ => Ok(NativeHostImportResult::ExternalHostCall(arguments)),
        }
    }

    fn run_until_signal_inner(&mut self) -> Result<VmSignal, String> {
        match self.state {
            ImageVmState::Ready => {}
            ImageVmState::WaitingForResume => {
                return Err("native image VM is waiting for resume".to_string())
            }
            ImageVmState::Halted => return Err("native image VM is halted".to_string()),
        }

        let started_at = Instant::now();
        loop {
            let instruction_start = self.instruction_pointer;
            let instruction = match self
                .current_function()?
                .instructions
                .get(self.instruction_pointer)
                .cloned()
            {
                Some(instruction) => instruction,
                None => return self.halt(VmValue::Unit),
            };
            self.instruction_pointer += 1;
            self.instructions_since_time_check += 1;
            self.metrics.instruction_clones = self.metrics.instruction_clones.saturating_add(1);
            self.metrics.record_opcode(instruction_opcode(&instruction));

            match instruction {
                Instruction::I32Const {
                    dst,
                    constant_index,
                } => {
                    let value = self.constant_i32(constant_index)?;
                    self.write_i32_register(dst, value)?;
                }
                Instruction::I64Const {
                    dst,
                    constant_index,
                } => {
                    let value = self.constant_i64(constant_index)?;
                    self.write_i64_register(dst, value)?;
                }
                Instruction::BoolConst { dst, value } => self.write_bool_register(dst, value)?,
                Instruction::RefConst {
                    dst,
                    constant_index,
                } => {
                    let value = self.constant_ref_value(constant_index)?;
                    self.write_ref_register(dst, value)?;
                }
                Instruction::LoadUnit { dst } => self.write_ref_register(dst, VmValue::Unit)?,
                Instruction::LoadNull { dst } => self.write_ref_register(dst, VmValue::Null)?,
                Instruction::I32Move { dst, src } => {
                    let value = self.clone_i32_register(src)?;
                    self.write_i32_register(dst, value)?;
                }
                Instruction::I64Move { dst, src } => {
                    let value = self.clone_i64_register(src)?;
                    self.write_i64_register(dst, value)?;
                }
                Instruction::BoolMove { dst, src } => {
                    let value = self.clone_bool_register(src)?;
                    self.write_bool_register(dst, value)?;
                }
                Instruction::RefMove { dst, src } => {
                    let value = self.clone_ref_register(src)?;
                    self.write_ref_register(dst, value)?;
                }
                Instruction::I32Add { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a.wrapping_add(b))?
                }
                Instruction::I32Sub { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a.wrapping_sub(b))?
                }
                Instruction::I32Mul { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a.wrapping_mul(b))?
                }
                Instruction::I32Div { dst, lhs, rhs } => {
                    let right = self.clone_i32_register(rhs)?;
                    if right == 0 {
                        return Err("CkVmImage division by zero".to_string());
                    }
                    let left = self.clone_i32_register(lhs)?;
                    self.write_i32_register(dst, left.wrapping_div(right))?;
                }
                Instruction::I32Neg { dst, src } => {
                    let value = self.clone_i32_register(src)?.wrapping_neg();
                    self.write_i32_register(dst, value)?;
                }
                Instruction::I32BitAnd { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a & b)?
                }
                Instruction::I32BitOr { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a | b)?
                }
                Instruction::I32BitXor { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a ^ b)?
                }
                Instruction::I32BitNot { dst, src } => {
                    let value = !self.clone_i32_register(src)?;
                    self.write_i32_register(dst, value)?;
                }
                Instruction::I32Shl { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a.wrapping_shl(b as u32))?
                }
                Instruction::I32Shr { dst, lhs, rhs } => {
                    self.write_i32_binary(dst, lhs, rhs, |a, b| a.wrapping_shr(b as u32))?
                }
                Instruction::I32Eq { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a == b)?
                }
                Instruction::I32Ne { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a != b)?
                }
                Instruction::I32Lt { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a < b)?
                }
                Instruction::I32Le { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a <= b)?
                }
                Instruction::I32Gt { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a > b)?
                }
                Instruction::I32Ge { dst, lhs, rhs } => {
                    self.write_i32_compare(dst, lhs, rhs, |a, b| a >= b)?
                }
                Instruction::BoolNot { dst, src } => {
                    let value = !self.clone_bool_register(src)?;
                    self.write_bool_register(dst, value)?;
                }
                Instruction::BoolAnd { dst, lhs, rhs } => {
                    self.write_bool_binary(dst, lhs, rhs, |a, b| a && b)?
                }
                Instruction::BoolOr { dst, lhs, rhs } => {
                    self.write_bool_binary(dst, lhs, rhs, |a, b| a || b)?
                }
                Instruction::Jump { target } => self.jump_instruction(target)?,
                Instruction::JumpIfFalse { cond, target } => {
                    if !self.clone_bool_register(cond)? {
                        self.jump_instruction(target)?;
                    }
                }
                Instruction::JumpIfTrue { cond, target } => {
                    if self.clone_bool_register(cond)? {
                        self.jump_instruction(target)?;
                    }
                }
                Instruction::CallStatic {
                    return_register,
                    function_index,
                    arguments,
                } => self.call_static(function_index, return_register, &arguments)?,
                Instruction::Return { src } => {
                    let result = self.clone_typed_register(src)?;
                    if let Some(signal) = self.return_value(result)? {
                        return Ok(signal);
                    }
                }
                Instruction::ReturnUnit => {
                    if let Some(signal) = self.return_value(VmValue::Unit)? {
                        return Ok(signal);
                    }
                }
                Instruction::CallHost {
                    return_register,
                    import_id,
                    arguments,
                } => {
                    let argument_registers = arguments;
                    let arguments = self.typed_register_arguments(&argument_registers)?;
                    let import = self.host_import(import_id)?;
                    let module_name = import.module_name.clone();
                    let function_name = import.function_name.clone();
                    self.metrics.host_call_attempts =
                        self.metrics.host_call_attempts.saturating_add(1);
                    match self.try_native_host_import(import_id, arguments)? {
                        NativeHostImportResult::Handled(value) => {
                            self.metrics.native_host_calls =
                                self.metrics.native_host_calls.saturating_add(1);
                            if let Some(register) = return_register {
                                self.write_typed_register(register, value)?;
                            }
                        }
                        NativeHostImportResult::ExternalHostCall(arguments) => {
                            if !requires_external_host_call(import_id) {
                                return Err(format!(
                                    "native host import {module_name}.{function_name} id {import_id} is not implemented; external hostcall fallback is disabled"
                                ));
                            }
                            self.metrics.jvm_host_call_signals =
                                self.metrics.jvm_host_call_signals.saturating_add(1);
                            self.pending_resume_register = return_register;
                            self.state = ImageVmState::WaitingForResume;
                            return Ok(VmSignal::HostCall {
                                module_name,
                                function_name,
                                arguments,
                            });
                        }
                        NativeHostImportResult::SignalNoResume { signal, arguments } => {
                            self.metrics.native_host_calls =
                                self.metrics.native_host_calls.saturating_add(1);
                            self.instruction_pointer = instruction_start;
                            for (register, value) in
                                argument_registers.iter().copied().zip(arguments)
                            {
                                self.write_typed_register(register, value)?;
                            }
                            return Ok(signal);
                        }
                    }
                }
                Instruction::Yield { dst } => {
                    self.pending_resume_register = Some(TypedRegister::Ref(dst));
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Yield);
                }
                Instruction::Sleep { dst, ticks } => {
                    let ticks = self.sleep_ticks_register(ticks)?;
                    self.pending_resume_register = Some(TypedRegister::Ref(dst));
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Sleep(ticks));
                }
                Instruction::ConstructRecord {
                    dst,
                    type_name_constant_index,
                    field_name_constant_indices,
                    field_values,
                } => {
                    let type_name =
                        self.constant_string_value(type_name_constant_index, "record type name")?;
                    if field_name_constant_indices.len() != field_values.len() {
                        return Err(format!(
                            "CkVmImage record field-name count {} does not match value count {}",
                            field_name_constant_indices.len(),
                            field_values.len()
                        ));
                    }
                    let mut fields = Vec::with_capacity(field_values.len());
                    for (field_name_constant_index, field_value) in
                        field_name_constant_indices.into_iter().zip(field_values)
                    {
                        let field_name = self.constant_string_value(
                            field_name_constant_index,
                            "record field name",
                        )?;
                        fields.push((field_name, self.clone_typed_register(field_value)?));
                    }
                    self.metrics.record_allocations =
                        self.metrics.record_allocations.saturating_add(1);
                    self.write_ref_register(dst, VmValue::Record { type_name, fields })?;
                }
                Instruction::GetField {
                    dst,
                    receiver,
                    field_name_constant_index,
                } => {
                    let field_name =
                        self.constant_string_value(field_name_constant_index, "field name")?;
                    let value = match self.clone_ref_register(receiver)? {
                        VmValue::Record { type_name, fields } => fields
                            .into_iter()
                            .find(|(name, _)| name == &field_name)
                            .map(|(_, value)| value)
                            .ok_or_else(|| {
                                format!(
                                    "CkVmImage record `{type_name}` has no field `{field_name}`"
                                )
                            })?,
                        other => {
                            return Err(format!(
                                "CkVmImage GET_FIELD requires Record receiver but found {other:?}"
                            ))
                        }
                    };
                    self.write_typed_register(dst, value)?;
                }
            }

            if self.instructions_since_time_check >= TIME_CHECK_INTERVAL {
                self.instructions_since_time_check = 0;
                if started_at.elapsed() >= self.slice_budget {
                    self.metrics.pause_signals = self.metrics.pause_signals.saturating_add(1);
                    return Ok(VmSignal::Pause);
                }
            }
        }
    }

    fn halt(&mut self, value: VmValue) -> Result<VmSignal, String> {
        self.state = ImageVmState::Halted;
        Ok(VmSignal::Halt(value))
    }

    fn clone_i32_register(&mut self, register: u16) -> Result<i32, String> {
        self.metrics.register_reads = self.metrics.register_reads.saturating_add(1);
        let index = self.i32_register_index(register)?;
        self.i32_registers.get(index).copied().ok_or_else(|| {
            format!(
                "CkVmImage i32 register {register} is out of bounds for current register window"
            )
        })
    }

    fn write_i32_register(&mut self, register: u16, value: i32) -> Result<(), String> {
        let index = self.i32_register_index(register)?;
        let register_count = self.i32_registers.len().saturating_sub(self.i32_base);
        let slot = self.i32_registers.get_mut(index).ok_or_else(|| {
            format!(
                "CkVmImage i32 register {register} is out of bounds for current register window of {register_count} registers"
            )
        })?;
        self.metrics.register_writes = self.metrics.register_writes.saturating_add(1);
        *slot = value;
        Ok(())
    }

    fn clone_i64_register(&mut self, register: u16) -> Result<i64, String> {
        self.metrics.register_reads = self.metrics.register_reads.saturating_add(1);
        let index = self.i64_register_index(register)?;
        self.i64_registers.get(index).copied().ok_or_else(|| {
            format!(
                "CkVmImage i64 register {register} is out of bounds for current register window"
            )
        })
    }

    fn write_i64_register(&mut self, register: u16, value: i64) -> Result<(), String> {
        let index = self.i64_register_index(register)?;
        let register_count = self.i64_registers.len().saturating_sub(self.i64_base);
        let slot = self.i64_registers.get_mut(index).ok_or_else(|| {
            format!(
                "CkVmImage i64 register {register} is out of bounds for current register window of {register_count} registers"
            )
        })?;
        self.metrics.register_writes = self.metrics.register_writes.saturating_add(1);
        *slot = value;
        Ok(())
    }

    fn clone_bool_register(&mut self, register: u16) -> Result<bool, String> {
        self.metrics.register_reads = self.metrics.register_reads.saturating_add(1);
        let index = self.bool_register_index(register)?;
        self.bool_registers.get(index).copied().ok_or_else(|| {
            format!(
                "CkVmImage bool register {register} is out of bounds for current register window"
            )
        })
    }

    fn write_bool_register(&mut self, register: u16, value: bool) -> Result<(), String> {
        let index = self.bool_register_index(register)?;
        let register_count = self.bool_registers.len().saturating_sub(self.bool_base);
        let slot = self.bool_registers.get_mut(index).ok_or_else(|| {
            format!(
                "CkVmImage bool register {register} is out of bounds for current register window of {register_count} registers"
            )
        })?;
        self.metrics.register_writes = self.metrics.register_writes.saturating_add(1);
        *slot = value;
        Ok(())
    }

    fn clone_ref_register(&mut self, register: u16) -> Result<VmValue, String> {
        self.metrics.register_reads = self.metrics.register_reads.saturating_add(1);
        self.metrics.value_clones = self.metrics.value_clones.saturating_add(1);
        let index = self.ref_register_index(register)?;
        self.ref_registers.get(index).cloned().ok_or_else(|| {
            format!(
                "CkVmImage ref register {register} is out of bounds for current register window"
            )
        })
    }

    fn write_ref_register(&mut self, register: u16, value: VmValue) -> Result<(), String> {
        if matches!(value, VmValue::Int(_) | VmValue::Long(_) | VmValue::Bool(_)) {
            return Err(format!(
                "CkVmImage ref register {register} cannot store scalar value {value:?}"
            ));
        }
        let index = self.ref_register_index(register)?;
        let register_count = self.ref_registers.len().saturating_sub(self.ref_base);
        let slot = self.ref_registers.get_mut(index).ok_or_else(|| {
            format!(
                "CkVmImage ref register {register} is out of bounds for current register window of {register_count} registers"
            )
        })?;
        self.metrics.register_writes = self.metrics.register_writes.saturating_add(1);
        *slot = value;
        Ok(())
    }

    fn clone_typed_register(&mut self, register: TypedRegister) -> Result<VmValue, String> {
        match register {
            TypedRegister::I32(index) => Ok(VmValue::Int(self.clone_i32_register(index)?)),
            TypedRegister::I64(index) => Ok(VmValue::Long(self.clone_i64_register(index)?)),
            TypedRegister::Bool(index) => Ok(VmValue::Bool(self.clone_bool_register(index)?)),
            TypedRegister::Ref(index) => self.clone_ref_register(index),
        }
    }

    fn write_typed_register(
        &mut self,
        register: TypedRegister,
        value: VmValue,
    ) -> Result<(), String> {
        match (register, value) {
            (TypedRegister::I32(index), VmValue::Int(value)) => {
                self.write_i32_register(index, value)
            }
            (TypedRegister::I64(index), VmValue::Long(value)) => {
                self.write_i64_register(index, value)
            }
            (TypedRegister::Bool(index), VmValue::Bool(value)) => {
                self.write_bool_register(index, value)
            }
            (TypedRegister::Ref(index), value) => self.write_ref_register(index, value),
            (register, value) => Err(format!(
                "CkVmImage cannot write value {value:?} into typed register {register:?}"
            )),
        }
    }

    fn typed_register_arguments(
        &mut self,
        registers: &[TypedRegister],
    ) -> Result<Vec<VmValue>, String> {
        registers
            .iter()
            .map(|register| self.clone_typed_register(*register))
            .collect()
    }

    fn i32_register_index(&self, register: u16) -> Result<usize, String> {
        self.i32_base
            .checked_add(usize::from(register))
            .ok_or_else(|| format!("CkVmImage i32 register {register} offset overflow"))
    }

    fn i64_register_index(&self, register: u16) -> Result<usize, String> {
        self.i64_base
            .checked_add(usize::from(register))
            .ok_or_else(|| format!("CkVmImage i64 register {register} offset overflow"))
    }

    fn bool_register_index(&self, register: u16) -> Result<usize, String> {
        self.bool_base
            .checked_add(usize::from(register))
            .ok_or_else(|| format!("CkVmImage bool register {register} offset overflow"))
    }

    fn ref_register_index(&self, register: u16) -> Result<usize, String> {
        self.ref_base
            .checked_add(usize::from(register))
            .ok_or_else(|| format!("CkVmImage ref register {register} offset overflow"))
    }

    fn write_i32_binary(
        &mut self,
        dst: u16,
        lhs: u16,
        rhs: u16,
        op: fn(i32, i32) -> i32,
    ) -> Result<(), String> {
        let left = self.clone_i32_register(lhs)?;
        let right = self.clone_i32_register(rhs)?;
        self.write_i32_register(dst, op(left, right))
    }

    fn write_i32_compare(
        &mut self,
        dst: u16,
        lhs: u16,
        rhs: u16,
        op: fn(i32, i32) -> bool,
    ) -> Result<(), String> {
        let left = self.clone_i32_register(lhs)?;
        let right = self.clone_i32_register(rhs)?;
        self.write_bool_register(dst, op(left, right))
    }

    fn write_bool_binary(
        &mut self,
        dst: u16,
        lhs: u16,
        rhs: u16,
        op: fn(bool, bool) -> bool,
    ) -> Result<(), String> {
        let left = self.clone_bool_register(lhs)?;
        let right = self.clone_bool_register(rhs)?;
        self.write_bool_register(dst, op(left, right))
    }

    fn jump_instruction(&mut self, target: usize) -> Result<(), String> {
        let instruction_count = self.current_function()?.instructions.len();
        if target > instruction_count {
            return Err(format!(
                "CkVmImage jump target {target} is outside function instruction count {instruction_count}"
            ));
        }
        self.instruction_pointer = target;
        Ok(())
    }

    fn call_static(
        &mut self,
        function_index: usize,
        return_register: Option<TypedRegister>,
        argument_registers: &[TypedRegister],
    ) -> Result<(), String> {
        if function_index >= self.image.functions.len() {
            return Err(format!(
                "CkVmImage function index {function_index} is out of bounds for {} functions",
                self.image.functions.len()
            ));
        }
        let arguments = self.typed_register_arguments(argument_registers)?;
        let callee = &self.image.functions[function_index];
        if arguments.len() != callee.parameters.len() {
            return Err(format!(
                "CkVmImage function {function_index} expects {} arguments but got {}",
                callee.parameters.len(),
                arguments.len()
            ));
        }
        let parameter_registers = callee.parameters.clone();
        let i32_register_count = callee.i32_register_count;
        let i64_register_count = callee.i64_register_count;
        let bool_register_count = callee.bool_register_count;
        let ref_register_count = callee.ref_register_count;
        let caller_frame = CallFrame {
            function_index: self.function_index,
            instruction_pointer: self.instruction_pointer,
            i32_base: self.i32_base,
            i64_base: self.i64_base,
            bool_base: self.bool_base,
            ref_base: self.ref_base,
            return_register,
        };
        self.call_stack.push(caller_frame);
        self.metrics.function_calls = self.metrics.function_calls.saturating_add(1);
        self.function_index = function_index;
        self.instruction_pointer = 0;
        self.i32_base = self.i32_registers.len();
        self.i64_base = self.i64_registers.len();
        self.bool_base = self.bool_registers.len();
        self.ref_base = self.ref_registers.len();
        self.i32_registers
            .resize(self.i32_base + i32_register_count, 0);
        self.i64_registers
            .resize(self.i64_base + i64_register_count, 0);
        self.bool_registers
            .resize(self.bool_base + bool_register_count, false);
        self.ref_registers
            .resize(self.ref_base + ref_register_count, VmValue::Unit);
        for (register, argument) in parameter_registers.into_iter().zip(arguments) {
            self.write_typed_register(register, argument)?;
        }
        Ok(())
    }

    fn return_value(&mut self, result: VmValue) -> Result<Option<VmSignal>, String> {
        self.metrics.function_returns = self.metrics.function_returns.saturating_add(1);
        let callee_i32_base = self.i32_base;
        let callee_i64_base = self.i64_base;
        let callee_bool_base = self.bool_base;
        let callee_ref_base = self.ref_base;
        if let Some(frame) = self.call_stack.pop() {
            self.i32_registers.truncate(callee_i32_base);
            self.i64_registers.truncate(callee_i64_base);
            self.bool_registers.truncate(callee_bool_base);
            self.ref_registers.truncate(callee_ref_base);
            self.function_index = frame.function_index;
            self.instruction_pointer = frame.instruction_pointer;
            self.i32_base = frame.i32_base;
            self.i64_base = frame.i64_base;
            self.bool_base = frame.bool_base;
            self.ref_base = frame.ref_base;
            if let Some(return_register) = frame.return_register {
                self.write_typed_register(return_register, result)?;
            }
            Ok(None)
        } else {
            Ok(Some(self.halt(result)?))
        }
    }

    fn sleep_ticks_register(&mut self, register: TypedRegister) -> Result<i64, String> {
        match register {
            TypedRegister::I32(index) => Ok(i64::from(self.clone_i32_register(index)?)),
            TypedRegister::I64(index) => self.clone_i64_register(index),
            other => Err(format!(
                "CkVmImage SLEEP requires i32 or i64 ticks register but found {other:?}"
            )),
        }
    }

    fn constant_i32(&self, constant_index: usize) -> Result<i32, String> {
        match self.image.constants.get(constant_index) {
            Some(Constant::Int(value)) => Ok(*value),
            Some(other) => Err(format!(
                "CkVmImage constant index {constant_index} must be Int but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn constant_i64(&self, constant_index: usize) -> Result<i64, String> {
        match self.image.constants.get(constant_index) {
            Some(Constant::Long(value)) => Ok(*value),
            Some(other) => Err(format!(
                "CkVmImage constant index {constant_index} must be Long but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn constant_ref_value(&mut self, constant_index: usize) -> Result<VmValue, String> {
        match self.image.constants.get(constant_index) {
            Some(Constant::String(value)) => {
                self.metrics.string_allocations = self.metrics.string_allocations.saturating_add(1);
                Ok(VmValue::String(value.clone()))
            }
            Some(other) => Err(format!(
                "CkVmImage constant index {constant_index} must be reference-compatible but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn constant_string_value(
        &self,
        constant_index: usize,
        metadata_name: &str,
    ) -> Result<String, String> {
        match self.image.constants.get(constant_index) {
            Some(Constant::String(value)) => Ok(value.clone()),
            Some(other) => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} must be String metadata but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn host_import(&self, import_id: i32) -> Result<&HostImport, String> {
        self.image
            .host_imports
            .iter()
            .find(|import| import.id == import_id)
            .ok_or_else(|| format!("CkVmImage host import id {import_id} is not declared"))
    }

    fn current_function(&self) -> Result<&Function, String> {
        self.image
            .functions
            .get(self.function_index)
            .ok_or_else(|| {
                format!(
                    "CkVmImage function index {} is out of bounds",
                    self.function_index
                )
            })
    }
}

fn checked_entry_function_index(image: &Image) -> Result<usize, String> {
    let index = image.entry_function_index;
    if index >= image.functions.len() {
        return Err(format!(
            "CkVmImage entry function index {} is out of bounds for {} functions",
            image.entry_function_index,
            image.functions.len()
        ));
    }
    Ok(index)
}

fn instruction_opcode(instruction: &Instruction) -> u8 {
    match instruction {
        Instruction::I32Const { .. } => 1,
        Instruction::I64Const { .. } => 2,
        Instruction::BoolConst { .. } => 3,
        Instruction::RefConst { .. } => 4,
        Instruction::LoadUnit { .. } => 5,
        Instruction::LoadNull { .. } => 6,
        Instruction::I32Move { .. } => 7,
        Instruction::I64Move { .. } => 8,
        Instruction::BoolMove { .. } => 9,
        Instruction::RefMove { .. } => 10,
        Instruction::I32Add { .. } => 11,
        Instruction::I32Sub { .. } => 12,
        Instruction::I32Mul { .. } => 13,
        Instruction::I32Div { .. } => 14,
        Instruction::I32Neg { .. } => 15,
        Instruction::I32BitAnd { .. } => 16,
        Instruction::I32BitOr { .. } => 17,
        Instruction::I32BitXor { .. } => 18,
        Instruction::I32BitNot { .. } => 19,
        Instruction::I32Shl { .. } => 20,
        Instruction::I32Shr { .. } => 21,
        Instruction::I32Eq { .. } => 22,
        Instruction::I32Ne { .. } => 23,
        Instruction::I32Lt { .. } => 24,
        Instruction::I32Le { .. } => 25,
        Instruction::I32Gt { .. } => 26,
        Instruction::I32Ge { .. } => 27,
        Instruction::BoolNot { .. } => 28,
        Instruction::BoolAnd { .. } => 29,
        Instruction::BoolOr { .. } => 30,
        Instruction::Jump { .. } => 31,
        Instruction::JumpIfFalse { .. } => 32,
        Instruction::JumpIfTrue { .. } => 33,
        Instruction::CallStatic { .. } => 34,
        Instruction::Return { .. } => 35,
        Instruction::ReturnUnit => 36,
        Instruction::CallHost { .. } => 37,
        Instruction::Yield { .. } => 38,
        Instruction::Sleep { .. } => 39,
        Instruction::ConstructRecord { .. } => 40,
        Instruction::GetField { .. } => 41,
    }
}

fn requires_external_host_call(import_id: i32) -> bool {
    matches!(
        import_id,
        SYSTEM_CURRENT_TICK_IMPORT_ID
            | SYSTEM_LABEL_IMPORT_ID
            | SYSTEM_LOG_IMPORT_ID
            | SYSTEM_SHUTDOWN_IMPORT_ID
            | SYSTEM_REBOOT_IMPORT_ID
            | PROCESS_RUN_IMPORT_ID
            | PROCESS_RUN_WITH_ARGUMENT_IMPORT_ID
            | PROCESS_SPAWN_IMPORT_ID
            | PROCESS_SPAWN_WITH_ARGUMENT_IMPORT_ID
    )
}

fn try_builtin_native_host_import(
    import_id: i32,
    arguments: Vec<VmValue>,
) -> Result<NativeHostImportResult, String> {
    match import_id {
        STRINGS_TRIM_IMPORT_ID => native_string_unary(arguments, |text| {
            VmValue::String(text.trim_matches(|ch: char| ch.is_whitespace()).to_string())
        }),
        STRINGS_BEFORE_SPACE_IMPORT_ID => native_string_unary(arguments, |text| {
            let text = text.trim_start_matches(|ch: char| ch.is_whitespace());
            let before_space = match text.find(|ch: char| ch.is_whitespace()) {
                Some(index) => &text[..index],
                None => text,
            };
            VmValue::String(before_space.to_string())
        }),
        STRINGS_AFTER_SPACE_IMPORT_ID => native_string_unary(arguments, |text| {
            let text = text.trim_start_matches(|ch: char| ch.is_whitespace());
            let after_space = match text.find(|ch: char| ch.is_whitespace()) {
                Some(index) => {
                    let after = index
                        + text[index..]
                            .chars()
                            .next()
                            .map(char::len_utf8)
                            .unwrap_or(0);
                    text[after..].trim_start_matches(|ch: char| ch.is_whitespace())
                }
                None => "",
            };
            VmValue::String(after_space.to_string())
        }),
        STRINGS_IS_BLANK_IMPORT_ID => native_string_unary(arguments, |text| {
            VmValue::Bool(text.chars().all(|ch| ch.is_whitespace()))
        }),
        STRINGS_TO_INT_IMPORT_ID => native_string_unary(arguments, |text| {
            let value = text
                .trim_matches(|ch: char| ch.is_whitespace())
                .parse::<i32>()
                .unwrap_or(0);
            VmValue::Int(value)
        }),
        STRINGS_LENGTH_IMPORT_ID => {
            native_string_unary(arguments, |text| VmValue::Int(text.chars().count() as i32))
        }
        STRINGS_CHAR_AT_IMPORT_ID => native_string_char_at(arguments),
        STRINGS_REPEAT_IMPORT_ID => native_string_repeat(arguments),
        STRINGS_SLICE_IMPORT_ID => native_string_slice(arguments),
        STRINGS_REPLACE_RANGE_IMPORT_ID => native_string_replace_range(arguments),
        STRINGS_CHAR_CODE_AT_IMPORT_ID => native_string_char_code_at(arguments),
        _ => Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    }
}

fn empty_event_record() -> VmValue {
    event_record(String::new(), 0, 0)
}

fn event_record(name: String, id: i32, arg_count: i32) -> VmValue {
    VmValue::Record {
        type_name: "Event".to_string(),
        fields: vec![
            ("name".to_string(), VmValue::String(name)),
            ("id".to_string(), VmValue::Int(id)),
            ("argCount".to_string(), VmValue::Int(arg_count)),
        ],
    }
}

fn poll_record(kind: &str, text: String, event: VmValue) -> VmValue {
    VmValue::Record {
        type_name: "Poll".to_string(),
        fields: vec![
            ("kind".to_string(), VmValue::String(kind.to_string())),
            ("text".to_string(), VmValue::String(text)),
            ("event".to_string(), event),
        ],
    }
}

fn event_id_argument(arguments: &[VmValue], index: usize, context: &str) -> Result<i32, String> {
    let value = arguments
        .get(index)
        .ok_or_else(|| format!("{context} missing argument {index}"))?;
    let VmValue::Record { type_name, fields } = value else {
        return Err(format!("{context} requires Event but found {value:?}"));
    };
    if type_name != "Event" {
        return Err(format!("{context} requires Event but found {type_name}"));
    }
    for (name, value) in fields {
        if name == "id" {
            return match value {
                VmValue::Int(id) => Ok(*id),
                other => Err(format!(
                    "{context} Event.id requires Int but found {other:?}"
                )),
            };
        }
    }
    Ok(0)
}

fn int_argument(arguments: &[VmValue], index: usize, context: &str) -> Result<i32, String> {
    match arguments.get(index) {
        Some(VmValue::Int(value)) => Ok(*value),
        Some(other) => Err(format!("{context} requires Int but found {other:?}")),
        None => Err(format!("{context} missing argument {index}")),
    }
}

fn string_argument<'a>(
    arguments: &'a [VmValue],
    index: usize,
    context: &str,
) -> Result<&'a str, String> {
    match arguments.get(index) {
        Some(VmValue::String(value)) => Ok(value),
        Some(other) => Err(format!("{context} requires String but found {other:?}")),
        None => Err(format!("{context} missing argument {index}")),
    }
}

fn long_argument(arguments: &[VmValue], index: usize, context: &str) -> Result<i64, String> {
    match arguments.get(index) {
        Some(VmValue::Long(value)) => Ok(*value),
        Some(other) => Err(format!("{context} requires Long but found {other:?}")),
        None => Err(format!("{context} missing argument {index}")),
    }
}

fn native_string_unary(
    arguments: Vec<VmValue>,
    operation: fn(&str) -> VmValue,
) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 1 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    match &arguments[0] {
        VmValue::String(text) => Ok(NativeHostImportResult::Handled(operation(text))),
        _ => Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    }
}

fn native_string_char_at(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 2 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let index = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let value = if index < 0 {
        String::new()
    } else {
        text.chars()
            .nth(index as usize)
            .map(|ch| ch.to_string())
            .unwrap_or_default()
    };
    Ok(NativeHostImportResult::Handled(VmValue::String(value)))
}

fn native_string_char_code_at(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 2 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let index = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let value = if index < 0 {
        -1
    } else {
        text.chars()
            .nth(index as usize)
            .map(|ch| ch as i32)
            .unwrap_or(-1)
    };
    Ok(NativeHostImportResult::Handled(VmValue::Int(value)))
}

fn native_string_repeat(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 2 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let count = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let value = if count <= 0 {
        String::new()
    } else {
        text.repeat(count as usize)
    };
    Ok(NativeHostImportResult::Handled(VmValue::String(value)))
}

fn native_string_slice(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 3 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let start = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let end = match &arguments[2] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let len = text.chars().count() as i32;
    let start = start.clamp(0, len) as usize;
    let end = end.clamp(start as i32, len) as usize;
    Ok(NativeHostImportResult::Handled(VmValue::String(
        text.chars().skip(start).take(end - start).collect(),
    )))
}

fn native_string_replace_range(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 3 {
        return Ok(NativeHostImportResult::ExternalHostCall(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let start = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let replacement = match &arguments[2] {
        VmValue::String(value) => value,
        _ => return Ok(NativeHostImportResult::ExternalHostCall(arguments)),
    };
    let len = text.chars().count() as i32;
    let start = start.clamp(0, len) as usize;
    let replacement_len = replacement.chars().count();
    let end = (start + replacement_len).min(len as usize);
    let start_byte = scalar_to_byte_index(text, start);
    let end_byte = scalar_to_byte_index(text, end);
    let mut value = String::with_capacity(text.len().max(start_byte + replacement.len()));
    value.push_str(&text[..start_byte]);
    value.push_str(replacement);
    value.push_str(&text[end_byte..]);
    Ok(NativeHostImportResult::Handled(VmValue::String(value)))
}

fn scalar_to_byte_index(text: &str, scalar_index: usize) -> usize {
    text.char_indices()
        .nth(scalar_index)
        .map(|(byte_index, _)| byte_index)
        .unwrap_or(text.len())
}

fn normalize_working_directory(path: &str) -> String {
    let mut segments = Vec::new();
    for segment in path.trim().trim_matches('/').split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                let _ = segments.pop();
            }
            other => segments.push(other),
        }
    }
    segments.join("/")
}

fn resolve_working_directory(working_directory: &str, path: &str) -> String {
    let trimmed = path.trim();
    if trimmed.starts_with('/') {
        normalize_working_directory(trimmed)
    } else {
        normalize_working_directory(
            &[working_directory.trim_matches('/'), trimmed]
                .into_iter()
                .filter(|part| !part.is_empty())
                .collect::<Vec<_>>()
                .join("/"),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn image_runner_can_return_decoded_signal_for_daemon() {
        let image = encode_empty_main_image();
        let mut handle = ImageVmHandle::create(&image, 128).unwrap();

        let signal = handle.run_until_signal_decoded().unwrap();

        assert_eq!(signal, VmSignal::Halt(VmValue::Unit));
    }

    #[test]
    fn normalizes_native_filesystem_working_directory() {
        assert_eq!(normalize_working_directory("/a/./b/../c/"), "a/c");
    }

    #[test]
    fn resolves_relative_native_process_working_directory() {
        assert_eq!(resolve_working_directory("rom/bin", "../lib"), "rom/lib");
        assert_eq!(
            resolve_working_directory("rom/bin", "/tmp/./tools"),
            "tmp/tools"
        );
    }

    #[test]
    fn unsupported_host_import_errors_instead_of_signaling_external_hostcall() {
        let image = image_with_host_import_and_instructions(42, "legacy", "fallback", |out| {
            out.push(37);
            out.push(0);
            i32(out, 42);
            i32(out, 0);
        });
        let mut handle = ImageVmHandle::create(&image, 128).unwrap();

        let error = handle.run_until_signal_decoded().unwrap_err();

        assert!(error.contains("external hostcall fallback is disabled"));
        assert!(error.contains("legacy.fallback"));
    }

    fn encode_empty_main_image() -> Vec<u8> {
        image_with_instructions(0, 0, 0, 0, |out| {
            out.push(36);
        })
    }

    fn image_with_instructions(
        i32_register_count: u16,
        i64_register_count: u16,
        bool_register_count: u16,
        ref_register_count: u16,
        write_instructions: impl FnOnce(&mut Vec<u8>),
    ) -> Vec<u8> {
        image_with_sections(
            |out| i32(out, 0),
            i32_register_count,
            i64_register_count,
            bool_register_count,
            ref_register_count,
            write_instructions,
        )
    }

    fn image_with_host_import_and_instructions(
        import_id: i32,
        module_name: &str,
        function_name: &str,
        write_instructions: impl FnOnce(&mut Vec<u8>),
    ) -> Vec<u8> {
        image_with_sections(
            |out| {
                i32(out, 1);
                i32(out, import_id);
                string(out, module_name);
                string(out, function_name);
                i32(out, 0);
                string(out, "Unit");
            },
            0,
            0,
            0,
            0,
            write_instructions,
        )
    }

    fn image_with_sections(
        write_host_imports: impl FnOnce(&mut Vec<u8>),
        i32_register_count: u16,
        i64_register_count: u16,
        bool_register_count: u16,
        ref_register_count: u16,
        write_instructions: impl FnOnce(&mut Vec<u8>),
    ) -> Vec<u8> {
        let mut instructions = Vec::new();
        write_instructions(&mut instructions);
        let mut out = Vec::new();
        out.extend_from_slice(b"CKIM");
        out.push(3);
        string(&mut out, "ckl-1");
        i32(&mut out, 0);
        write_host_imports(&mut out);
        i32(&mut out, 0);
        i32(&mut out, 1);
        string(&mut out, "main");
        out.extend_from_slice(&i32_register_count.to_le_bytes());
        out.extend_from_slice(&i64_register_count.to_le_bytes());
        out.extend_from_slice(&bool_register_count.to_le_bytes());
        out.extend_from_slice(&ref_register_count.to_le_bytes());
        i32(&mut out, 0);
        i32(&mut out, 1);
        out.extend_from_slice(&instructions);
        out
    }

    fn string(out: &mut Vec<u8>, value: &str) {
        i32(out, value.len() as i32);
        out.extend_from_slice(value.as_bytes());
    }

    fn i32(out: &mut Vec<u8>, value: i32) {
        out.extend_from_slice(&value.to_le_bytes());
    }
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "native image VM panic".to_string()
    }
}
