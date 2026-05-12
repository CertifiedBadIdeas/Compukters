use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryBus, MemoryFault};

const GPIO_REGISTER_COUNT: usize = 16;

pub struct MicrocontrollerMachine {
    memory: MachineMemory,
    gpio_registers: [i32; GPIO_REGISTER_COUNT],
}

impl MicrocontrollerMachine {
    pub const GPIO_BASE: u32 = 0x1000_0000;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
            gpio_registers: [0; GPIO_REGISTER_COUNT],
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
        LowImageVm::create_cpu_with_bus(image, slice_budget_nanos, self)
    }

    pub fn gpio_register(&self, index: usize) -> Option<i32> {
        self.gpio_registers.get(index).copied()
    }

    fn gpio_index(address: u32) -> Option<usize> {
        let offset = address.checked_sub(Self::GPIO_BASE)?;
        if offset % 4 != 0 {
            return None;
        }
        let index = (offset / 4) as usize;
        (index < GPIO_REGISTER_COUNT).then_some(index)
    }
}

impl MemoryBus for MicrocontrollerMachine {
    fn len(&self) -> usize {
        self.memory.len()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        if let Some(index) = Self::gpio_index(address) {
            return Ok(self.gpio_registers[index]);
        }
        self.memory.load_i32(address)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        if let Some(index) = Self::gpio_index(address) {
            self.gpio_registers[index] = value;
            return Ok(());
        }
        self.memory.store_i32(address, value)
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

    #[test]
    fn microcontroller_firmware_can_use_gpio_memory_mapped_registers() {
        let mut machine = MicrocontrollerMachine::new(256).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: MicrocontrollerMachine::GPIO_BASE,
                },
                Instruction::I32Const { dst: 1, value: 1 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::Load32 { dst: 2, addr: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
            3,
        );

        let mut cpu = machine.create_firmware_cpu(firmware, 128).unwrap();

        assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltI32(1));
        drop(cpu);
        assert_eq!(machine.gpio_register(0), Some(1));
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
