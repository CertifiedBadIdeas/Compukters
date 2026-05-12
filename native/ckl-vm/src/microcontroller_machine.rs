use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
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

    pub fn create_firmware_cpu(
        &mut self,
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowImageCpu<'_>, String> {
        LowImageVm::create_cpu_with_memory(image, slice_budget_nanos, &mut self.memory)
    }
}

#[cfg(test)]
mod tests {
    use crate::low_image::{Function, Image, Instruction};
    use crate::low_image_runner::LowImageSignal;
    use crate::microcontroller_machine::MicrocontrollerMachine;

    #[test]
    fn microcontroller_machine_has_small_ram_and_no_process_model() {
        let machine = MicrocontrollerMachine::new(256).unwrap();

        assert_eq!(machine.memory().bytes().len(), 256);
    }

    #[test]
    fn microcontroller_machine_runs_single_firmware_cpu_against_its_ram() {
        let mut machine = MicrocontrollerMachine::new(256).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst { dst: 0, value: 16 },
                Instruction::I32Const { dst: 1, value: 123 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let mut cpu = machine.create_firmware_cpu(firmware, 128).unwrap();

        assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        drop(cpu);
        assert_eq!(machine.memory().load_i32(16).unwrap(), 123);
    }

    fn image(instructions: Vec<Instruction>, register_count: usize) -> Image {
        Image {
            language_version: "ckl-low-1".to_string(),
            memory_size: 256,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "firmware".to_string(),
                register_count,
                parameters: Vec::new(),
                instructions,
            }],
        }
    }
}
