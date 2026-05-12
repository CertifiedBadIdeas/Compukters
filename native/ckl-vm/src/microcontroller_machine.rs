use crate::low_machine::{MachineMemory, MemoryFault};

pub struct MicrocontrollerMachine {
    memory: MachineMemory,
}

impl MicrocontrollerMachine {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        &self.memory
    }
}

#[cfg(test)]
mod tests {
    use crate::microcontroller_machine::MicrocontrollerMachine;

    #[test]
    fn microcontroller_machine_has_small_ram_and_no_process_model() {
        let machine = MicrocontrollerMachine::new(256).unwrap();

        assert_eq!(machine.memory().bytes().len(), 256);
    }
}
