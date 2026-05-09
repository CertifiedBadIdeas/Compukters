use std::collections::{BTreeMap, VecDeque};
use std::sync::{Condvar, Mutex, MutexGuard};
use std::time::Duration;

use crate::display::{DeviceDisplayRegistry, DisplayFrameDelta, PixelFormat};
use crate::filesystem::DeviceFilesystem;
use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueuedEvent {
    pub name: String,
    pub arguments: Vec<VmValue>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PulledEvent {
    pub name: String,
    pub id: i32,
    pub arg_count: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProcessStatus {
    Running,
    Completed(i32),
    Missing,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ProcessEntry {
    parent_pid: i32,
    program_path: String,
    state: ProcessState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProcessState {
    Running,
    Completed { exit_code: i32 },
}

pub struct DeviceRuntimeKernel {
    event_queue: VecDeque<QueuedEvent>,
    deferred_events: VecDeque<QueuedEvent>,
    captured_events: BTreeMap<i32, Vec<VmValue>>,
    processes: BTreeMap<i32, ProcessEntry>,
    ipc: IpcRegistry,
    pub displays: DeviceDisplayRegistry,
    pub filesystem: Option<DeviceFilesystem>,
    next_event_id: i32,
    wake_sequence: i64,
    display_wake_sequence: i64,
    max_event_queue_size: usize,
}

impl DeviceRuntimeKernel {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            event_queue: VecDeque::new(),
            deferred_events: VecDeque::new(),
            captured_events: BTreeMap::new(),
            processes: BTreeMap::new(),
            ipc: IpcRegistry::new(max_buffered_bytes_per_channel),
            displays: DeviceDisplayRegistry::new(),
            filesystem: None,
            next_event_id: 1,
            wake_sequence: 0,
            display_wake_sequence: 0,
            max_event_queue_size: max_event_queue_size.max(1),
        }
    }

    pub fn attach_filesystem(&mut self, root_path: String, quota_bytes: i64) -> Result<(), String> {
        self.filesystem = Some(DeviceFilesystem::attach(root_path, quota_bytes)?);
        Ok(())
    }

    pub fn register_process(
        &mut self,
        pid: i32,
        parent_pid: i32,
        program_path: String,
    ) -> bool {
        if pid <= 0 || self.processes.contains_key(&pid) {
            return false;
        }
        self.processes.insert(
            pid,
            ProcessEntry {
                parent_pid,
                program_path,
                state: ProcessState::Running,
            },
        );
        true
    }

    pub fn complete_process(&mut self, pid: i32, exit_code: i32) -> bool {
        let Some(entry) = self.processes.get_mut(&pid) else {
            return false;
        };
        if !matches!(entry.state, ProcessState::Running) {
            return false;
        }
        entry.state = ProcessState::Completed { exit_code };
        self.wake_sequence = self.wake_sequence.saturating_add(1);
        true
    }

    pub fn process_status(&self, pid: i32) -> ProcessStatus {
        match self.processes.get(&pid) {
            Some(entry) => match entry.state {
                ProcessState::Running => ProcessStatus::Running,
                ProcessState::Completed { exit_code } => ProcessStatus::Completed(exit_code),
            },
            None => ProcessStatus::Missing,
        }
    }

    pub fn enqueue_event(&mut self, name: &str, arguments: Vec<VmValue>) -> bool {
        if self.event_queue.len() >= self.max_event_queue_size {
            let _ = self.event_queue.pop_front();
        }
        self.event_queue.push_back(QueuedEvent {
            name: name.to_string(),
            arguments,
        });
        self.wake_sequence = self.wake_sequence.saturating_add(1);
        true
    }

    pub fn try_pull_event(&mut self, filter: Option<&str>) -> Option<PulledEvent> {
        let index = self
            .event_queue
            .iter()
            .position(|event| filter.map_or(true, |expected| event.name == expected))?;
        let event = self.event_queue.remove(index)?;
        let id = self.next_event_id;
        self.next_event_id = self.next_event_id.saturating_add(1).max(1);
        let arg_count = event.arguments.len() as i32;
        self.captured_events.insert(id, event.arguments);
        Some(PulledEvent {
            name: event.name,
            id,
            arg_count,
        })
    }

    pub fn event_arg_count(&self, event_id: i32) -> i32 {
        self.captured_events
            .get(&event_id)
            .map_or(0, |arguments| arguments.len() as i32)
    }

    pub fn event_arg_int(&self, event_id: i32, index: i32) -> i32 {
        match self.event_arg(event_id, index) {
            Some(VmValue::Int(value)) => *value,
            _ => 0,
        }
    }

    pub fn event_arg_bool(&self, event_id: i32, index: i32) -> bool {
        match self.event_arg(event_id, index) {
            Some(VmValue::Bool(value)) => *value,
            _ => false,
        }
    }

    pub fn event_arg_string(&self, event_id: i32, index: i32) -> String {
        match self.event_arg(event_id, index) {
            Some(VmValue::String(value)) => value.clone(),
            _ => String::new(),
        }
    }

    fn event_arg(&self, event_id: i32, index: i32) -> Option<&VmValue> {
        if index < 0 {
            return None;
        }
        self.captured_events.get(&event_id)?.get(index as usize)
    }

    pub fn open_ipc_channel(&mut self) -> Result<i32, String> {
        self.ipc.open()
    }

    pub fn write_ipc(&mut self, channel: i32, text: &str) -> Result<(), String> {
        self.ipc.write(channel, text)?;
        self.wake_sequence = self.wake_sequence.saturating_add(1);
        Ok(())
    }

    pub fn try_read_ipc(&mut self, channel: i32) -> Result<String, String> {
        self.ipc.try_read(channel)
    }

    pub fn close_ipc(&mut self, channel: i32) -> Result<(), String> {
        self.ipc.close(channel)?;
        self.wake_sequence = self.wake_sequence.saturating_add(1);
        Ok(())
    }

    pub fn wake_sequence(&self) -> i64 {
        self.wake_sequence
    }

    pub fn display_wake_sequence(&self) -> i64 {
        self.display_wake_sequence
    }

    fn advance_display_wake_sequence(&mut self) {
        self.display_wake_sequence = self.display_wake_sequence.saturating_add(1);
    }

    pub fn attach_placeholder_payload_event(&mut self, name: &str, _payload: &[u8]) -> bool {
        self.enqueue_event(name, Vec::new())
    }
}

pub struct DeviceRuntimeKernelHandle {
    kernel: Mutex<DeviceRuntimeKernel>,
    wake: Condvar,
    display_wake: Condvar,
}

impl DeviceRuntimeKernelHandle {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            kernel: Mutex::new(DeviceRuntimeKernel::new(
                max_event_queue_size,
                max_buffered_bytes_per_channel,
            )),
            wake: Condvar::new(),
            display_wake: Condvar::new(),
        }
    }

    pub fn lock(&self) -> Result<MutexGuard<'_, DeviceRuntimeKernel>, String> {
        self.kernel
            .lock()
            .map_err(|_| "native device runtime kernel lock is poisoned".to_string())
    }

    pub fn with_kernel_mut<T>(
        &self,
        action: impl FnOnce(&mut DeviceRuntimeKernel) -> T,
    ) -> Result<T, String> {
        let mut kernel = self.lock()?;
        let before = kernel.wake_sequence();
        let result = action(&mut kernel);
        if kernel.wake_sequence() != before {
            self.wake.notify_all();
        }
        Ok(result)
    }

    pub fn wake_sequence(&self) -> Result<i64, String> {
        Ok(self.lock()?.wake_sequence())
    }

    pub fn display_wake_sequence(&self) -> Result<i64, String> {
        Ok(self.lock()?.display_wake_sequence())
    }

    pub fn attach_display(
        &self,
        display_id: i32,
        width: i32,
        height: i32,
        pixel_format: PixelFormat,
    ) -> Result<(), String> {
        let mut kernel = self.lock()?;
        let emitted = kernel
            .displays
            .attach(display_id, width, height, pixel_format)?;
        if emitted {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn detach_display(&self, display_id: i32) -> Result<(), String> {
        let mut kernel = self.lock()?;
        if kernel.displays.detach(display_id) {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn present_display(&self, display_id: i32) -> Result<(), String> {
        let mut kernel = self.lock()?;
        if kernel.displays.present(display_id) {
            kernel.advance_display_wake_sequence();
            self.display_wake.notify_all();
        }
        Ok(())
    }

    pub fn drain_display_frames(&self) -> Result<Vec<DisplayFrameDelta>, String> {
        let mut kernel = self.lock()?;
        Ok(kernel.displays.drain_frames())
    }

    pub fn wait_for_wake(&self, observed_sequence: i64, timeout: Duration) -> Result<i64, String> {
        let kernel = self.lock()?;
        if kernel.wake_sequence() > observed_sequence {
            return Ok(kernel.wake_sequence());
        }
        let (kernel, _) = self
            .wake
            .wait_timeout_while(kernel, timeout, |kernel| {
                kernel.wake_sequence() <= observed_sequence
            })
            .map_err(|_| "native device runtime kernel wait lock is poisoned".to_string())?;
        Ok(kernel.wake_sequence())
    }

    pub fn wait_for_process_wake(
        &self,
        _pid: i32,
        observed_sequence: i64,
        timeout: Duration,
    ) -> Result<i64, String> {
        self.wait_for_wake(observed_sequence, timeout)
    }

    pub fn wait_for_display_wake(
        &self,
        observed_sequence: i64,
        timeout: Duration,
    ) -> Result<i64, String> {
        let kernel = self.lock()?;
        if kernel.display_wake_sequence() > observed_sequence {
            return Ok(kernel.display_wake_sequence());
        }
        let (kernel, _) = self
            .display_wake
            .wait_timeout_while(kernel, timeout, |kernel| {
                kernel.display_wake_sequence() <= observed_sequence
            })
            .map_err(|_| "native display frame wait lock is poisoned".to_string())?;
        Ok(kernel.display_wake_sequence())
    }
}

struct IpcRegistry {
    next_id: i32,
    channels: BTreeMap<i32, IpcChannel>,
    max_buffered_bytes_per_channel: usize,
}

impl IpcRegistry {
    fn new(max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            next_id: 1,
            channels: BTreeMap::new(),
            max_buffered_bytes_per_channel: max_buffered_bytes_per_channel.max(1),
        }
    }

    fn open(&mut self) -> Result<i32, String> {
        if self.next_id == i32::MAX {
            return Err("native device runtime kernel IPC id overflow".to_string());
        }
        let id = self.next_id;
        self.next_id += 1;
        self.channels
            .insert(id, IpcChannel::new(self.max_buffered_bytes_per_channel));
        Ok(id)
    }

    fn write(&mut self, channel: i32, text: &str) -> Result<(), String> {
        let channel_id = channel;
        let channel = self
            .channels
            .get_mut(&channel)
            .ok_or_else(|| format!("IPC channel not found: {channel_id}"))?;
        if channel.closed {
            return Err(format!("IPC channel is closed: {channel_id}"));
        }
        let remaining = channel
            .max_buffered_bytes
            .saturating_sub(channel.buffer.len());
        channel
            .buffer
            .push_str(&text.chars().take(remaining).collect::<String>());
        Ok(())
    }

    fn try_read(&mut self, channel: i32) -> Result<String, String> {
        let channel = self
            .channels
            .get_mut(&channel)
            .ok_or_else(|| format!("IPC channel not found: {channel}"))?;
        if channel.closed {
            return Ok(String::new());
        }
        Ok(std::mem::take(&mut channel.buffer))
    }

    fn close(&mut self, channel: i32) -> Result<(), String> {
        let channel = self
            .channels
            .get_mut(&channel)
            .ok_or_else(|| format!("IPC channel not found: {channel}"))?;
        channel.closed = true;
        channel.buffer.clear();
        Ok(())
    }
}

struct IpcChannel {
    buffer: String,
    max_buffered_bytes: usize,
    closed: bool,
}

impl IpcChannel {
    fn new(max_buffered_bytes: usize) -> Self {
        Self {
            buffer: String::new(),
            max_buffered_bytes,
            closed: false,
        }
    }
}

#[allow(dead_code)]
fn _deferred_queue_len(kernel: &DeviceRuntimeKernel) -> usize {
    kernel.deferred_events.len()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::display::PixelFormat;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn display_wake_sequence_advances_when_attach_queues_full_refresh() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        let before = handle.display_wake_sequence().unwrap();

        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();

        assert!(handle.display_wake_sequence().unwrap() > before);
        assert!(!handle.drain_display_frames().unwrap().is_empty());
    }

    #[test]
    fn display_wake_sequence_advances_when_present_emits_frame() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let before = handle.display_wake_sequence().unwrap();

        handle
            .with_kernel_mut(|kernel| {
                kernel.displays.fill_rect(7, 0, 0, 2, 2, 0x07e0);
            })
            .unwrap();
        handle.present_display(7).unwrap();

        assert!(handle.display_wake_sequence().unwrap() > before);
        assert!(!handle.drain_display_frames().unwrap().is_empty());
    }

    #[test]
    fn display_wake_sequence_does_not_advance_when_present_has_no_dirty_frame() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let before = handle.display_wake_sequence().unwrap();

        handle.present_display(7).unwrap();

        assert_eq!(before, handle.display_wake_sequence().unwrap());
    }

    #[test]
    fn wait_for_display_wake_returns_after_present() {
        let handle = std::sync::Arc::new(DeviceRuntimeKernelHandle::new(16, 1024));
        handle
            .attach_display(7, 16, 16, PixelFormat::Rgb565)
            .unwrap();
        let _ = handle.drain_display_frames().unwrap();
        let observed = handle.display_wake_sequence().unwrap();
        let waiter = handle.clone();

        let join = thread::spawn(move || {
            waiter
                .wait_for_display_wake(observed, Duration::from_millis(500))
                .unwrap()
        });

        thread::sleep(Duration::from_millis(25));
        handle
            .with_kernel_mut(|kernel| {
                kernel.displays.fill_rect(7, 0, 0, 2, 2, 0xffff);
            })
            .unwrap();
        handle.present_display(7).unwrap();

        assert!(join.join().unwrap() > observed);
    }

    #[test]
    fn wait_for_display_wake_times_out_without_change() {
        let handle = DeviceRuntimeKernelHandle::new(16, 1024);
        let observed = handle.display_wake_sequence().unwrap();

        let after = handle
            .wait_for_display_wake(observed, Duration::from_millis(5))
            .unwrap();

        assert_eq!(observed, after);
    }
}
