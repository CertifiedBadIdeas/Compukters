use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use crate::image::{decode_image, Constant, Function, HostImport, Image};
use crate::runtime_kernel::{DeviceRuntimeKernelHandle, ProcessStatus};
use crate::signal::{decode_value, encode_error, encode_signal, VmSignal};
use crate::value::VmValue;

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

struct CallFrame {
    function_index: usize,
    instruction_pointer: usize,
    locals: Vec<VmValue>,
}

#[derive(Debug, Clone)]
enum HeapObject {
    Array(Vec<VmValue>),
    List(Vec<VmValue>),
    Map(Vec<(VmValue, VmValue)>),
}

pub struct ImageVmHandle {
    image: Image,
    function_index: usize,
    instruction_pointer: usize,
    stack: Vec<VmValue>,
    locals: Vec<VmValue>,
    call_stack: Vec<CallFrame>,
    objects: HashMap<u32, HeapObject>,
    next_object_id: u32,
    attached_kernel: Option<Arc<DeviceRuntimeKernelHandle>>,
    working_directory: String,
    process_argument: Option<String>,
    instruction_budget: usize,
    instructions_since_pause: usize,
    state: ImageVmState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ImageVmState {
    Ready,
    WaitingForResume,
    Halted,
}

enum NativeHostImportResult {
    Handled(VmValue),
    Fallback(Vec<VmValue>),
    SignalNoResume {
        signal: VmSignal,
        arguments: Vec<VmValue>,
    },
}

impl ImageVmHandle {
    pub fn create(image: &[u8], instruction_budget: usize) -> Result<Self, String> {
        let image = decode_image(image).map_err(|error| error.to_string())?;
        let function_index = checked_entry_function_index(&image)?;
        let frame_size = checked_frame_size(&image, function_index)?;
        Ok(Self {
            image,
            function_index,
            instruction_pointer: 0,
            stack: Vec::new(),
            locals: vec![VmValue::Unit; frame_size],
            call_stack: Vec::new(),
            objects: HashMap::new(),
            next_object_id: 1,
            attached_kernel: None,
            working_directory: String::new(),
            process_argument: None,
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
            state: ImageVmState::Ready,
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
        self.stack.push(value);
        self.state = ImageVmState::Ready;
        Ok(())
    }

    fn try_native_host_import(
        &mut self,
        import_id: i32,
        module_name: &str,
        function_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<NativeHostImportResult, String> {
        match self.try_attached_kernel_host_import(module_name, function_name, arguments)? {
            NativeHostImportResult::Handled(value) => Ok(NativeHostImportResult::Handled(value)),
            NativeHostImportResult::Fallback(arguments) => {
                try_builtin_native_host_import(import_id, module_name, arguments)
            }
            signal @ NativeHostImportResult::SignalNoResume { .. } => Ok(signal),
        }
    }

    fn try_attached_kernel_host_import(
        &mut self,
        module_name: &str,
        function_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<NativeHostImportResult, String> {
        if !matches!(
            module_name,
            "display" | "filesystem" | "events" | "ipc" | "runtime" | "process"
        ) {
            return Ok(NativeHostImportResult::Fallback(arguments));
        }
        let Some(kernel_handle) = self.attached_kernel.as_ref() else {
            return Ok(NativeHostImportResult::Fallback(arguments));
        };
        if module_name == "filesystem" {
            let kernel = kernel_handle.lock()?;
            let Some(filesystem) = kernel.filesystem.as_ref() else {
                return Ok(NativeHostImportResult::Fallback(arguments));
            };
            return match function_name {
                "exists" => {
                    let path = string_argument(&arguments, 0, "filesystem.exists path")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(
                        filesystem.exists(&self.working_directory, path)?,
                    )))
                }
                "isDirectory" => {
                    let path = string_argument(&arguments, 0, "filesystem.isDirectory path")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(
                        filesystem.is_directory(&self.working_directory, path)?,
                    )))
                }
                "readText" => {
                    let path = string_argument(&arguments, 0, "filesystem.readText path")?;
                    Ok(NativeHostImportResult::Handled(VmValue::String(
                        filesystem.read_text(&self.working_directory, path)?,
                    )))
                }
                "writeText" => {
                    let path = string_argument(&arguments, 0, "filesystem.writeText path")?;
                    let text = string_argument(&arguments, 1, "filesystem.writeText text")?;
                    filesystem.write_text(&self.working_directory, path, text)?;
                    Ok(NativeHostImportResult::Handled(VmValue::Unit))
                }
                "makeDir" => {
                    let path = string_argument(&arguments, 0, "filesystem.makeDir path")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(
                        filesystem.make_dir(&self.working_directory, path)?,
                    )))
                }
                "remove" => {
                    let path = string_argument(&arguments, 0, "filesystem.remove path")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(
                        filesystem.remove(&self.working_directory, path)?,
                    )))
                }
                "list" => {
                    let path = arguments
                        .first()
                        .map(|_| string_argument(&arguments, 0, "filesystem.list path"))
                        .transpose()?
                        .unwrap_or("");
                    Ok(NativeHostImportResult::Handled(VmValue::String(
                        filesystem.list(&self.working_directory, path)?,
                    )))
                }
                _ => Ok(NativeHostImportResult::Fallback(arguments)),
            };
        }
        if module_name == "events" {
            let mut kernel = kernel_handle.lock()?;
            return match function_name {
                "tryPull" => {
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
                "argCount" => {
                    let event_id = event_id_argument(&arguments, 0, "events.argCount event")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Int(
                        kernel.event_arg_count(event_id),
                    )))
                }
                "argInt" => {
                    let event_id = event_id_argument(&arguments, 0, "events.argInt event")?;
                    let index = int_argument(&arguments, 1, "events.argInt index")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Int(
                        kernel.event_arg_int(event_id, index),
                    )))
                }
                "argBool" => {
                    let event_id = event_id_argument(&arguments, 0, "events.argBool event")?;
                    let index = int_argument(&arguments, 1, "events.argBool index")?;
                    Ok(NativeHostImportResult::Handled(VmValue::Bool(
                        kernel.event_arg_bool(event_id, index),
                    )))
                }
                "argString" => {
                    let event_id = event_id_argument(&arguments, 0, "events.argString event")?;
                    let index = int_argument(&arguments, 1, "events.argString index")?;
                    Ok(NativeHostImportResult::Handled(VmValue::String(
                        kernel.event_arg_string(event_id, index),
                    )))
                }
                _ => Ok(NativeHostImportResult::Fallback(arguments)),
            };
        }
        if module_name == "ipc" {
            return match function_name {
                "open" => Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel_handle.with_kernel_mut(|kernel| kernel.open_ipc_channel())??,
                ))),
                "write" => {
                    let channel = int_argument(&arguments, 0, "ipc.write channel")?;
                    let text = string_argument(&arguments, 1, "ipc.write text")?;
                    kernel_handle.with_kernel_mut(|kernel| kernel.write_ipc(channel, text))??;
                    Ok(NativeHostImportResult::Handled(VmValue::Unit))
                }
                "read" => {
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
                "tryRead" => {
                    let channel = int_argument(&arguments, 0, "ipc.tryRead channel")?;
                    let mut kernel = kernel_handle.lock()?;
                    Ok(NativeHostImportResult::Handled(VmValue::String(
                        kernel.try_read_ipc(channel)?,
                    )))
                }
                "close" => {
                    let channel = int_argument(&arguments, 0, "ipc.close channel")?;
                    kernel_handle.with_kernel_mut(|kernel| kernel.close_ipc(channel))??;
                    Ok(NativeHostImportResult::Handled(VmValue::Unit))
                }
                _ => Ok(NativeHostImportResult::Fallback(arguments)),
            };
        }
        if module_name == "runtime" {
            let mut kernel = kernel_handle.lock()?;
            return match function_name {
                "poll" => {
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
                _ => Ok(NativeHostImportResult::Fallback(arguments)),
            };
        }
        if module_name == "process" {
            return match function_name {
                "argument" => self
                    .process_argument
                    .as_ref()
                    .map(|argument| {
                        NativeHostImportResult::Handled(VmValue::String(argument.clone()))
                    })
                    .map(Ok)
                    .unwrap_or_else(|| Ok(NativeHostImportResult::Fallback(arguments))),
                "currentDirectory" => {
                    let kernel = kernel_handle.lock()?;
                    if kernel.filesystem.is_none() {
                        return Ok(NativeHostImportResult::Fallback(arguments));
                    }
                    Ok(NativeHostImportResult::Handled(VmValue::String(
                        self.working_directory.clone(),
                    )))
                }
                "changeDirectory" => {
                    let path = string_argument(&arguments, 0, "process.changeDirectory path")?;
                    let candidate = resolve_working_directory(&self.working_directory, path);
                    let kernel = kernel_handle.lock()?;
                    let Some(filesystem) = kernel.filesystem.as_ref() else {
                        return Ok(NativeHostImportResult::Fallback(arguments));
                    };
                    if filesystem.is_directory("", &candidate)? {
                        self.working_directory = candidate;
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(true)))
                    } else {
                        Ok(NativeHostImportResult::Handled(VmValue::Bool(false)))
                    }
                }
                "wait" => {
                    let kernel = kernel_handle.lock()?;
                    let pid = int_argument(&arguments, 0, "process.wait pid")?;
                    match kernel.process_status(pid) {
                        ProcessStatus::Completed(exit_code) => {
                            Ok(NativeHostImportResult::Handled(VmValue::Int(exit_code)))
                        }
                        ProcessStatus::Missing => {
                            Ok(NativeHostImportResult::Handled(VmValue::Int(1)))
                        }
                        ProcessStatus::Running => {
                            let wake_sequence = kernel.wake_sequence();
                            Ok(NativeHostImportResult::SignalNoResume {
                                signal: VmSignal::WaitProcess { pid, wake_sequence },
                                arguments,
                            })
                        }
                    }
                }
                _ => Ok(NativeHostImportResult::Fallback(arguments)),
            };
        }
        let mut kernel = kernel_handle.lock()?;
        if kernel.displays.first_display_id().is_none() {
            return Ok(NativeHostImportResult::Fallback(arguments));
        }
        match function_name {
            "primary" => Ok(NativeHostImportResult::Handled(VmValue::Int(
                kernel.displays.first_display_id().unwrap_or(0),
            ))),
            "isAttached" => {
                let display_id = int_argument(&arguments, 0, "display.isAttached displayId")?;
                Ok(NativeHostImportResult::Handled(VmValue::Bool(
                    kernel.displays.is_attached(display_id),
                )))
            }
            "width" => {
                let display_id = int_argument(&arguments, 0, "display.width displayId")?;
                Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel.displays.width(display_id).unwrap_or(0),
                )))
            }
            "height" => {
                let display_id = int_argument(&arguments, 0, "display.height displayId")?;
                Ok(NativeHostImportResult::Handled(VmValue::Int(
                    kernel.displays.height(display_id).unwrap_or(0),
                )))
            }
            "clear" => {
                let display_id = int_argument(&arguments, 0, "display.clear displayId")?;
                let rgb565 = int_argument(&arguments, 1, "display.clear rgb565")? as u16;
                kernel.displays.clear(display_id, rgb565);
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            "setPixel" => {
                let display_id = int_argument(&arguments, 0, "display.setPixel displayId")?;
                let x = int_argument(&arguments, 1, "display.setPixel x")?;
                let y = int_argument(&arguments, 2, "display.setPixel y")?;
                let rgb565 = int_argument(&arguments, 3, "display.setPixel rgb565")? as u16;
                kernel.displays.set_pixel(display_id, x, y, rgb565);
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            "fillRect" => {
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
            "copyRect" => {
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
            "blitMono" => {
                let display_id = int_argument(&arguments, 0, "display.blitMono displayId")?;
                let x = int_argument(&arguments, 1, "display.blitMono x")?;
                let y = int_argument(&arguments, 2, "display.blitMono y")?;
                let width = int_argument(&arguments, 3, "display.blitMono width")?;
                let height = int_argument(&arguments, 4, "display.blitMono height")?;
                let mask = string_argument(&arguments, 5, "display.blitMono mask")?;
                let foreground = int_argument(&arguments, 6, "display.blitMono foreground")? as u16;
                let background = match int_argument(&arguments, 7, "display.blitMono background")? {
                    value if value < 0 => None,
                    value => Some(value as u16),
                };
                kernel.displays.blit_mono(
                    display_id, x, y, width, height, mask, foreground, background,
                );
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            "blitMono5x7" => {
                let display_id = int_argument(&arguments, 0, "display.blitMono5x7 displayId")?;
                let x = int_argument(&arguments, 1, "display.blitMono5x7 x")?;
                let y = int_argument(&arguments, 2, "display.blitMono5x7 y")?;
                let mut glyph = 0_u64;
                for index in 0..7 {
                    let row =
                        int_argument(&arguments, 3 + index, "display.blitMono5x7 row")? as u64;
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
            "blitMono5x7Packed" => {
                let display_id =
                    int_argument(&arguments, 0, "display.blitMono5x7Packed displayId")?;
                let x = int_argument(&arguments, 1, "display.blitMono5x7Packed x")?;
                let y = int_argument(&arguments, 2, "display.blitMono5x7Packed y")?;
                let glyph = long_argument(&arguments, 3, "display.blitMono5x7Packed glyph")? as u64;
                let foreground =
                    int_argument(&arguments, 4, "display.blitMono5x7Packed foreground")? as u16;
                let background =
                    match int_argument(&arguments, 5, "display.blitMono5x7Packed background")? {
                        value if value < 0 => None,
                        value => Some(value as u16),
                    };
                kernel
                    .displays
                    .blit_mono5x7_packed(display_id, x, y, glyph, foreground, background);
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            "present" => {
                let display_id = int_argument(&arguments, 0, "display.present displayId")?;
                drop(kernel);
                kernel_handle.present_display(display_id)?;
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            "blitMono5x7Text" => {
                let display_id = int_argument(&arguments, 0, "display.blitMono5x7Text displayId")?;
                let x = int_argument(&arguments, 1, "display.blitMono5x7Text x")?;
                let y = int_argument(&arguments, 2, "display.blitMono5x7Text y")?;
                let text = string_argument(&arguments, 3, "display.blitMono5x7Text text")?;
                let foreground =
                    int_argument(&arguments, 4, "display.blitMono5x7Text foreground")? as u16;
                let background =
                    match int_argument(&arguments, 5, "display.blitMono5x7Text background")? {
                        value if value < 0 => None,
                        value => Some(value as u16),
                    };
                kernel
                    .displays
                    .blit_mono5x7_text(display_id, x, y, text, foreground, background);
                Ok(NativeHostImportResult::Handled(VmValue::Unit))
            }
            _ => Ok(NativeHostImportResult::Fallback(arguments)),
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

        loop {
            let instruction_start = self.instruction_pointer;
            let opcode = match self.read_u8()? {
                Some(opcode) => opcode,
                None => return self.halt(VmValue::Unit),
            };
            self.instructions_since_pause += 1;

            match opcode {
                OP_PUSH_UNIT => self.stack.push(VmValue::Unit),
                OP_RETURN => {
                    let result = self.stack.pop().unwrap_or(VmValue::Unit);
                    if let Some(frame) = self.call_stack.pop() {
                        self.function_index = frame.function_index;
                        self.instruction_pointer = frame.instruction_pointer;
                        self.locals = frame.locals;
                        self.stack.push(result);
                    } else {
                        return self.halt(result);
                    }
                }
                OP_PUSH_CONSTANT => {
                    let constant_index = self.read_i32()?;
                    let value = self.constant_value(constant_index)?;
                    self.stack.push(value);
                }
                OP_CALL_HOST => {
                    let import_id = self.read_i32()?;
                    let argument_count = self.read_i32()?;
                    let arguments = self.pop_many(argument_count)?;
                    let import = self.host_import(import_id)?;
                    let module_name = import.module_name.clone();
                    let function_name = import.function_name.clone();
                    match self.try_native_host_import(
                        import_id,
                        &module_name,
                        &function_name,
                        arguments,
                    )? {
                        NativeHostImportResult::Handled(value) => {
                            self.stack.push(value);
                        }
                        NativeHostImportResult::Fallback(arguments) => {
                            self.state = ImageVmState::WaitingForResume;
                            return Ok(VmSignal::HostCall {
                                module_name,
                                function_name,
                                arguments,
                            });
                        }
                        NativeHostImportResult::SignalNoResume { signal, arguments } => {
                            self.instruction_pointer = instruction_start;
                            self.stack.extend(arguments);
                            return Ok(signal);
                        }
                    }
                }
                OP_POP => {
                    let _ = self.stack.pop();
                }
                OP_PUSH_BOOL => {
                    let value = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    match value {
                        0 => self.stack.push(VmValue::Bool(false)),
                        1 => self.stack.push(VmValue::Bool(true)),
                        other => return Err(format!("invalid CkVmImage bool byte {other}")),
                    }
                }
                OP_PUSH_NULL => self.stack.push(VmValue::Null),
                OP_LOAD_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.local(slot)?.clone();
                    self.stack.push(value);
                }
                OP_STORE_LOCAL => {
                    let slot = self.read_i32()?;
                    let value = self.pop_one("store local")?;
                    *self.local_mut(slot)? = value;
                }
                OP_JUMP => {
                    let target = self.read_i32()?;
                    self.jump(target)?;
                }
                OP_JUMP_IF_FALSE => {
                    let target = self.read_i32()?;
                    if !self.pop_bool_condition("JUMP_IF_FALSE")? {
                        self.jump(target)?;
                    }
                }
                OP_JUMP_IF_TRUE => {
                    let target = self.read_i32()?;
                    if self.pop_bool_condition("JUMP_IF_TRUE")? {
                        self.jump(target)?;
                    }
                }
                OP_BINARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let right = self.pop_one("binary right operand")?;
                    let left = self.pop_one("binary left operand")?;
                    self.stack
                        .push(apply_binary_operator(operator, left, right)?);
                }
                OP_UNARY => {
                    let operator = self.read_u8()?.ok_or_else(|| {
                        "unexpected end of CkVmImage instruction stream".to_string()
                    })?;
                    let operand = self.pop_one("unary operand")?;
                    self.stack.push(apply_unary_operator(operator, operand)?);
                }
                OP_CALL_FUNCTION => {
                    let function_index = self.read_i32()?;
                    let argument_count = self.read_i32()?;
                    self.call_function(function_index, argument_count)?;
                }
                OP_CONSTRUCT_RECORD => self.construct_record()?,
                OP_GET_FIELD => self.get_field()?,
                OP_CONSTRUCT_ARRAY => self.construct_array()?,
                OP_CONSTRUCT_LIST => self.construct_list()?,
                OP_CONSTRUCT_MAP => self.construct_map()?,
                OP_INDEX_GET => self.index_get()?,
                OP_INDEX_SET => self.index_set()?,
                OP_CALL_COLLECTION_METHOD => {
                    let method_name_index = self.read_i32()?;
                    let method_name =
                        self.constant_string_metadata(method_name_index, "collection method name")?;
                    let argument_count = self.read_i32()?;
                    self.call_collection_method(method_name, argument_count)?;
                }
                OP_YIELD => {
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Yield);
                }
                OP_SLEEP => {
                    let ticks = self.sleep_ticks()?;
                    self.state = ImageVmState::WaitingForResume;
                    return Ok(VmSignal::Sleep(ticks));
                }
                other => return Err(format!("unknown CkVmImage opcode {other}")),
            }

            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(VmSignal::Pause);
            }
        }
    }

    fn halt(&mut self, value: VmValue) -> Result<VmSignal, String> {
        self.state = ImageVmState::Halted;
        Ok(VmSignal::Halt(value))
    }

    fn read_u8(&mut self) -> Result<Option<u8>, String> {
        let code = &self.current_function()?.code;
        if self.instruction_pointer >= code.len() {
            return Ok(None);
        }
        let value = code[self.instruction_pointer];
        self.instruction_pointer += 1;
        Ok(Some(value))
    }

    fn read_i32(&mut self) -> Result<i32, String> {
        let code = &self.current_function()?.code;
        let end = self
            .instruction_pointer
            .checked_add(4)
            .ok_or_else(|| "CkVmImage instruction offset overflow".to_string())?;
        let bytes = code
            .get(self.instruction_pointer..end)
            .ok_or_else(|| "unexpected end of CkVmImage instruction stream".to_string())?;
        let mut buffer = [0u8; 4];
        buffer.copy_from_slice(bytes);
        self.instruction_pointer = end;
        Ok(i32::from_le_bytes(buffer))
    }

    fn constant_value(&self, constant_index: i32) -> Result<VmValue, String> {
        if constant_index < 0 {
            return Err(format!(
                "negative CkVmImage constant index {constant_index}"
            ));
        }
        match self.image.constants.get(constant_index as usize) {
            Some(Constant::String(value)) => Ok(VmValue::String(value.clone())),
            Some(Constant::Int(value)) => Ok(VmValue::Int(*value)),
            Some(Constant::Long(value)) => Ok(VmValue::Long(*value)),
            None => Err(format!(
                "CkVmImage constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn constant_string_metadata(
        &self,
        constant_index: i32,
        metadata_name: &str,
    ) -> Result<String, String> {
        if constant_index < 0 {
            return Err(format!(
                "negative CkVmImage {metadata_name} constant index {constant_index}"
            ));
        }
        match self.image.constants.get(constant_index as usize) {
            Some(Constant::String(value)) => Ok(value.clone()),
            Some(other) => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} must be String metadata but found {other:?}"
            )),
            None => Err(format!(
                "CkVmImage {metadata_name} constant index {constant_index} is out of bounds"
            )),
        }
    }

    fn construct_record(&mut self) -> Result<(), String> {
        let type_name_index = self.read_i32()?;
        let type_name = self.constant_string_metadata(type_name_index, "record type name")?;
        let field_count = self.read_i32()?;
        if field_count < 0 {
            return Err(format!(
                "negative CkVmImage record field count {field_count}"
            ));
        }
        let field_count = field_count as usize;
        let mut field_names = Vec::with_capacity(field_count);
        for _ in 0..field_count {
            let field_name_index = self.read_i32()?;
            field_names.push(self.constant_string_metadata(field_name_index, "record field name")?);
        }
        let values = self.pop_many(field_count as i32)?;
        let fields = field_names.into_iter().zip(values).collect();
        self.stack.push(VmValue::Record { type_name, fields });
        Ok(())
    }

    fn get_field(&mut self) -> Result<(), String> {
        let field_name_index = self.read_i32()?;
        let field_name = self.constant_string_metadata(field_name_index, "field name")?;
        let receiver = self.pop_one("get field receiver")?;
        match receiver {
            VmValue::Record { type_name, fields } => {
                if let Some((_, value)) = fields.into_iter().find(|(name, _)| name == &field_name) {
                    self.stack.push(value);
                    Ok(())
                } else {
                    Err(format!(
                        "CkVmImage record `{type_name}` has no field `{field_name}`"
                    ))
                }
            }
            other => Err(format!(
                "CkVmImage GET_FIELD requires Record receiver but found {other:?}"
            )),
        }
    }

    fn construct_array(&mut self) -> Result<(), String> {
        let default = self.pop_one("construct array default")?;
        let size = Self::require_int(self.pop_one("construct array size")?, "array size")?;
        if size < 0 {
            return Err(format!("negative CkVmImage array size {size}"));
        }
        let object_ref = self.allocate_object(HeapObject::Array(vec![default; size as usize]))?;
        self.stack.push(object_ref);
        Ok(())
    }

    fn construct_list(&mut self) -> Result<(), String> {
        let element_count = self.read_i32()?;
        if element_count < 0 {
            return Err(format!(
                "negative CkVmImage list element count {element_count}"
            ));
        }
        let values = self.pop_many(element_count)?;
        let object_ref = self.allocate_object(HeapObject::List(values))?;
        self.stack.push(object_ref);
        Ok(())
    }

    fn construct_map(&mut self) -> Result<(), String> {
        let entry_count = self.read_i32()?;
        if entry_count < 0 {
            return Err(format!("negative CkVmImage map entry count {entry_count}"));
        }
        let value_count = entry_count.checked_mul(2).ok_or_else(|| {
            format!("CkVmImage map entry count {entry_count} overflows value count")
        })?;
        let values = self.pop_many(value_count)?;
        let mut entries = Vec::with_capacity(entry_count as usize);
        let mut values = values.into_iter();
        while let Some(key) = values.next() {
            let value = values
                .next()
                .ok_or_else(|| "CkVmImage map construction missing value".to_string())?;
            Self::map_set(&mut entries, Self::require_non_null_key(key)?, value);
        }
        let object_ref = self.allocate_object(HeapObject::Map(entries))?;
        self.stack.push(object_ref);
        Ok(())
    }

    fn index_get(&mut self) -> Result<(), String> {
        let index_or_key = self.pop_one("index get key")?;
        let receiver = self.pop_one("index get receiver")?;
        let result = match self.collection_object(receiver, "INDEX_GET")? {
            HeapObject::Array(values) => {
                let index = Self::checked_index(
                    Self::require_int(index_or_key, "array index get")?,
                    values.len(),
                    "array index get",
                )?;
                values[index].clone()
            }
            HeapObject::List(values) => {
                let index = Self::checked_index(
                    Self::require_int(index_or_key, "list index get")?,
                    values.len(),
                    "list index get",
                )?;
                values[index].clone()
            }
            HeapObject::Map(entries) => {
                let key = Self::require_non_null_key(index_or_key)?;
                Self::map_find_index(entries, &key)
                    .map(|index| entries[index].1.clone())
                    .unwrap_or(VmValue::Null)
            }
        };
        self.stack.push(result);
        Ok(())
    }

    fn index_set(&mut self) -> Result<(), String> {
        let value = self.pop_one("index set value")?;
        let index_or_key = self.pop_one("index set key")?;
        let receiver = self.pop_one("index set receiver")?;
        match self.collection_object_mut(receiver, "INDEX_SET")? {
            HeapObject::Array(values) => {
                let index = Self::checked_index(
                    Self::require_int(index_or_key, "array index set")?,
                    values.len(),
                    "array index set",
                )?;
                values[index] = value;
            }
            HeapObject::List(values) => {
                let index = Self::checked_index(
                    Self::require_int(index_or_key, "list index set")?,
                    values.len(),
                    "list index set",
                )?;
                values[index] = value;
            }
            HeapObject::Map(entries) => {
                Self::map_set(entries, Self::require_non_null_key(index_or_key)?, value);
            }
        }
        self.stack.push(VmValue::Unit);
        Ok(())
    }

    fn call_collection_method(
        &mut self,
        method_name: String,
        argument_count: i32,
    ) -> Result<(), String> {
        let arguments = self.pop_many(argument_count)?;
        let receiver = self.pop_one("collection method receiver")?;
        let id = Self::require_object_ref(receiver, "CALL_COLLECTION_METHOD")?;
        let result = match self.objects.get(&id) {
            Some(HeapObject::Array(_)) => self.call_array_method(id, &method_name, arguments)?,
            Some(HeapObject::List(_)) => self.call_list_method(id, &method_name, arguments)?,
            Some(HeapObject::Map(_)) => self.call_map_method(id, &method_name, arguments)?,
            None => {
                return Err(format!(
                    "CkVmImage CALL_COLLECTION_METHOD object id {id} does not exist"
                ))
            }
        };
        self.stack.push(result);
        Ok(())
    }

    fn call_array_method(
        &mut self,
        id: u32,
        method_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<VmValue, String> {
        match method_name {
            "size" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self.array_values(id, method_name)?;
                Ok(VmValue::Int(values.len() as i32))
            }
            "get" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let index = Self::require_int(arguments[0].clone(), "array get index")?;
                let values = self.array_values(id, method_name)?;
                let index = Self::checked_index(index, values.len(), "array get")?;
                Ok(values[index].clone())
            }
            "set" => {
                Self::expect_argument_count(method_name, arguments.len(), 2)?;
                let index = Self::require_int(arguments[0].clone(), "array set index")?;
                let values = self.array_values_mut(id, method_name)?;
                let index = Self::checked_index(index, values.len(), "array set")?;
                values[index] = arguments[1].clone();
                Ok(VmValue::Unit)
            }
            "getOrNull" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let index = Self::require_int(arguments[0].clone(), "array getOrNull index")?;
                let values = self.array_values(id, method_name)?;
                if index < 0 || index as usize >= values.len() {
                    Ok(VmValue::Null)
                } else {
                    Ok(values[index as usize].clone())
                }
            }
            other => Err(format!(
                "CkVmImage Array has no collection method `{other}`"
            )),
        }
    }

    fn call_list_method(
        &mut self,
        id: u32,
        method_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<VmValue, String> {
        match method_name {
            "size" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self.list_values(id, method_name)?;
                Ok(VmValue::Int(values.len() as i32))
            }
            "isEmpty" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self.list_values(id, method_name)?;
                Ok(VmValue::Bool(values.is_empty()))
            }
            "get" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let index = Self::require_int(arguments[0].clone(), "list get index")?;
                let values = self.list_values(id, method_name)?;
                let index = Self::checked_index(index, values.len(), "list get")?;
                Ok(values[index].clone())
            }
            "set" => {
                Self::expect_argument_count(method_name, arguments.len(), 2)?;
                let index = Self::require_int(arguments[0].clone(), "list set index")?;
                let values = self.list_values_mut(id, method_name)?;
                let index = Self::checked_index(index, values.len(), "list set")?;
                values[index] = arguments[1].clone();
                Ok(VmValue::Unit)
            }
            "getOrNull" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let index = Self::require_int(arguments[0].clone(), "list getOrNull index")?;
                let values = self.list_values(id, method_name)?;
                if index < 0 || index as usize >= values.len() {
                    Ok(VmValue::Null)
                } else {
                    Ok(values[index as usize].clone())
                }
            }
            "add" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let values = self.list_values_mut(id, method_name)?;
                values.push(arguments[0].clone());
                Ok(VmValue::Unit)
            }
            "insert" => {
                Self::expect_argument_count(method_name, arguments.len(), 2)?;
                let index = Self::require_int(arguments[0].clone(), "list insert index")?;
                let values = self.list_values_mut(id, method_name)?;
                if index < 0 || index as usize > values.len() {
                    return Err(format!(
                        "CkVmImage list insert index {index} is out of bounds for length {}",
                        values.len()
                    ));
                }
                values.insert(index as usize, arguments[1].clone());
                Ok(VmValue::Unit)
            }
            "removeAt" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let index = Self::require_int(arguments[0].clone(), "list removeAt index")?;
                let values = self.list_values_mut(id, method_name)?;
                let index = Self::checked_index(index, values.len(), "list removeAt")?;
                Ok(values.remove(index))
            }
            "clear" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self.list_values_mut(id, method_name)?;
                values.clear();
                Ok(VmValue::Unit)
            }
            other => Err(format!("CkVmImage List has no collection method `{other}`")),
        }
    }

    fn call_map_method(
        &mut self,
        id: u32,
        method_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<VmValue, String> {
        match method_name {
            "size" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let entries = self.map_entries(id, method_name)?;
                Ok(VmValue::Int(entries.len() as i32))
            }
            "isEmpty" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let entries = self.map_entries(id, method_name)?;
                Ok(VmValue::Bool(entries.is_empty()))
            }
            "containsKey" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let key = Self::require_non_null_key(arguments[0].clone())?;
                let entries = self.map_entries(id, method_name)?;
                Ok(VmValue::Bool(Self::map_find_index(entries, &key).is_some()))
            }
            "get" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let key = Self::require_non_null_key(arguments[0].clone())?;
                let entries = self.map_entries(id, method_name)?;
                Ok(Self::map_find_index(entries, &key)
                    .map(|index| entries[index].1.clone())
                    .unwrap_or(VmValue::Null))
            }
            "getOrDefault" => {
                Self::expect_argument_count(method_name, arguments.len(), 2)?;
                let key = Self::require_non_null_key(arguments[0].clone())?;
                let entries = self.map_entries(id, method_name)?;
                Ok(Self::map_find_index(entries, &key)
                    .map(|index| entries[index].1.clone())
                    .unwrap_or_else(|| arguments[1].clone()))
            }
            "set" => {
                Self::expect_argument_count(method_name, arguments.len(), 2)?;
                let key = Self::require_non_null_key(arguments[0].clone())?;
                let entries = self.map_entries_mut(id, method_name)?;
                Self::map_set(entries, key, arguments[1].clone());
                Ok(VmValue::Unit)
            }
            "remove" => {
                Self::expect_argument_count(method_name, arguments.len(), 1)?;
                let key = Self::require_non_null_key(arguments[0].clone())?;
                let entries = self.map_entries_mut(id, method_name)?;
                if let Some(index) = Self::map_find_index(entries, &key) {
                    Ok(entries.remove(index).1)
                } else {
                    Ok(VmValue::Null)
                }
            }
            "clear" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let entries = self.map_entries_mut(id, method_name)?;
                entries.clear();
                Ok(VmValue::Unit)
            }
            "keys" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self
                    .map_entries(id, method_name)?
                    .iter()
                    .map(|(key, _)| key.clone())
                    .collect();
                self.allocate_object(HeapObject::List(values))
            }
            "values" => {
                Self::expect_argument_count(method_name, arguments.len(), 0)?;
                let values = self
                    .map_entries(id, method_name)?
                    .iter()
                    .map(|(_, value)| value.clone())
                    .collect();
                self.allocate_object(HeapObject::List(values))
            }
            other => Err(format!("CkVmImage Map has no collection method `{other}`")),
        }
    }

    fn allocate_object(&mut self, object: HeapObject) -> Result<VmValue, String> {
        let id = self.next_object_id;
        self.next_object_id = self
            .next_object_id
            .checked_add(1)
            .ok_or_else(|| "CkVmImage object id overflow".to_string())?;
        self.objects.insert(id, object);
        Ok(VmValue::ObjectRef(id))
    }

    fn require_object_ref(receiver: VmValue, operation: &str) -> Result<u32, String> {
        match receiver {
            VmValue::ObjectRef(id) => Ok(id),
            other => Err(format!(
                "CkVmImage {operation} requires collection ObjectRef receiver but found {other:?}"
            )),
        }
    }

    fn collection_object(&self, receiver: VmValue, operation: &str) -> Result<&HeapObject, String> {
        let id = Self::require_object_ref(receiver, operation)?;
        self.objects
            .get(&id)
            .ok_or_else(|| format!("CkVmImage {operation} object id {id} does not exist"))
    }

    fn collection_object_mut(
        &mut self,
        receiver: VmValue,
        operation: &str,
    ) -> Result<&mut HeapObject, String> {
        let id = Self::require_object_ref(receiver, operation)?;
        self.objects
            .get_mut(&id)
            .ok_or_else(|| format!("CkVmImage {operation} object id {id} does not exist"))
    }

    fn array_values(&self, id: u32, operation: &str) -> Result<&Vec<VmValue>, String> {
        match self.objects.get(&id) {
            Some(HeapObject::Array(values)) => Ok(values),
            Some(other) => Err(format!(
                "CkVmImage Array method `{operation}` found non-array object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage Array method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn array_values_mut(&mut self, id: u32, operation: &str) -> Result<&mut Vec<VmValue>, String> {
        match self.objects.get_mut(&id) {
            Some(HeapObject::Array(values)) => Ok(values),
            Some(other) => Err(format!(
                "CkVmImage Array method `{operation}` found non-array object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage Array method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn list_values(&self, id: u32, operation: &str) -> Result<&Vec<VmValue>, String> {
        match self.objects.get(&id) {
            Some(HeapObject::List(values)) => Ok(values),
            Some(other) => Err(format!(
                "CkVmImage List method `{operation}` found non-list object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage List method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn list_values_mut(&mut self, id: u32, operation: &str) -> Result<&mut Vec<VmValue>, String> {
        match self.objects.get_mut(&id) {
            Some(HeapObject::List(values)) => Ok(values),
            Some(other) => Err(format!(
                "CkVmImage List method `{operation}` found non-list object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage List method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn map_entries(&self, id: u32, operation: &str) -> Result<&Vec<(VmValue, VmValue)>, String> {
        match self.objects.get(&id) {
            Some(HeapObject::Map(entries)) => Ok(entries),
            Some(other) => Err(format!(
                "CkVmImage Map method `{operation}` found non-map object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage Map method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn map_entries_mut(
        &mut self,
        id: u32,
        operation: &str,
    ) -> Result<&mut Vec<(VmValue, VmValue)>, String> {
        match self.objects.get_mut(&id) {
            Some(HeapObject::Map(entries)) => Ok(entries),
            Some(other) => Err(format!(
                "CkVmImage Map method `{operation}` found non-map object {other:?}"
            )),
            None => Err(format!(
                "CkVmImage Map method `{operation}` object id {id} does not exist"
            )),
        }
    }

    fn expect_argument_count(
        method_name: &str,
        actual: usize,
        expected: usize,
    ) -> Result<(), String> {
        if actual == expected {
            Ok(())
        } else {
            Err(format!(
                "CkVmImage collection method `{method_name}` expected {expected} arguments but got {actual}"
            ))
        }
    }

    fn require_int(value: VmValue, operation: &str) -> Result<i32, String> {
        match value {
            VmValue::Int(value) => Ok(value),
            other => Err(format!(
                "CkVmImage {operation} requires Int but found {other:?}"
            )),
        }
    }

    fn checked_index(index: i32, length: usize, operation: &str) -> Result<usize, String> {
        if index < 0 || index as usize >= length {
            Err(format!(
                "CkVmImage {operation} index {index} is out of bounds for length {length}"
            ))
        } else {
            Ok(index as usize)
        }
    }

    fn require_non_null_key(key: VmValue) -> Result<VmValue, String> {
        if key == VmValue::Null {
            Err("CkVmImage Map keys cannot be null".to_string())
        } else {
            Ok(key)
        }
    }

    fn map_find_index(entries: &[(VmValue, VmValue)], key: &VmValue) -> Option<usize> {
        entries
            .iter()
            .position(|(entry_key, _)| value_equals(entry_key, key))
    }

    fn map_set(entries: &mut Vec<(VmValue, VmValue)>, key: VmValue, value: VmValue) {
        if let Some(index) = Self::map_find_index(entries, &key) {
            entries[index].1 = value;
        } else {
            entries.push((key, value));
        }
    }

    fn host_import(&self, import_id: i32) -> Result<&HostImport, String> {
        self.image
            .host_imports
            .iter()
            .find(|import| import.id == import_id)
            .ok_or_else(|| format!("CkVmImage host import id {import_id} is not declared"))
    }

    fn pop_many(&mut self, argument_count: i32) -> Result<Vec<VmValue>, String> {
        if argument_count < 0 {
            return Err(format!(
                "negative CkVmImage argument count {argument_count}"
            ));
        }
        let argument_count = argument_count as usize;
        if self.stack.len() < argument_count {
            return Err(format!(
                "CkVmImage stack underflow: need {argument_count} arguments but stack has {}",
                self.stack.len()
            ));
        }
        let start = self.stack.len() - argument_count;
        Ok(self.stack.split_off(start))
    }

    fn pop_one(&mut self, operation: &str) -> Result<VmValue, String> {
        self.stack
            .pop()
            .ok_or_else(|| format!("CkVmImage stack underflow during {operation}"))
    }

    fn sleep_ticks(&mut self) -> Result<i64, String> {
        match self.pop_one("sleep ticks")? {
            VmValue::Int(ticks) => Ok(i64::from(ticks)),
            VmValue::Long(ticks) => Ok(ticks),
            other => Err(format!(
                "CkVmImage SLEEP requires Long ticks but found {other:?}"
            )),
        }
    }

    fn pop_bool_condition(&mut self, opcode_name: &str) -> Result<bool, String> {
        match self.pop_one(opcode_name)? {
            VmValue::Bool(value) => Ok(value),
            other => Err(format!(
                "CkVmImage {opcode_name} requires Bool condition but found {other:?}"
            )),
        }
    }

    fn local(&self, slot: i32) -> Result<&VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        self.locals.get(slot as usize).ok_or_else(|| {
            format!(
                "CkVmImage local slot {slot} is out of bounds for {} locals",
                self.locals.len()
            )
        })
    }

    fn local_mut(&mut self, slot: i32) -> Result<&mut VmValue, String> {
        if slot < 0 {
            return Err(format!("CkVmImage local slot {slot} is negative"));
        }
        let local_count = self.locals.len();
        self.locals.get_mut(slot as usize).ok_or_else(|| {
            format!("CkVmImage local slot {slot} is out of bounds for {local_count} locals")
        })
    }

    fn jump(&mut self, target: i32) -> Result<(), String> {
        if target < 0 {
            return Err(format!("CkVmImage jump target {target} is negative"));
        }
        let target = target as usize;
        let code_len = self.current_function()?.code.len();
        if target > code_len {
            return Err(format!(
                "CkVmImage jump target {target} is outside function code length {code_len}"
            ));
        }
        self.instruction_pointer = target;
        Ok(())
    }

    fn call_function(&mut self, function_index: i32, argument_count: i32) -> Result<(), String> {
        let function_index = self.checked_function_index(function_index)?;
        if argument_count < 0 {
            return Err(format!(
                "negative CkVmImage argument count {argument_count}"
            ));
        }
        let argument_count = argument_count as usize;
        let frame_size = checked_frame_size(&self.image, function_index)?;
        if argument_count > frame_size {
            return Err(format!(
                "CkVmImage argument count {argument_count} exceeds frame size {frame_size} for function {function_index}"
            ));
        }
        let arguments = self.pop_many(argument_count as i32)?;
        let caller_frame = CallFrame {
            function_index: self.function_index,
            instruction_pointer: self.instruction_pointer,
            locals: std::mem::take(&mut self.locals),
        };
        self.call_stack.push(caller_frame);
        self.function_index = function_index;
        self.instruction_pointer = 0;
        self.locals = vec![VmValue::Unit; frame_size];
        for (slot, argument) in arguments.into_iter().enumerate() {
            self.locals[slot] = argument;
        }
        Ok(())
    }

    fn checked_function_index(&self, function_index: i32) -> Result<usize, String> {
        if function_index < 0 {
            return Err(format!(
                "negative CkVmImage function index {function_index}"
            ));
        }
        let function_index = function_index as usize;
        if function_index >= self.image.functions.len() {
            return Err(format!(
                "CkVmImage function index {function_index} is out of bounds for {} functions",
                self.image.functions.len()
            ));
        }
        Ok(function_index)
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
    if image.entry_function_index < 0 {
        return Err(format!(
            "negative CkVmImage entry function index {}",
            image.entry_function_index
        ));
    }
    let index = image.entry_function_index as usize;
    if index >= image.functions.len() {
        return Err(format!(
            "CkVmImage entry function index {} is out of bounds for {} functions",
            image.entry_function_index,
            image.functions.len()
        ));
    }
    Ok(index)
}

fn checked_frame_size(image: &Image, function_index: usize) -> Result<usize, String> {
    let frame_size = image.functions[function_index].frame_size;
    if frame_size < 0 {
        return Err(format!("negative CkVmImage frame size {frame_size}"));
    }
    Ok(frame_size as usize)
}

fn apply_binary_operator(operator: u8, left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => binary_add(left, right),
        1 => numeric_binary(
            left,
            right,
            "-",
            |a, b| a.wrapping_sub(b),
            |a, b| a.wrapping_sub(b),
        ),
        2 => numeric_binary(
            left,
            right,
            "*",
            |a, b| a.wrapping_mul(b),
            |a, b| a.wrapping_mul(b),
        ),
        3 => binary_divide(left, right),
        4 => Ok(VmValue::Bool(value_equals(&left, &right))),
        5 => Ok(VmValue::Bool(!value_equals(&left, &right))),
        6 => compare_values(left, right, "<", |ordering| ordering.is_lt()),
        7 => compare_values(left, right, "<=", |ordering| !ordering.is_gt()),
        8 => compare_values(left, right, ">", |ordering| ordering.is_gt()),
        9 => compare_values(left, right, ">=", |ordering| !ordering.is_lt()),
        10 => bool_binary(left, right, "&&", |a, b| a && b),
        11 => bool_binary(left, right, "||", |a, b| a || b),
        12 => numeric_binary(left, right, "&", |a, b| a & b, |a, b| a & b),
        13 => numeric_binary(left, right, "|", |a, b| a | b, |a, b| a | b),
        14 => numeric_binary(left, right, "^", |a, b| a ^ b, |a, b| a ^ b),
        15 => shift_binary(
            left,
            right,
            "<<",
            |a, b| a.wrapping_shl(b),
            |a, b| a.wrapping_shl(b),
        ),
        16 => shift_binary(
            left,
            right,
            ">>",
            |a, b| a.wrapping_shr(b),
            |a, b| a.wrapping_shr(b),
        ),
        other => Err(format!("unknown CkVmImage binary operator tag {other}")),
    }
}

fn apply_unary_operator(operator: u8, operand: VmValue) -> Result<VmValue, String> {
    match operator {
        0 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(value.wrapping_neg())),
            VmValue::Long(value) => Ok(VmValue::Long(value.wrapping_neg())),
            other => Err(format!(
                "CkVmImage unary - requires Int or Long but found {other:?}"
            )),
        },
        1 => match operand {
            VmValue::Bool(value) => Ok(VmValue::Bool(!value)),
            other => Err(format!(
                "CkVmImage unary ! requires Bool but found {other:?}"
            )),
        },
        2 => match operand {
            VmValue::Int(value) => Ok(VmValue::Int(!value)),
            VmValue::Long(value) => Ok(VmValue::Long(!value)),
            other => Err(format!(
                "CkVmImage unary ~ requires Int or Long but found {other:?}"
            )),
        },
        other => Err(format!("unknown CkVmImage unary operator tag {other}")),
    }
}

fn try_builtin_native_host_import(
    import_id: i32,
    module_name: &str,
    arguments: Vec<VmValue>,
) -> Result<NativeHostImportResult, String> {
    if module_name != "strings" {
        return Ok(NativeHostImportResult::Fallback(arguments));
    }

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
        _ => Ok(NativeHostImportResult::Fallback(arguments)),
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
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    match &arguments[0] {
        VmValue::String(text) => Ok(NativeHostImportResult::Handled(operation(text))),
        _ => Ok(NativeHostImportResult::Fallback(arguments)),
    }
}

fn native_string_char_at(arguments: Vec<VmValue>) -> Result<NativeHostImportResult, String> {
    if arguments.len() != 2 {
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let index = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
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
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let index = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
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
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let count = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
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
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let start = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let end = match &arguments[2] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
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
        return Ok(NativeHostImportResult::Fallback(arguments));
    }
    let text = match &arguments[0] {
        VmValue::String(text) => text,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let start = match &arguments[1] {
        VmValue::Int(value) => *value,
        VmValue::Long(value) => *value as i32,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
    };
    let replacement = match &arguments[2] {
        VmValue::String(value) => value,
        _ => return Ok(NativeHostImportResult::Fallback(arguments)),
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

fn binary_add(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::String(left), right) => Ok(VmValue::String(left + &value_to_string(&right)?)),
        (left, VmValue::String(right)) => Ok(VmValue::String(value_to_string(&left)? + &right)),
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_add(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_add(right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long((left as i64).wrapping_add(right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(left.wrapping_add(right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary + requires numbers or strings but found {left:?} and {right:?}"
        )),
    }
}

fn binary_divide(left: VmValue, right: VmValue) -> Result<VmValue, String> {
    match (left, right) {
        (_, VmValue::Int(0)) | (_, VmValue::Long(0)) => {
            Err("CkVmImage division by zero".to_string())
        }
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(left.wrapping_div(right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(left.wrapping_div(right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long((left as i64).wrapping_div(right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(left.wrapping_div(right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary / requires Int or Long but found {left:?} and {right:?}"
        )),
    }
}

fn numeric_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, i32) -> i32,
    long_op: fn(i64, i64) -> i64,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => Ok(VmValue::Int(int_op(left, right))),
        (VmValue::Long(left), VmValue::Long(right)) => Ok(VmValue::Long(long_op(left, right))),
        (VmValue::Int(left), VmValue::Long(right)) => {
            Ok(VmValue::Long(long_op(left as i64, right)))
        }
        (VmValue::Long(left), VmValue::Int(right)) => {
            Ok(VmValue::Long(long_op(left, right as i64)))
        }
        (left, right) => Err(format!(
            "CkVmImage binary {symbol} requires Int or Long but found {left:?} and {right:?}"
        )),
    }
}

fn shift_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    int_op: fn(i32, u32) -> i32,
    long_op: fn(i64, u32) -> i64,
) -> Result<VmValue, String> {
    let shift = match right {
        VmValue::Int(value) => value as u32,
        VmValue::Long(value) => value as u32,
        other => {
            return Err(format!(
                "CkVmImage binary {symbol} shift count requires Int or Long but found {other:?}"
            ));
        }
    };
    match left {
        VmValue::Int(value) => Ok(VmValue::Int(int_op(value, shift))),
        VmValue::Long(value) => Ok(VmValue::Long(long_op(value, shift))),
        other => Err(format!(
            "CkVmImage binary {symbol} requires Int or Long left operand but found {other:?}"
        )),
    }
}

fn bool_binary(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    op: fn(bool, bool) -> bool,
) -> Result<VmValue, String> {
    match (left, right) {
        (VmValue::Bool(left), VmValue::Bool(right)) => Ok(VmValue::Bool(op(left, right))),
        (left, right) => Err(format!(
            "CkVmImage binary {symbol} requires Bool but found {left:?} and {right:?}"
        )),
    }
}

fn compare_values(
    left: VmValue,
    right: VmValue,
    symbol: &str,
    predicate: fn(std::cmp::Ordering) -> bool,
) -> Result<VmValue, String> {
    let ordering = match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => left.cmp(&right),
        (VmValue::Long(left), VmValue::Long(right)) => left.cmp(&right),
        (VmValue::Int(left), VmValue::Long(right)) => (left as i64).cmp(&right),
        (VmValue::Long(left), VmValue::Int(right)) => left.cmp(&(right as i64)),
        (VmValue::String(left), VmValue::String(right)) => left.cmp(&right),
        (left, right) => {
            return Err(format!(
                "CkVmImage binary {symbol} requires comparable values but found {left:?} and {right:?}"
            ));
        }
    };
    Ok(VmValue::Bool(predicate(ordering)))
}

fn value_equals(left: &VmValue, right: &VmValue) -> bool {
    match (left, right) {
        (VmValue::Int(left), VmValue::Long(right)) => i64::from(*left) == *right,
        (VmValue::Long(left), VmValue::Int(right)) => *left == i64::from(*right),
        _ => left == right,
    }
}

fn value_to_string(value: &VmValue) -> Result<String, String> {
    match value {
        VmValue::Unit => Ok("unit".to_string()),
        VmValue::Null => Ok("null".to_string()),
        VmValue::Bool(value) => Ok(value.to_string()),
        VmValue::Int(value) => Ok(value.to_string()),
        VmValue::Long(value) => Ok(value.to_string()),
        VmValue::String(value) => Ok(value.clone()),
        VmValue::Record { .. } => {
            Err("CkVmImage string concatenation with records is not supported".to_string())
        }
        VmValue::ObjectRef(_) => {
            Err("CkVmImage string concatenation with objects is not supported".to_string())
        }
    }
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
    fn rejects_string_concatenation_with_object_value() {
        let error = apply_binary_operator(
            0,
            VmValue::ObjectRef(42),
            VmValue::String("suffix".to_string()),
        )
        .unwrap_err();

        assert!(error.contains("string concatenation with objects"));
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
    fn process_current_directory_falls_back_without_native_filesystem() {
        let image = encode_empty_main_image();
        let mut handle = ImageVmHandle::create(&image, 128).unwrap();
        handle
            .attach_device_kernel(Arc::new(DeviceRuntimeKernelHandle::new(16, 1024)))
            .unwrap();

        let result = handle
            .try_native_host_import(6000, "process", "currentDirectory", Vec::new())
            .unwrap();

        match result {
            NativeHostImportResult::Fallback(arguments) => assert!(arguments.is_empty()),
            NativeHostImportResult::Handled(value) => {
                panic!(
                    "expected currentDirectory fallback without native filesystem, got {value:?}"
                )
            }
            NativeHostImportResult::SignalNoResume { .. } => {
                panic!("expected currentDirectory fallback without native filesystem")
            }
        }
    }

    fn encode_empty_main_image() -> Vec<u8> {
        image_with_code(0, vec![OP_RETURN])
    }

    fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"CKIM");
        out.push(1);
        string(&mut out, "ckl-1");
        out.extend_from_slice(&1u16.to_le_bytes());
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 0);
        i32(&mut out, 1);
        string(&mut out, "main");
        i32(&mut out, frame_size);
        i32(&mut out, code.len() as i32);
        out.extend_from_slice(&code);
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
