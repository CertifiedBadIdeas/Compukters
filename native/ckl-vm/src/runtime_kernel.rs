use std::collections::{BTreeMap, VecDeque};

use crate::display::DeviceDisplayRegistry;
use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueuedEvent {
    pub name: String,
    pub arguments: Vec<VmValue>,
}

pub struct DeviceRuntimeKernel {
    event_queue: VecDeque<QueuedEvent>,
    deferred_events: VecDeque<QueuedEvent>,
    ipc: IpcRegistry,
    pub displays: DeviceDisplayRegistry,
    max_event_queue_size: usize,
}

impl DeviceRuntimeKernel {
    pub fn new(max_event_queue_size: usize, max_buffered_bytes_per_channel: usize) -> Self {
        Self {
            event_queue: VecDeque::new(),
            deferred_events: VecDeque::new(),
            ipc: IpcRegistry::new(max_buffered_bytes_per_channel),
            displays: DeviceDisplayRegistry::new(),
            max_event_queue_size: max_event_queue_size.max(1),
        }
    }

    pub fn enqueue_event(
        &mut self,
        name: &str,
        arguments: Vec<VmValue>,
    ) -> bool {
        if self.event_queue.len() >= self.max_event_queue_size {
            let _ = self.event_queue.pop_front();
        }
        self.event_queue.push_back(QueuedEvent {
            name: name.to_string(),
            arguments,
        });
        true
    }

    pub fn open_ipc_channel(&mut self) -> Result<i32, String> {
        self.ipc.open()
    }

    pub fn attach_placeholder_payload_event(
        &mut self,
        name: &str,
        _payload: &[u8],
    ) -> bool {
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
        self.channels.insert(id, IpcChannel::new(self.max_buffered_bytes_per_channel));
        Ok(id)
    }
}

struct IpcChannel {
    #[allow(dead_code)]
    buffer: String,
    #[allow(dead_code)]
    max_buffered_bytes: usize,
}

impl IpcChannel {
    fn new(max_buffered_bytes: usize) -> Self {
        Self {
            buffer: String::new(),
            max_buffered_bytes,
        }
    }
}

#[allow(dead_code)]
fn _deferred_queue_len(kernel: &DeviceRuntimeKernel) -> usize {
    kernel.deferred_events.len()
}
