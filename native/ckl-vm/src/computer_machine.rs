use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
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

    pub fn create_cpu(
        &mut self,
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowImageCpu<'_>, String> {
        LowImageVm::create_cpu_with_memory(image, slice_budget_nanos, &mut self.memory)
    }
}

#[cfg(test)]
mod tests {
    use crate::computer_machine::ComputerMachine;
    use crate::low_image::{Function, Image, Instruction};
    use crate::low_image_runner::LowImageSignal;

    #[test]
    fn computer_machine_owns_shared_physical_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine.memory_mut().store_i32(128, 42).unwrap();

        assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
    }

    #[test]
    fn computer_machine_runs_cpu_contexts_against_shared_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let writer = image(
            vec![
                Instruction::AddrConst { dst: 0, value: 128 },
                Instruction::I32Const { dst: 1, value: 91 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );
        let reader = image(
            vec![
                Instruction::AddrConst { dst: 0, value: 128 },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::ReturnI32 { src: 1 },
            ],
            2,
        );

        {
            let mut cpu = machine.create_cpu(writer, 128).unwrap();
            assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        }
        {
            let mut cpu = machine.create_cpu(reader, 128).unwrap();
            assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltI32(91));
        }
    }

    fn image(instructions: Vec<Instruction>, register_count: usize) -> Image {
        Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count,
                parameters: Vec::new(),
                instructions,
            }],
        }
    }
}
