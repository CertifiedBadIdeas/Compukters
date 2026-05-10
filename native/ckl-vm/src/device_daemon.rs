use std::collections::{BTreeMap, VecDeque};
use std::sync::Arc;

use crate::display::{DisplayFrameDelta, PixelFormat};
use crate::image_runner::ImageVmHandle;
use crate::runtime_kernel::{DeviceRuntimeKernelHandle, ProcessStatus};
use crate::signal::VmSignal;
use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonBootSummary {
    pub pid: i32,
    pub image_attached: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonTickSummary {
    pub server_tick: i64,
    pub turns: i64,
    pub remaining_instructions: i64,
    pub idle: bool,
    pub halted: i64,
    pub host_requests: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceDaemonProcessStatus {
    Running,
    Completed(i32),
    Missing,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceDaemonHostRequestKind {
    HostCall,
    CompileProgram,
    Crash,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceDaemonHostRequest {
    pub request_id: i64,
    pub pid: i32,
    pub kind: DeviceDaemonHostRequestKind,
    pub module_name: Option<String>,
    pub function_name: Option<String>,
    pub arguments: Vec<VmValue>,
    pub path: Option<String>,
    pub working_directory: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DaemonSignalOutcome {
    Runnable,
    Waiting,
    Halted,
    HostRequest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PendingCompileMode {
    Spawn,
    Run,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct PendingCompile {
    parent_pid: i32,
    child_pid: i32,
    path: String,
    argument: String,
    working_directory: String,
    mode: PendingCompileMode,
}

pub struct DeviceDaemon {
    kernel: Arc<DeviceRuntimeKernelHandle>,
    images: BTreeMap<i32, ImageVmHandle>,
    image_handles: BTreeMap<i32, i64>,
    next_image_handle: i64,
    next_pid: i32,
    instruction_budget: usize,
    host_requests: VecDeque<DeviceDaemonHostRequest>,
    pending_host_requests: BTreeMap<i64, i32>,
    pending_compiles: BTreeMap<i64, PendingCompile>,
    pending_run_parents: BTreeMap<i32, i32>,
    next_host_request_id: i64,
}

impl DeviceDaemon {
    pub fn new(
        max_event_queue_size: usize,
        max_buffered_bytes_per_channel: usize,
        instruction_budget: usize,
    ) -> Self {
        Self {
            kernel: Arc::new(DeviceRuntimeKernelHandle::new(
                max_event_queue_size,
                max_buffered_bytes_per_channel,
            )),
            images: BTreeMap::new(),
            image_handles: BTreeMap::new(),
            next_image_handle: 1,
            next_pid: 2,
            instruction_budget: instruction_budget.max(1),
            host_requests: VecDeque::new(),
            pending_host_requests: BTreeMap::new(),
            pending_compiles: BTreeMap::new(),
            pending_run_parents: BTreeMap::new(),
            next_host_request_id: 1,
        }
    }

    pub fn kernel(&self) -> Arc<DeviceRuntimeKernelHandle> {
        self.kernel.clone()
    }

    #[cfg(test)]
    fn attach_child_image_for_test(
        &mut self,
        pid: i32,
        parent_pid: i32,
        path: &str,
        image_bytes: &[u8],
        argument: &str,
    ) -> Result<(), String> {
        let pending = PendingCompile {
            parent_pid,
            child_pid: pid,
            path: path.to_string(),
            argument: argument.to_string(),
            working_directory: String::new(),
            mode: PendingCompileMode::Spawn,
        };
        self.attach_child_image(&pending, image_bytes)?;
        Ok(())
    }

    pub fn boot_image(
        &mut self,
        image_bytes: &[u8],
        program_path: &str,
        argument: &str,
        working_directory: &str,
    ) -> DeviceDaemonBootSummary {
        let pid = 1;
        let mut image = ImageVmHandle::create(image_bytes, self.instruction_budget)
            .expect("boot image must decode");
        image
            .attach_device_kernel(self.kernel.clone())
            .expect("boot image must attach to daemon kernel");
        image.set_working_directory(working_directory.to_string());
        image.set_process_argument(argument.to_string());
        let image_handle = self.next_image_handle;
        self.next_image_handle = self.next_image_handle.saturating_add(1);
        let registered = self
            .kernel
            .with_kernel_mut(|kernel| {
                kernel.register_process(pid, 0, program_path.to_string())
                    && kernel.attach_process_image(pid, image_handle)
            })
            .expect("daemon kernel must be lockable");
        if registered {
            self.images.insert(pid, image);
            self.image_handles.insert(pid, image_handle);
        }
        DeviceDaemonBootSummary {
            pid,
            image_attached: registered,
        }
    }

    pub fn process_image_handle(&self, pid: i32) -> Option<i64> {
        self.image_handles.get(&pid).copied()
    }

    pub fn process_status(&self, pid: i32) -> DeviceDaemonProcessStatus {
        match self
            .kernel
            .lock()
            .map(|kernel| kernel.process_status(pid))
            .unwrap_or(ProcessStatus::Missing)
        {
            ProcessStatus::Running => DeviceDaemonProcessStatus::Running,
            ProcessStatus::Completed(exit_code) => DeviceDaemonProcessStatus::Completed(exit_code),
            ProcessStatus::Missing => DeviceDaemonProcessStatus::Missing,
        }
    }

    pub fn enqueue_event(&mut self, name: &str, arguments: Vec<VmValue>) -> bool {
        self.kernel
            .with_kernel_mut(|kernel| kernel.enqueue_event(name, arguments))
            .unwrap_or(false)
    }

    pub fn attach_filesystem(&mut self, root_path: String, quota_bytes: i64) -> Result<(), String> {
        self.kernel
            .with_kernel_mut(|kernel| kernel.attach_filesystem(root_path, quota_bytes))?
    }

    pub fn attach_display(
        &mut self,
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<(), String> {
        self.kernel
            .attach_display(display_id, width, height, pixel_format)
    }

    pub fn detach_display(&mut self, display_id: i32) -> Result<(), String> {
        self.kernel.detach_display(display_id)
    }

    pub fn drain_display_frames(&mut self) -> Result<Vec<DisplayFrameDelta>, String> {
        self.kernel.drain_display_frames()
    }

    pub fn drain_host_requests(&mut self) -> Vec<DeviceDaemonHostRequest> {
        self.host_requests.drain(..).collect()
    }

    pub fn complete_host_request(&mut self, request_id: i64, value: VmValue) -> Result<(), String> {
        let pid = self
            .pending_host_requests
            .remove(&request_id)
            .ok_or_else(|| format!("daemon host request not found: {request_id}"))?;
        let image = self
            .images
            .get_mut(&pid)
            .ok_or_else(|| format!("daemon host request pid has no image: {pid}"))?;
        image.resume_with_value(value)?;
        self.kernel
            .with_kernel_mut(|kernel| kernel.mark_process_runnable(pid))?;
        Ok(())
    }

    pub fn complete_compile_program(
        &mut self,
        request_id: i64,
        image_bytes: Option<&[u8]>,
        exit_code: i32,
    ) -> Result<(), String> {
        let pending = self
            .pending_compiles
            .remove(&request_id)
            .ok_or_else(|| format!("daemon compile request not found: {request_id}"))?;
        let success = image_bytes
            .map(|bytes| self.attach_child_image(&pending, bytes))
            .transpose()?
            .unwrap_or(false);

        match (pending.mode, success) {
            (PendingCompileMode::Spawn, true) => {
                self.resume_process_with_value(pending.parent_pid, VmValue::Int(pending.child_pid))?;
            }
            (PendingCompileMode::Spawn, false) => {
                self.kernel.with_kernel_mut(|kernel| {
                    if matches!(
                        kernel.process_status(pending.child_pid),
                        ProcessStatus::Missing
                    ) {
                        let _ = kernel.register_process(
                            pending.child_pid,
                            pending.parent_pid,
                            pending.path.clone(),
                        );
                    }
                    kernel.complete_process(pending.child_pid, exit_code.max(1));
                    kernel.mark_process_runnable(pending.parent_pid)
                })?;
                self.resume_process_with_value(pending.parent_pid, VmValue::Int(pending.child_pid))?;
            }
            (PendingCompileMode::Run, true) => {
                self.pending_run_parents
                    .insert(pending.child_pid, pending.parent_pid);
                self.kernel.with_kernel_mut(|kernel| {
                    kernel.mark_process_waiting_for_process(pending.parent_pid, pending.child_pid)
                })?;
            }
            (PendingCompileMode::Run, false) => {
                self.resume_process_with_value(pending.parent_pid, VmValue::Int(exit_code.max(1)))?;
            }
        }
        Ok(())
    }

    pub fn refill_execution_quota(
        &mut self,
        instructions: i64,
        wall_nanos: i64,
        server_tick: i64,
    ) {
        self.kernel
            .with_kernel_mut(|kernel| {
                kernel.add_execution_quota(instructions, wall_nanos, server_tick);
            })
            .expect("daemon kernel must be lockable");
    }

    pub fn run_ready_until_blocked(&mut self, max_turns: i64) -> DeviceDaemonTickSummary {
        let mut turns = 0;
        let mut halted = 0;
        let mut host_requests = 0;
        let mut remaining_instructions = 0;
        let mut server_tick = 0;
        let max_turns = max_turns.max(1);

        while turns < max_turns {
            let step = self
                .kernel
                .with_kernel_mut(|kernel| kernel.run_scheduler_step())
                .expect("daemon kernel must be lockable");
            server_tick = step.server_tick;
            remaining_instructions = step.remaining_instructions;
            let Some(pid) = step.selected_pid else {
                return DeviceDaemonTickSummary {
                    server_tick,
                    turns,
                    remaining_instructions,
                    idle: true,
                    halted,
                    host_requests,
                };
            };

            turns += 1;
            let signal = match self.images.get_mut(&pid) {
                Some(image) => image.run_until_signal_decoded(),
                None => Err(format!("daemon selected pid {pid} without image")),
            };
            match signal {
                Ok(signal) => match self.handle_signal(pid, signal, server_tick) {
                    Ok(DaemonSignalOutcome::Halted) => halted += 1,
                    Ok(DaemonSignalOutcome::HostRequest) => host_requests += 1,
                    Ok(DaemonSignalOutcome::Runnable | DaemonSignalOutcome::Waiting) => {}
                    Err(message) => self.crash_process(pid, message),
                },
                Err(message) => {
                    self.crash_process(pid, message);
                }
            }
        }

        DeviceDaemonTickSummary {
            server_tick,
            turns,
            remaining_instructions,
            idle: false,
            halted,
            host_requests,
        }
    }

    fn crash_process(&mut self, pid: i32, message: String) {
        let _ = self
            .kernel
            .with_kernel_mut(|kernel| kernel.mark_process_crashed(pid, message));
        self.images.remove(&pid);
        self.image_handles.remove(&pid);
    }

    fn handle_signal(
        &mut self,
        pid: i32,
        signal: VmSignal,
        server_tick: i64,
    ) -> Result<DaemonSignalOutcome, String> {
        match signal {
            VmSignal::Halt(_value) => {
                let exit_code = 0;
                self.kernel
                    .with_kernel_mut(|kernel| kernel.complete_process(pid, exit_code))?;
                self.images.remove(&pid);
                self.image_handles.remove(&pid);
                if let Some(parent_pid) = self.pending_run_parents.remove(&pid) {
                    self.resume_process_with_value(parent_pid, VmValue::Int(exit_code))?;
                }
                Ok(DaemonSignalOutcome::Halted)
            }
            VmSignal::Pause => Ok(DaemonSignalOutcome::Runnable),
            VmSignal::Yield => {
                if let Some(image) = self.images.get_mut(&pid) {
                    image.resume_with_value(VmValue::Unit)?;
                }
                self.kernel
                    .with_kernel_mut(|kernel| kernel.mark_process_runnable(pid))?;
                Ok(DaemonSignalOutcome::Runnable)
            }
            VmSignal::Sleep(ticks) => {
                if let Some(image) = self.images.get_mut(&pid) {
                    image.resume_with_value(VmValue::Unit)?;
                }
                let until_tick = server_tick.saturating_add(ticks.max(1));
                self.kernel
                    .with_kernel_mut(|kernel| kernel.mark_process_sleeping(pid, until_tick))?;
                Ok(DaemonSignalOutcome::Waiting)
            }
            VmSignal::WaitEvent(filter) => {
                self.kernel
                    .with_kernel_mut(|kernel| kernel.mark_process_waiting_for_event(pid, filter))?;
                Ok(DaemonSignalOutcome::Waiting)
            }
            VmSignal::WaitPoll {
                channel,
                wake_sequence: _wake_sequence,
            } => {
                self.kernel
                    .with_kernel_mut(|kernel| kernel.mark_process_waiting_for_ipc(pid, channel))?;
                Ok(DaemonSignalOutcome::Waiting)
            }
            VmSignal::WaitProcess {
                pid: target_pid,
                wake_sequence: _wake_sequence,
            } => {
                self.kernel.with_kernel_mut(|kernel| {
                    kernel.mark_process_waiting_for_process(pid, target_pid)
                })?;
                Ok(DaemonSignalOutcome::Waiting)
            }
            VmSignal::HostCall {
                module_name,
                function_name,
                arguments,
            } => {
                if module_name == "process" && (function_name == "spawn" || function_name == "run")
                {
                    return self.request_compile_program(pid, &function_name, arguments);
                }
                let request_id = self.next_host_request_id;
                self.next_host_request_id = self.next_host_request_id.saturating_add(1).max(1);
                self.host_requests.push_back(DeviceDaemonHostRequest {
                    request_id,
                    pid,
                    kind: DeviceDaemonHostRequestKind::HostCall,
                    module_name: Some(module_name),
                    function_name: Some(function_name),
                    arguments,
                    path: None,
                    working_directory: None,
                });
                self.pending_host_requests.insert(request_id, pid);
                self.kernel.with_kernel_mut(|kernel| {
                    kernel.mark_process_waiting_for_host_request(pid, request_id)
                })?;
                Ok(DaemonSignalOutcome::HostRequest)
            }
        }
    }

    fn request_compile_program(
        &mut self,
        parent_pid: i32,
        function_name: &str,
        arguments: Vec<VmValue>,
    ) -> Result<DaemonSignalOutcome, String> {
        let path = string_argument(&arguments, 0, "process path")?.to_string();
        let argument = arguments
            .get(1)
            .map(|_| string_argument(&arguments, 1, "process argument"))
            .transpose()?
            .unwrap_or("")
            .to_string();
        let working_directory = self
            .images
            .get(&parent_pid)
            .map(|image| image.working_directory().to_string())
            .unwrap_or_default();
        let child_pid = self.allocate_pid();
        let mode = if function_name == "run" {
            PendingCompileMode::Run
        } else {
            PendingCompileMode::Spawn
        };
        let request_id = self.next_host_request_id;
        self.next_host_request_id = self.next_host_request_id.saturating_add(1).max(1);
        self.pending_compiles.insert(
            request_id,
            PendingCompile {
                parent_pid,
                child_pid,
                path: path.clone(),
                argument: argument.clone(),
                working_directory: working_directory.clone(),
                mode,
            },
        );
        self.host_requests.push_back(DeviceDaemonHostRequest {
            request_id,
            pid: parent_pid,
            kind: DeviceDaemonHostRequestKind::CompileProgram,
            module_name: Some("process".to_string()),
            function_name: Some(function_name.to_string()),
            arguments: vec![VmValue::Int(child_pid), VmValue::String(argument)],
            path: Some(path),
            working_directory: Some(working_directory),
        });
        self.kernel.with_kernel_mut(|kernel| {
            kernel.mark_process_waiting_for_host_request(parent_pid, request_id)
        })?;
        Ok(DaemonSignalOutcome::HostRequest)
    }

    fn allocate_pid(&mut self) -> i32 {
        let pid = self.next_pid.max(2);
        self.next_pid = self.next_pid.saturating_add(1).max(2);
        pid
    }

    fn attach_child_image(
        &mut self,
        pending: &PendingCompile,
        image_bytes: &[u8],
    ) -> Result<bool, String> {
        if image_bytes.is_empty() {
            return Ok(false);
        }
        let mut image = ImageVmHandle::create(image_bytes, self.instruction_budget)?;
        image.attach_device_kernel(self.kernel.clone())?;
        image.set_working_directory(pending.working_directory.clone());
        image.set_process_argument(pending.argument.clone());
        let image_handle = self.next_image_handle;
        self.next_image_handle = self.next_image_handle.saturating_add(1);
        let registered = self.kernel.with_kernel_mut(|kernel| {
            kernel.register_process(pending.child_pid, pending.parent_pid, pending.path.clone())
                && kernel.attach_process_image(pending.child_pid, image_handle)
        })?;
        if registered {
            self.images.insert(pending.child_pid, image);
            self.image_handles.insert(pending.child_pid, image_handle);
        }
        Ok(registered)
    }

    fn resume_process_with_value(&mut self, pid: i32, value: VmValue) -> Result<(), String> {
        let image = self
            .images
            .get_mut(&pid)
            .ok_or_else(|| format!("daemon pid has no image: {pid}"))?;
        image.resume_with_value(value)?;
        self.kernel
            .with_kernel_mut(|kernel| kernel.mark_process_runnable(pid))?;
        Ok(())
    }
}

fn string_argument<'a>(
    arguments: &'a [VmValue],
    index: usize,
    label: &str,
) -> Result<&'a str, String> {
    match arguments.get(index) {
        Some(VmValue::String(value)) => Ok(value.as_str()),
        Some(value) => Err(format!("{label} must be String but found {value:?}")),
        None => Err(format!("{label} is missing")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const OP_RETURN: u8 = 2;
    const OP_PUSH_CONSTANT: u8 = 3;
    const OP_CALL_HOST: u8 = 4;
    const OP_YIELD: u8 = 24;
    const OP_SLEEP: u8 = 25;

    fn run_daemon_slice(
        daemon: &mut DeviceDaemon,
        instructions: i64,
        wall_nanos: i64,
        server_tick: i64,
    ) -> DeviceDaemonTickSummary {
        daemon.refill_execution_quota(instructions, wall_nanos, server_tick);
        daemon.run_ready_until_blocked(instructions.max(1))
    }

    #[test]
    fn daemon_registers_boot_process_with_owned_kernel() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);

        let summary = daemon.boot_image(&ckim_empty_main(), "/rom/bios.ck", "", "");

        assert_eq!(
            summary,
            DeviceDaemonBootSummary {
                pid: 1,
                image_attached: true,
            }
        );
        assert_eq!(daemon.process_image_handle(1), Some(1));
    }

    #[test]
    fn daemon_refill_and_run_ready_runs_boot_image_to_halt() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_empty_main(), "/rom/bios.ck", "", "");

        let summary = run_daemon_slice(&mut daemon, 128, 1_000_000, 7);

        assert_eq!(
            summary,
            DeviceDaemonTickSummary {
                server_tick: 7,
                turns: 1,
                remaining_instructions: 127,
                idle: true,
                halted: 1,
                host_requests: 0,
            }
        );
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
    }

    #[test]
    fn daemon_handles_yield_by_resuming_unit_and_requeueing_process() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_yields_then_halts(), "/rom/yield.ck", "", "");

        let first = run_daemon_slice(&mut daemon, 1, 1_000_000, 10);
        assert_eq!(first.turns, 1);
        assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Running);

        let second = run_daemon_slice(&mut daemon, 1, 1_000_000, 11);
        assert_eq!(second.halted, 1);
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
    }

    #[test]
    fn daemon_moves_sleeping_process_until_due_tick() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_sleeps_one_tick_then_halts(), "/rom/sleep.ck", "", "");

        let first = run_daemon_slice(&mut daemon, 1, 1_000_000, 20);
        let second = run_daemon_slice(&mut daemon, 1, 1_000_000, 20);
        let third = run_daemon_slice(&mut daemon, 1, 1_000_000, 21);

        assert_eq!(first.turns, 1);
        assert!(second.idle);
        assert_eq!(third.halted, 1);
    }

    #[test]
    fn daemon_handles_wait_poll_by_parking_process() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        let channel = daemon
            .kernel()
            .with_kernel_mut(|kernel| kernel.open_ipc_channel())
            .unwrap()
            .unwrap();
        daemon.boot_image(
            &ckim_polls_empty_channel_then_halts(channel),
            "/rom/poll.ck",
            "",
            "",
        );

        let first = run_daemon_slice(&mut daemon, 1, 1_000_000, 30);
        let second = run_daemon_slice(&mut daemon, 1, 1_000_000, 31);

        assert_eq!(first.turns, 1);
        assert!(second.idle);
        assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Running);
    }

    #[test]
    fn daemon_handles_wait_process_by_parking_process() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_waits_for_pid_then_halts(2), "/rom/wait.ck", "", "");
        daemon
            .kernel()
            .with_kernel_mut(|kernel| {
                assert!(kernel.register_process(2, 1, "/rom/child.ck".to_string()));
                assert!(kernel.mark_process_sleeping(2, 10_000));
            })
            .unwrap();

        let first = run_daemon_slice(&mut daemon, 1, 1_000_000, 40);
        let second = run_daemon_slice(&mut daemon, 1, 1_000_000, 41);

        assert_eq!(first.turns, 1);
        assert!(second.idle);
        assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Running);
    }

    #[test]
    fn daemon_handles_wait_event_by_parking_process() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_empty_main(), "/rom/event.ck", "", "");

        let outcome = daemon
            .handle_signal(1, VmSignal::WaitEvent(Some("key".to_string())), 50)
            .unwrap();
        let summary = run_daemon_slice(&mut daemon, 1, 1_000_000, 51);

        assert_eq!(outcome, DaemonSignalOutcome::Waiting);
        assert!(summary.idle);
        assert_eq!(daemon.process_status(1), DeviceDaemonProcessStatus::Running);
    }

    #[test]
    fn daemon_host_call_parks_process_and_can_resume_with_value() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_calls_system_log_then_halts(), "/rom/host.ck", "", "");

        let first = run_daemon_slice(&mut daemon, 4, 1_000_000, 1);
        let requests = daemon.drain_host_requests();

        assert_eq!(first.host_requests, 1);
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].pid, 1);
        assert_eq!(requests[0].module_name.as_deref(), Some("system"));
        assert_eq!(requests[0].function_name.as_deref(), Some("log"));

        daemon
            .complete_host_request(requests[0].request_id, VmValue::Unit)
            .unwrap();
        let second = run_daemon_slice(&mut daemon, 4, 1_000_000, 2);

        assert_eq!(second.halted, 1);
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
    }

    #[test]
    fn daemon_process_spawn_emits_compile_program_request() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(
            &ckim_spawns_child_then_halts("child.ck", "stdio-v1"),
            "/rom/parent.ck",
            "",
            "rom",
        );

        let first = run_daemon_slice(&mut daemon, 8, 1_000_000, 1);
        let requests = daemon.drain_host_requests();

        assert_eq!(first.host_requests, 1);
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].kind, DeviceDaemonHostRequestKind::CompileProgram);
        assert_eq!(requests[0].module_name.as_deref(), Some("process"));
        assert_eq!(requests[0].function_name.as_deref(), Some("spawn"));
        assert_eq!(requests[0].path.as_deref(), Some("child.ck"));
        assert_eq!(requests[0].working_directory.as_deref(), Some("rom"));
        assert_eq!(
            requests[0].arguments,
            vec![VmValue::Int(2), VmValue::String("stdio-v1".to_string())],
        );
    }

    #[test]
    fn daemon_process_spawn_compile_failure_resumes_parent_with_child_pid() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(
            &ckim_spawns_child_then_halts("missing.ck", ""),
            "/rom/parent.ck",
            "",
            "",
        );

        run_daemon_slice(&mut daemon, 8, 1_000_000, 1);
        let mut requests = daemon.drain_host_requests();
        assert_eq!(requests.len(), 1);
        let request = requests.pop().unwrap();
        daemon
            .complete_compile_program(request.request_id, None, 1)
            .unwrap();
        let second = run_daemon_slice(&mut daemon, 8, 1_000_000, 2);

        assert_eq!(second.halted, 1);
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
        assert_eq!(
            daemon.process_status(2),
            DeviceDaemonProcessStatus::Completed(1),
        );
    }

    #[test]
    fn daemon_ipc_read_waits_until_channel_has_text() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        let channel = daemon
            .kernel()
            .with_kernel_mut(|kernel| kernel.open_ipc_channel())
            .unwrap()
            .unwrap();
        daemon.boot_image(
            &ckim_reads_ipc_then_logs(channel),
            "/rom/read.ck",
            "",
            "",
        );

        let waiting = run_daemon_slice(&mut daemon, 8, 1_000_000, 1);
        let idle = run_daemon_slice(&mut daemon, 8, 1_000_000, 2);
        daemon
            .kernel()
            .with_kernel_mut(|kernel| kernel.write_ipc(channel, "hello\n"))
            .unwrap()
            .unwrap();
        let woke = run_daemon_slice(&mut daemon, 8, 1_000_000, 3);
        let request = daemon.drain_host_requests().pop().unwrap();

        assert_eq!(waiting.host_requests, 0);
        assert!(idle.idle);
        assert_eq!(woke.host_requests, 1);
        assert_eq!(request.module_name.as_deref(), Some("system"));
        assert_eq!(request.function_name.as_deref(), Some("log"));
        assert_eq!(request.arguments, vec![VmValue::String("hello\n".to_string())]);
    }

    #[test]
    fn daemon_run_ready_until_blocked_runs_multiple_processes_in_one_pass() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_yields_then_halts(), "/rom/terminal.ck", "", "");
        daemon
            .attach_child_image_for_test(2, 1, "/rom/shell.ck", &ckim_yields_then_halts(), "")
            .expect("child image should attach");

        daemon.refill_execution_quota(16, 1_000_000, 70);
        let summary = daemon.run_ready_until_blocked(16);

        assert_eq!(summary.server_tick, 70);
        assert_eq!(summary.turns, 4);
        assert_eq!(summary.halted, 2);
        assert!(summary.idle);
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
        assert_eq!(
            daemon.process_status(2),
            DeviceDaemonProcessStatus::Completed(0),
        );
    }

    fn ckim_empty_main() -> Vec<u8> {
        image_with_code(0, vec![OP_RETURN])
    }

    fn ckim_yields_then_halts() -> Vec<u8> {
        image_with_code(0, vec![OP_YIELD, OP_RETURN])
    }

    fn ckim_sleeps_one_tick_then_halts() -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_SLEEP);
        code.push(OP_RETURN);
        image_with_constants_and_code(vec![ConstantFixture::Long(1)], 0, code)
    }

    fn ckim_polls_empty_channel_then_halts(channel: i32) -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_CALL_HOST);
        i32(&mut code, 1);
        i32(&mut code, 1);
        code.push(OP_RETURN);
        image_with_constants_imports_and_code(
            vec![ConstantFixture::Int(channel)],
            vec![HostImportFixture::new(1, "runtime", "poll")],
            0,
            code,
        )
    }

    fn ckim_waits_for_pid_then_halts(pid: i32) -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_CALL_HOST);
        i32(&mut code, 1);
        i32(&mut code, 1);
        code.push(OP_RETURN);
        image_with_constants_imports_and_code(
            vec![ConstantFixture::Int(pid)],
            vec![HostImportFixture::new(1, "process", "wait")],
            0,
            code,
        )
    }

    fn ckim_calls_system_log_then_halts() -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_CALL_HOST);
        i32(&mut code, 1);
        i32(&mut code, 1);
        code.push(OP_RETURN);
        image_with_constants_imports_and_code(
            vec![ConstantFixture::String("hello".to_string())],
            vec![HostImportFixture::new(1, "system", "log")],
            0,
            code,
        )
    }

    fn ckim_spawns_child_then_halts(path: &str, argument: &str) -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 1);
        code.push(OP_CALL_HOST);
        i32(&mut code, 1);
        i32(&mut code, 2);
        code.push(OP_RETURN);
        image_with_constants_imports_and_code(
            vec![
                ConstantFixture::String(path.to_string()),
                ConstantFixture::String(argument.to_string()),
            ],
            vec![HostImportFixture::new(1, "process", "spawn")],
            0,
            code,
        )
    }

    fn ckim_reads_ipc_then_logs(channel: i32) -> Vec<u8> {
        let mut code = Vec::new();
        code.push(OP_PUSH_CONSTANT);
        i32(&mut code, 0);
        code.push(OP_CALL_HOST);
        i32(&mut code, 1);
        i32(&mut code, 1);
        code.push(OP_CALL_HOST);
        i32(&mut code, 2);
        i32(&mut code, 1);
        code.push(OP_RETURN);
        image_with_constants_imports_and_code(
            vec![ConstantFixture::Int(channel)],
            vec![
                HostImportFixture::new(1, "ipc", "read"),
                HostImportFixture::new(2, "system", "log"),
            ],
            0,
            code,
        )
    }

    fn image_with_code(frame_size: i32, code: Vec<u8>) -> Vec<u8> {
        image_with_constants_and_code(Vec::new(), frame_size, code)
    }

    fn image_with_constants_and_code(
        constants: Vec<ConstantFixture>,
        frame_size: i32,
        code: Vec<u8>,
    ) -> Vec<u8> {
        image_with_constants_imports_and_code(constants, Vec::new(), frame_size, code)
    }

    fn image_with_constants_imports_and_code(
        constants: Vec<ConstantFixture>,
        host_imports: Vec<HostImportFixture>,
        frame_size: i32,
        code: Vec<u8>,
    ) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"CKIM");
        out.push(1);
        string(&mut out, "ckl-1");
        out.extend_from_slice(&1u16.to_le_bytes());
        i32(&mut out, 0);
        i32(&mut out, constants.len() as i32);
        for constant in constants {
            constant.write_to(&mut out);
        }
        i32(&mut out, host_imports.len() as i32);
        for host_import in host_imports {
            host_import.write_to(&mut out);
        }
        i32(&mut out, 0);
        i32(&mut out, 1);
        string(&mut out, "main");
        i32(&mut out, frame_size);
        i32(&mut out, code.len() as i32);
        out.extend_from_slice(&code);
        out
    }

    enum ConstantFixture {
        Int(i32),
        Long(i64),
        String(String),
    }

    impl ConstantFixture {
        fn write_to(self, out: &mut Vec<u8>) {
            match self {
                ConstantFixture::Int(value) => {
                    out.push(2);
                    i32(out, value);
                }
                ConstantFixture::Long(value) => {
                    out.push(3);
                    out.extend_from_slice(&value.to_le_bytes());
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
    }

    impl HostImportFixture {
        fn new(id: i32, module_name: &'static str, function_name: &'static str) -> Self {
            Self {
                id,
                module_name,
                function_name,
            }
        }

        fn write_to(self, out: &mut Vec<u8>) {
            i32(out, self.id);
            string(out, self.module_name);
            string(out, self.function_name);
            i32(out, 1);
            string(out, "Int");
            string(out, "Any");
        }
    }

    fn string(out: &mut Vec<u8>, value: &str) {
        i32(out, value.len() as i32);
        out.extend_from_slice(value.as_bytes());
    }

    fn i32(out: &mut Vec<u8>, value: i32) {
        out.extend_from_slice(&value.to_le_bytes());
    }
}
