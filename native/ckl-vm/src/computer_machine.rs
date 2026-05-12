use crate::low_bus::MachineBus;
use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryFault};

pub struct ComputerMachine {
    bus: MachineBus,
}

impl ComputerMachine {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            bus: MachineBus::new(memory_size)?,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        self.bus.memory()
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        self.bus.memory_mut()
    }

    pub fn create_cpu(
        &mut self,
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowImageCpu<'_>, String> {
        LowImageVm::create_cpu_with_bus(image, slice_budget_nanos, &mut self.bus)
    }
}

#[cfg(test)]
mod tests {
    use crate::computer_machine::ComputerMachine;
    use crate::low_bus::MmioDevice;
    use crate::low_image::{Function, Image, Instruction};
    use crate::low_image_runner::LowImageSignal;
    use crate::low_machine::MemoryFault;

    struct LatchDevice {
        value: i32,
    }

    impl MmioDevice for LatchDevice {
        fn size(&self) -> u32 {
            4
        }

        fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
            assert_eq!(offset, 0);
            Ok(self.value)
        }

        fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
            assert_eq!(offset, 0);
            self.value = value;
            Ok(())
        }
    }

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

    #[test]
    fn computer_machine_runs_cpu_contexts_against_mmio_bus_devices() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let device_id = machine
            .bus
            .map_mmio(0x1000_0000, Box::new(LatchDevice { value: 0 }))
            .unwrap();
        let writer = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: 0x1000_0000,
                },
                Instruction::I32Const { dst: 1, value: 77 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );
        let reader = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: 0x1000_0000,
                },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::ReturnI32 { src: 1 },
            ],
            2,
        );

        {
            let mut cpu = machine.create_cpu(writer, 128).unwrap();
            assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        }
        assert_eq!(
            machine.bus.device::<LatchDevice>(device_id).unwrap().value,
            77
        );
        {
            let mut cpu = machine.create_cpu(reader, 128).unwrap();
            assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltI32(77));
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
