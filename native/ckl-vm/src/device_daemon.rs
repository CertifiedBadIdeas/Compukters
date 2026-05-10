use std::collections::BTreeMap;
use std::sync::Arc;

use crate::image_runner::ImageVmHandle;
use crate::runtime_kernel::{DeviceRuntimeKernelHandle, ProcessStatus};
use crate::signal::VmSignal;

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

pub struct DeviceDaemon {
    kernel: Arc<DeviceRuntimeKernelHandle>,
    images: BTreeMap<i32, ImageVmHandle>,
    image_handles: BTreeMap<i32, i64>,
    next_image_handle: i64,
    instruction_budget: usize,
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
            instruction_budget: instruction_budget.max(1),
        }
    }

    pub fn kernel(&self) -> Arc<DeviceRuntimeKernelHandle> {
        self.kernel.clone()
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
        let _ = argument;
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

    pub fn tick(
        &mut self,
        instructions: i64,
        wall_nanos: i64,
        server_tick: i64,
    ) -> DeviceDaemonTickSummary {
        self.kernel
            .with_kernel_mut(|kernel| {
                kernel.add_execution_quota(instructions, wall_nanos, server_tick);
            })
            .expect("daemon kernel must be lockable");
        let step = self
            .kernel
            .with_kernel_mut(|kernel| kernel.run_scheduler_step())
            .expect("daemon kernel must be lockable");
        let Some(pid) = step.selected_pid else {
            return DeviceDaemonTickSummary {
                server_tick,
                turns: 0,
                remaining_instructions: step.remaining_instructions,
                idle: true,
                halted: 0,
                host_requests: 0,
            };
        };
        let signal = match self.images.get_mut(&pid) {
            Some(image) => image.run_until_signal_decoded(),
            None => Err(format!("daemon selected pid {pid} without image")),
        };
        let mut halted = 0;
        match signal {
            Ok(VmSignal::Halt(_value)) => {
                halted = 1;
                let _ = self
                    .kernel
                    .with_kernel_mut(|kernel| kernel.complete_process(pid, 0));
                self.images.remove(&pid);
                self.image_handles.remove(&pid);
            }
            Ok(VmSignal::Pause) => {}
            Ok(other) => {
                panic!("daemon received deferred Task 4 signal during Task 3: {other:?}");
            }
            Err(message) => {
                let _ = self
                    .kernel
                    .with_kernel_mut(|kernel| kernel.mark_process_crashed(pid, message));
                self.images.remove(&pid);
                self.image_handles.remove(&pid);
            }
        }
        DeviceDaemonTickSummary {
            server_tick,
            turns: 1,
            remaining_instructions: step.remaining_instructions,
            idle: false,
            halted,
            host_requests: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn daemon_tick_runs_boot_image_to_halt() {
        let mut daemon = DeviceDaemon::new(16, 1024, 128);
        daemon.boot_image(&ckim_empty_main(), "/rom/bios.ck", "", "");

        let summary = daemon.tick(128, 1_000_000, 7);

        assert_eq!(
            summary,
            DeviceDaemonTickSummary {
                server_tick: 7,
                turns: 1,
                remaining_instructions: 127,
                idle: false,
                halted: 1,
                host_requests: 0,
            }
        );
        assert_eq!(
            daemon.process_status(1),
            DeviceDaemonProcessStatus::Completed(0),
        );
    }

    fn ckim_empty_main() -> Vec<u8> {
        image_with_code(0, vec![2])
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
