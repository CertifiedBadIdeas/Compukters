use crate::low_bus::{MachineBus, MmioDevice, MmioDeviceId};
use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryFault};

pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: MmioDeviceId,
}

impl ComputerMachine {
    pub const CONTROL_BASE: u32 = 0x1000_0000;
    pub const CONTROL_STATUS: u32 = Self::CONTROL_BASE;
    pub const CONTROL_PANIC_CODE: u32 = Self::CONTROL_BASE + 4;
    pub const STATUS_PANIC: i32 = -1;
    pub const STATUS_RESET: i32 = 0;
    pub const STATUS_BOOTING: i32 = 1;
    pub const STATUS_READY: i32 = 2;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        let mut bus = MachineBus::new(memory_size)?;
        let control_device_id =
            bus.map_mmio(Self::CONTROL_BASE, Box::new(ComputerControlDevice::new()))?;
        Ok(Self {
            bus,
            control_device_id,
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

    pub fn boot_cpu(
        &mut self,
        kernel_image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowImageCpu<'_>, String> {
        self.create_cpu(kernel_image, slice_budget_nanos)
    }

    pub fn control_status(&self) -> i32 {
        self.control_device().status
    }

    pub fn panic_code(&self) -> i32 {
        self.control_device().panic_code
    }

    fn control_device(&self) -> &ComputerControlDevice {
        self.bus
            .device::<ComputerControlDevice>(self.control_device_id)
            .expect("computer control device must be mapped")
    }
}

struct ComputerControlDevice {
    status: i32,
    panic_code: i32,
}

impl ComputerControlDevice {
    fn new() -> Self {
        Self {
            status: 0,
            panic_code: 0,
        }
    }

    fn register_for_offset(&mut self, offset: u32) -> Result<&mut i32, MemoryFault> {
        match offset {
            0 => Ok(&mut self.status),
            4 => Ok(&mut self.panic_code),
            _ => Err(MemoryFault::new(format!(
                "computer control offset {offset} is not mapped",
            ))),
        }
    }

    fn value_for_offset(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(self.status),
            4 => Ok(self.panic_code),
            _ => Err(MemoryFault::new(format!(
                "computer control offset {offset} is not mapped",
            ))),
        }
    }
}

impl MmioDevice for ComputerControlDevice {
    fn size(&self) -> u32 {
        8
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.value_for_offset(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        *self.register_for_offset(offset)? = value;
        Ok(())
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
            .map_mmio(0x1000_1000, Box::new(LatchDevice { value: 0 }))
            .unwrap();
        let writer = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: 0x1000_1000,
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
                    value: 0x1000_1000,
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

    #[test]
    fn computer_kernel_can_write_machine_control_status() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const { dst: 1, value: 2 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let mut cpu = machine.create_cpu(kernel, 128).unwrap();

        assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        drop(cpu);
        assert_eq!(machine.control_status(), 2);
    }

    #[test]
    fn computer_kernel_can_write_machine_panic_code() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_PANIC_CODE,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: 0x55AA,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::Load32 { dst: 2, addr: 0 },
                Instruction::ReturnI32 { src: 2 },
            ],
            3,
        );

        let mut cpu = machine.create_cpu(kernel, 128).unwrap();

        assert_eq!(
            cpu.run_until_signal().unwrap(),
            LowImageSignal::HaltI32(0x55AA),
        );
        drop(cpu);
        assert_eq!(machine.panic_code(), 0x55AA);
    }

    #[test]
    fn computer_starts_in_reset_status() {
        let machine = ComputerMachine::new(1024).unwrap();

        assert_eq!(machine.control_status(), ComputerMachine::STATUS_RESET);
        assert_eq!(machine.panic_code(), 0);
    }

    #[test]
    fn boot_cpu_runs_kernel_that_marks_machine_ready() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const {
                    dst: 2,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let mut cpu = machine.boot_cpu(kernel, 128).unwrap();

        assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        drop(cpu);
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    }

    #[test]
    fn boot_cpu_runs_kernel_that_marks_machine_panicked() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: ComputerMachine::CONTROL_PANIC_CODE,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: ComputerMachine::STATUS_PANIC,
                },
                Instruction::I32Const { dst: 3, value: 404 },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::ReturnUnit,
            ],
            4,
        );

        let mut cpu = machine.boot_cpu(kernel, 128).unwrap();

        assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
        drop(cpu);
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_eq!(machine.panic_code(), 404);
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
