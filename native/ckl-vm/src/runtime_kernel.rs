use std::collections::{BTreeMap, VecDeque};

use crate::display::DeviceDisplayRegistry;
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

pub struct DeviceRuntimeKernel {
    event_queue: VecDeque<QueuedEvent>,
    deferred_events: VecDeque<QueuedEvent>,
    captured_events: BTreeMap<i32, Vec<VmValue>>,
    ipc: IpcRegistry,
    pub displays: DeviceDisplayRegistry,
    pub filesystem: Option<DeviceFilesystem>,
    next_event_id: i32,
    wake_sequence: i64,
    max_event_queue_size: usize,
}

impl DeviceRuntimeKernel {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            event_queue: VecDeque::new(),
            deferred_events: VecDeque::new(),
            captured_events: BTreeMap::new(),
            ipc: IpcRegistry::new(max_buffered_bytes_per_channel),
            displays: DeviceDisplayRegistry::new(),
            filesystem: None,
            next_event_id: 1,
            wake_sequence: 0,
            max_event_queue_size: max_event_queue_size.max(1),
        }
    }

    pub fn attach_filesystem(&mut self, root_path: String, quota_bytes: i64) -> Result<(), String> {
        self.filesystem = Some(DeviceFilesystem::attach(root_path, quota_bytes)?);
        Ok(())
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
        self.ipc.close(channel)
    }

    pub fn attach_placeholder_payload_event(&mut self, name: &str, _payload: &[u8]) -> bool {
        self.enqueue_event(name, Vec::new())
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
        let remaining = channel.max_buffered_bytes.saturating_sub(channel.buffer.len());
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
