use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;
use std::cell::RefCell;
use std::collections::VecDeque;

const DEFAULT_CAPACITY: usize = 256;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KeyboardEvent {
    pub kind: u32,
    pub code: u32,
    pub modifiers: u32,
    pub flags: u32,
}

pub(crate) struct KeyboardDevice {
    events: RefCell<VecDeque<KeyboardEvent>>,
    capacity: usize,
    sequence: u64,
    dropped_count: u32,
}

impl KeyboardDevice {
    pub(crate) const SIZE: u32 = computer_abi::KEYBOARD0_SIZE;

    pub(crate) fn new() -> Self {
        Self::with_capacity(DEFAULT_CAPACITY)
    }

    #[cfg(test)]
    pub(crate) fn with_capacity_for_tests(capacity: usize) -> Self {
        Self::with_capacity(capacity)
    }

    fn with_capacity(capacity: usize) -> Self {
        Self {
            events: RefCell::new(VecDeque::new()),
            capacity,
            sequence: 0,
            dropped_count: 0,
        }
    }

    pub(crate) fn len(&self) -> usize {
        self.events.borrow().len()
    }

    pub(crate) fn dropped_count(&self) -> u32 {
        self.dropped_count
    }

    pub(crate) fn front_event(&self) -> Option<KeyboardEvent> {
        self.events.borrow().front().copied()
    }

    pub(crate) fn events(&self) -> Vec<KeyboardEvent> {
        self.events.borrow().iter().copied().collect()
    }

    pub(crate) fn sequence(&self) -> u64 {
        self.sequence
    }

    pub(crate) fn restore_snapshot(
        &mut self,
        events: Vec<KeyboardEvent>,
        sequence: u64,
        dropped_count: u32,
    ) -> Result<(), String> {
        if events.len() > self.capacity {
            return Err(format!(
                "keyboard0 snapshot has {} events but capacity is {}",
                events.len(),
                self.capacity
            ));
        }
        for event in &events {
            validate_event(*event)?;
        }
        self.events = RefCell::new(VecDeque::from(events));
        self.sequence = sequence;
        self.dropped_count = dropped_count;
        Ok(())
    }

    pub(crate) fn push_key_down(&mut self, code: u32, repeat: bool, modifiers: u32) {
        self.push_event(KeyboardEvent {
            kind: computer_abi::KEYBOARD0_EVENT_KEY_DOWN as u32,
            code,
            modifiers,
            flags: if repeat {
                computer_abi::KEYBOARD0_FLAG_REPEAT as u32
            } else {
                0
            },
        });
    }

    pub(crate) fn push_key_up(&mut self, code: u32, modifiers: u32) {
        self.push_event(KeyboardEvent {
            kind: computer_abi::KEYBOARD0_EVENT_KEY_UP as u32,
            code,
            modifiers,
            flags: 0,
        });
    }

    pub(crate) fn push_char(&mut self, byte: u8) {
        self.push_event(KeyboardEvent {
            kind: computer_abi::KEYBOARD0_EVENT_CHAR as u32,
            code: u32::from(byte),
            modifiers: 0,
            flags: 0,
        });
    }

    pub(crate) fn push_paste_byte(&mut self, byte: u8) {
        self.push_event(KeyboardEvent {
            kind: computer_abi::KEYBOARD0_EVENT_PASTE_BYTE as u32,
            code: u32::from(byte),
            modifiers: 0,
            flags: 0,
        });
    }

    fn push_event(&mut self, event: KeyboardEvent) {
        if self.events.borrow().len() >= self.capacity {
            self.dropped_count = self.dropped_count.saturating_add(1);
            return;
        }
        self.events.borrow_mut().push_back(event);
        self.sequence = self.sequence.saturating_add(1);
    }

    fn consume(&mut self) {
        self.events.borrow_mut().pop_front();
    }

    fn clear(&mut self) {
        self.events.borrow_mut().clear();
        self.sequence = self.sequence.saturating_add(1);
    }

    fn status(&self) -> i32 {
        if self.events.borrow().is_empty() {
            computer_abi::KEYBOARD0_STATUS_EMPTY
        } else if self.dropped_count > 0 {
            computer_abi::KEYBOARD0_STATUS_OVERFLOW
        } else {
            computer_abi::KEYBOARD0_STATUS_READY
        }
    }

    fn front_value(&self, selector: impl FnOnce(KeyboardEvent) -> u32) -> i32 {
        let value = self.front_event().map(selector).unwrap_or(0);
        i32::from_le_bytes(value.to_le_bytes())
    }
}

impl MmioDevice for KeyboardDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        let value = match offset {
            0x00 => computer_abi::KEYBOARD0_VERSION_VALUE,
            0x04 => self.len().min(i32::MAX as usize) as i32,
            0x08 => self.status(),
            0x0c => self.front_value(|event| event.kind),
            0x10 => self.front_value(|event| event.code),
            0x14 => self.front_value(|event| event.modifiers),
            0x18 => self.front_value(|event| event.flags),
            0x1c => i32::from_le_bytes((self.sequence as u32).to_le_bytes()),
            0x20 => i32::from_le_bytes(((self.sequence >> 32) as u32).to_le_bytes()),
            0x28 => i32::from_le_bytes(self.dropped_count.to_le_bytes()),
            _ => {
                return Err(MemoryFault::new(format!(
                    "keyboard0 offset {offset} is not mapped",
                )));
            }
        };
        Ok(value)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset != 0x24 {
            return Err(MemoryFault::new(format!(
                "keyboard0 offset {offset} is read-only",
            )));
        }
        match value {
            computer_abi::KEYBOARD0_COMMAND_NOP => Ok(()),
            computer_abi::KEYBOARD0_COMMAND_CONSUME => {
                self.consume();
                Ok(())
            }
            computer_abi::KEYBOARD0_COMMAND_CLEAR => {
                self.clear();
                Ok(())
            }
            _ => Err(MemoryFault::new(format!(
                "keyboard0 command {value} is invalid",
            ))),
        }
    }
}

pub(crate) fn validate_event(event: KeyboardEvent) -> Result<(), String> {
    match event.kind {
        kind if kind == computer_abi::KEYBOARD0_EVENT_KEY_DOWN as u32 => {
            if event.flags & !computer_abi::KEYBOARD0_FLAG_REPEAT as u32 != 0 {
                return Err(format!(
                    "keyboard0 key_down flags {:#010x} are invalid",
                    event.flags
                ));
            }
        }
        kind if kind == computer_abi::KEYBOARD0_EVENT_KEY_UP as u32 => {
            if event.flags != 0 {
                return Err(format!(
                    "keyboard0 key_up flags {:#010x} are invalid",
                    event.flags
                ));
            }
        }
        kind if kind == computer_abi::KEYBOARD0_EVENT_CHAR as u32
            || kind == computer_abi::KEYBOARD0_EVENT_PASTE_BYTE as u32 =>
        {
            if event.code > u32::from(u8::MAX) {
                return Err(format!(
                    "keyboard0 byte event code {:#010x} is invalid",
                    event.code
                ));
            }
            if event.modifiers != 0 || event.flags != 0 {
                return Err("keyboard0 byte event modifiers and flags must be zero".to_string());
            }
        }
        _ => return Err(format!("keyboard0 event kind {} is invalid", event.kind)),
    }
    Ok(())
}
