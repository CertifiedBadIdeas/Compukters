use crate::low_machine::{MachineMemory, MemoryFault};

pub struct ComputerMachine {
    memory: MachineMemory,
}

impl ComputerMachine {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        &self.memory
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        &mut self.memory
    }
}

#[cfg(test)]
mod tests {
    use crate::computer_machine::ComputerMachine;

    #[test]
    fn computer_machine_owns_shared_physical_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine.memory_mut().store_i32(128, 42).unwrap();

        assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
    }
}
