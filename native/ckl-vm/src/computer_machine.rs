use crate::low_bus::{MachineBus, MmioDevice, MmioDeviceId};
use crate::low_image::Image;
use crate::low_image_runner::{LowCpuContext, LowImageSignal, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryFault};

pub type CpuId = usize;

pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: MmioDeviceId,
    cpus: Vec<LowCpuContext>,
    boot_cpu: Option<CpuId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMemoryMap {
    regions: Vec<ComputerMemoryRegion>,
}

impl ComputerMemoryMap {
    pub fn region(&self, name: &str) -> Option<&ComputerMemoryRegion> {
        self.regions.iter().find(|region| region.name == name)
    }

    pub fn regions(&self) -> &[ComputerMemoryRegion] {
        &self.regions
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMemoryRegion {
    pub name: &'static str,
    pub base: u32,
    pub size: u32,
    pub readable: bool,
    pub writable: bool,
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
            cpus: Vec::new(),
            boot_cpu: None,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        self.bus.memory()
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        self.bus.memory_mut()
    }

    pub fn memory_map(&self) -> ComputerMemoryMap {
        ComputerMemoryMap {
            regions: vec![
                ComputerMemoryRegion {
                    name: "ram",
                    base: 0,
                    size: self.memory().len() as u32,
                    readable: true,
                    writable: true,
                },
                ComputerMemoryRegion {
                    name: "control",
                    base: Self::CONTROL_BASE,
                    size: ComputerControlDevice::SIZE,
                    readable: true,
                    writable: true,
                },
            ],
        }
    }

    pub fn spawn_cpu(
        &mut self,
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<CpuId, String> {
        let required_memory = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        if self.bus.memory().len() < required_memory {
            return Err(format!(
                "image requires {required_memory} bytes but machine memory has {} bytes",
                self.bus.memory().len(),
            ));
        }
        let cpu = LowImageVm::create_cpu_context(image, slice_budget_nanos)?;
        let cpu_id = self.cpus.len();
        self.cpus.push(cpu);
        Ok(cpu_id)
    }

    pub fn spawn_boot_cpu(
        &mut self,
        kernel_image: Image,
        slice_budget_nanos: u64,
    ) -> Result<CpuId, String> {
        if self.boot_cpu.is_some() {
            return Err("boot CPU is already spawned".to_string());
        }
        let cpu_id = self.spawn_cpu(kernel_image, slice_budget_nanos)?;
        self.boot_cpu = Some(cpu_id);
        Ok(cpu_id)
    }

    pub fn boot_cpu_id(&self) -> Option<CpuId> {
        self.boot_cpu
    }

    pub fn cpu_count(&self) -> usize {
        self.cpus.len()
    }

    pub fn run_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
        let cpu = self
            .cpus
            .get_mut(cpu_id)
            .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
        cpu.run_until_signal(&mut self.bus)
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
    const SIZE: u32 = 8;

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
        Self::SIZE
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

        let writer_cpu_id = machine.spawn_cpu(writer, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(writer_cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        let reader_cpu_id = machine.spawn_cpu(reader, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(reader_cpu_id).unwrap(),
            LowImageSignal::HaltI32(91),
        );
    }

    #[test]
    fn computer_machine_owns_boot_cpu_context() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let kernel = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::CONTROL_STATUS,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(machine.boot_cpu_id(), Some(cpu_id));
        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
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

        let writer_cpu_id = machine.spawn_cpu(writer, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(writer_cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(
            machine.bus.device::<LatchDevice>(device_id).unwrap().value,
            77
        );
        let reader_cpu_id = machine.spawn_cpu(reader, 128).unwrap();
        assert_eq!(
            machine.run_cpu_until_signal(reader_cpu_id).unwrap(),
            LowImageSignal::HaltI32(77),
        );
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

        let cpu_id = machine.spawn_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
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

        let cpu_id = machine.spawn_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltI32(0x55AA),
        );
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

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
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

        let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_eq!(machine.panic_code(), 404);
    }

    #[test]
    fn computer_memory_map_describes_ram_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let ram = map.region("ram").unwrap();

        assert_eq!(ram.base, 0);
        assert_eq!(ram.size, 1024);
        assert!(ram.readable);
        assert!(ram.writable);
    }

    #[test]
    fn computer_memory_map_describes_control_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let control = map.region("control").unwrap();

        assert_eq!(control.base, ComputerMachine::CONTROL_BASE);
        assert_eq!(control.size, 8);
        assert!(control.readable);
        assert!(control.writable);
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
