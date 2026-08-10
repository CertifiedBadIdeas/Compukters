use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::MemoryFault;
use std::cell::RefCell;
use std::collections::VecDeque;

pub(crate) struct DebugSerialDevice {
    bytes: Vec<u8>,
    limit: Option<usize>,
}

impl DebugSerialDevice {
    pub(crate) const SIZE: u32 = computer_abi::DEBUG_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            bytes: Vec::new(),
            limit: None,
        }
    }

    #[allow(dead_code)] // Used by the RV32 machine construction slice that follows this device slice.
    pub(crate) fn with_limit(limit: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(limit),
            limit: Some(limit),
        }
    }

    pub(crate) fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(crate) fn drain(&mut self) -> Vec<u8> {
        let replacement = self.limit.map_or_else(Vec::new, Vec::with_capacity);
        std::mem::replace(&mut self.bytes, replacement)
    }

    pub(crate) fn restore_bytes(&mut self, bytes: Vec<u8>) {
        debug_assert!(self.limit.is_none_or(|limit| bytes.len() <= limit));
        self.bytes = bytes;
    }

    #[cfg(test)]
    pub(crate) fn capacity(&self) -> usize {
        self.bytes.capacity()
    }

    fn push(&mut self, value: u8) -> Result<(), MemoryFault> {
        if self.limit.is_some_and(|limit| self.bytes.len() >= limit) {
            return Err(MemoryFault::new(format!(
                "computer debug serial output exceeds limit {}",
                self.limit.unwrap(),
            )));
        }
        self.bytes.push(value);
        Ok(())
    }
}

impl MmioDevice for DebugSerialDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        if offset == 0 {
            Ok(0)
        } else {
            Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )))
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset != 0 {
            return Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )));
        }
        self.push(value.to_le_bytes()[0])
    }

    fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
        if offset != 0 {
            return Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )));
        }
        self.push(value)
    }
}

pub(crate) struct SerialInputDevice {
    bytes: RefCell<VecDeque<u8>>,
}

impl SerialInputDevice {
    pub(crate) const SIZE: u32 = computer_abi::SERIAL_INPUT_SIZE;

    pub(crate) fn new() -> Self {
        Self {
            bytes: RefCell::new(VecDeque::new()),
        }
    }

    pub(crate) fn push_bytes(&mut self, bytes: &[u8]) {
        self.bytes.borrow_mut().extend(bytes.iter().copied());
    }

    pub(crate) fn len(&self) -> usize {
        self.bytes.borrow().len()
    }

    pub(crate) fn bytes(&self) -> Vec<u8> {
        self.bytes.borrow().iter().copied().collect()
    }

    pub(crate) fn restore_bytes(&mut self, bytes: Vec<u8>) {
        self.bytes = RefCell::new(VecDeque::from(bytes));
    }
}

impl MmioDevice for SerialInputDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(if self.bytes.borrow().is_empty() { 0 } else { 1 }),
            4 => Ok(self.bytes.borrow_mut().pop_front().unwrap_or(0).into()),
            _ => Err(MemoryFault::new(format!(
                "computer serial input offset {offset} is not mapped",
            ))),
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset == 4 {
            self.bytes.borrow_mut().push_back(value.to_le_bytes()[0]);
            return Ok(());
        }
        Err(MemoryFault::new(format!(
            "computer serial input offset {offset} is read-only",
        )))
    }

    fn load_u8(&self, offset: u32) -> Result<u8, MemoryFault> {
        match offset {
            0 => Ok(if self.bytes.borrow().is_empty() { 0 } else { 1 }),
            4 => Ok(self.bytes.borrow_mut().pop_front().unwrap_or(0)),
            _ => Err(MemoryFault::new(format!(
                "computer serial input offset {offset} is not mapped",
            ))),
        }
    }

    fn store_u8(&mut self, offset: u32, value: u8) -> Result<(), MemoryFault> {
        if offset == 4 {
            self.bytes.borrow_mut().push_back(value);
            return Ok(());
        }
        Err(MemoryFault::new(format!(
            "computer serial input offset {offset} is read-only",
        )))
    }
}
