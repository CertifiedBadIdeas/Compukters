use crate::computer_abi;
use crate::low_bus::{MachineBus, MmioDevice, MmioDeviceId};
use crate::low_image::Image;
use crate::low_image_runner::{LowCpuContext, LowImageSignal, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryFault};

pub type CpuId = usize;

pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: MmioDeviceId,
    debug_device_id: MmioDeviceId,
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
    pub const CONTROL_BASE: u32 = computer_abi::CONTROL_BASE;
    pub const CONTROL_STATUS: u32 = computer_abi::CONTROL_STATUS;
    pub const CONTROL_PANIC_CODE: u32 = computer_abi::CONTROL_PANIC_CODE;
    pub const CONTROL_EXIT_CODE: u32 = computer_abi::CONTROL_EXIT_CODE;
    pub const CONTROL_SIZE: u32 = computer_abi::CONTROL_SIZE;
    pub const DEBUG_BASE: u32 = computer_abi::DEBUG_BASE;
    pub const DEBUG_WRITE: u32 = computer_abi::DEBUG_WRITE;
    pub const DEBUG_SIZE: u32 = computer_abi::DEBUG_SIZE;
    pub const STATUS_RESET: i32 = computer_abi::STATUS_RESET;
    pub const STATUS_BOOTING: i32 = computer_abi::STATUS_BOOTING;
    pub const STATUS_READY: i32 = computer_abi::STATUS_READY;
    pub const STATUS_HALTED: i32 = computer_abi::STATUS_HALTED;
    pub const STATUS_PANIC: i32 = computer_abi::STATUS_PANIC;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        let mut bus = MachineBus::new(memory_size)?;
        let control_device_id =
            bus.map_mmio(Self::CONTROL_BASE, Box::new(ComputerControlDevice::new()))?;
        let debug_device_id = bus.map_mmio(Self::DEBUG_BASE, Box::new(DebugSerialDevice::new()))?;
        Ok(Self {
            bus,
            control_device_id,
            debug_device_id,
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
                ComputerMemoryRegion {
                    name: "debug",
                    base: Self::DEBUG_BASE,
                    size: DebugSerialDevice::SIZE,
                    readable: true,
                    writable: true,
                },
            ],
        }
    }

    pub fn spawn_cpu(&mut self, image: Image, slice_budget_nanos: u64) -> Result<CpuId, String> {
        let required_memory = usize::try_from(image.memory_size)
            .map_err(|_| "memory size does not fit usize".to_string())?;
        if self.bus.memory().len() < required_memory {
            return Err(format!(
                "image requires {required_memory} bytes but machine memory has {} bytes",
                self.bus.memory().len(),
            ));
        }
        LowImageVm::load_image_sections_into_bus(&image, &mut self.bus)?;
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

    pub fn run_boot_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
        if self.boot_cpu != Some(cpu_id) {
            return Err(format!("CPU {cpu_id} is not the boot CPU"));
        }
        let signal = self.run_cpu_until_signal(cpu_id);
        match &signal {
            Ok(LowImageSignal::HaltUnit) => {
                self.set_halted_exit_code(0)?;
            }
            Ok(LowImageSignal::HaltI32(exit_code)) => {
                self.set_halted_exit_code(*exit_code)?;
            }
            Ok(LowImageSignal::HaltI64(exit_code)) => {
                self.set_halted_exit_code(
                    (*exit_code).clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32,
                )?;
            }
            Ok(LowImageSignal::HaltAddr(exit_code)) => {
                self.set_halted_exit_code(i32::from_ne_bytes(exit_code.to_ne_bytes()))?;
            }
            Ok(LowImageSignal::HaltBool(success)) => {
                self.set_halted_exit_code(if *success { 0 } else { 1 })?;
            }
            Err(message) => {
                self.set_panic_from_fault(message)?;
            }
            Ok(LowImageSignal::Pause) => {}
        }
        signal
    }

    pub fn control_status(&self) -> i32 {
        self.control_device().status
    }

    pub fn panic_code(&self) -> i32 {
        self.control_device().panic_code
    }

    pub fn exit_code(&self) -> i32 {
        self.control_device().exit_code
    }

    pub fn debug_output_bytes(&self) -> &[u8] {
        self.debug_device().bytes()
    }

    pub fn debug_output_string(&self) -> String {
        String::from_utf8_lossy(self.debug_output_bytes()).into_owned()
    }

    fn control_device(&self) -> &ComputerControlDevice {
        self.bus
            .device::<ComputerControlDevice>(self.control_device_id)
            .expect("computer control device must be mapped")
    }

    fn debug_device(&self) -> &DebugSerialDevice {
        self.bus
            .device::<DebugSerialDevice>(self.debug_device_id)
            .expect("computer debug serial device must be mapped")
    }

    fn control_device_mut(&mut self) -> &mut ComputerControlDevice {
        self.bus
            .device_mut::<ComputerControlDevice>(self.control_device_id)
            .expect("computer control device must be mapped")
    }

    fn set_halted_exit_code(&mut self, exit_code: i32) -> Result<(), String> {
        let control = self.control_device_mut();
        control.status = Self::STATUS_HALTED;
        control.exit_code = exit_code;
        Ok(())
    }

    fn set_panic_from_fault(&mut self, message: &str) -> Result<(), String> {
        let control = self.control_device_mut();
        control.status = Self::STATUS_PANIC;
        control.panic_code = stable_panic_code(message);
        Err(message.to_string())
    }
}

fn stable_panic_code(message: &str) -> i32 {
    message.bytes().fold(0_i32, |hash, byte| {
        hash.wrapping_mul(31).wrapping_add(i32::from(byte))
    })
}

struct ComputerControlDevice {
    status: i32,
    panic_code: i32,
    exit_code: i32,
}

impl ComputerControlDevice {
    const SIZE: u32 = computer_abi::CONTROL_SIZE;

    fn new() -> Self {
        Self {
            status: ComputerMachine::STATUS_RESET,
            panic_code: 0,
            exit_code: 0,
        }
    }

    fn register_for_offset(&mut self, offset: u32) -> Result<&mut i32, MemoryFault> {
        match offset {
            0 => Ok(&mut self.status),
            4 => Ok(&mut self.panic_code),
            8 => Ok(&mut self.exit_code),
            _ => Err(MemoryFault::new(format!(
                "computer control offset {offset} is not mapped",
            ))),
        }
    }

    fn value_for_offset(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(self.status),
            4 => Ok(self.panic_code),
            8 => Ok(self.exit_code),
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

struct DebugSerialDevice {
    bytes: Vec<u8>,
}

impl DebugSerialDevice {
    const SIZE: u32 = computer_abi::DEBUG_SIZE;

    fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    fn bytes(&self) -> &[u8] {
        &self.bytes
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
        self.bytes.push(value.to_le_bytes()[0]);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{ComputerControlDevice, DebugSerialDevice};
    use crate::computer_abi;
    use crate::computer_machine::ComputerMachine;
    use crate::low_bus::MmioDevice;
    use crate::low_image::{Function, Image, Instruction};
    use crate::low_image_runner::LowImageSignal;
    use crate::low_machine::MemoryFault;

    // Legacy CKL OS research fixtures. Keep these tests as reference material, but do not
    // treat the guest process table/scheduler path as the current bare-metal MVP direction.
    const OS_STATE_BASE: u32 = 0x0001_0000;
    const OS_MAGIC: i32 = 0x434B_4F53;
    const OS_CURRENT_PID: u32 = OS_STATE_BASE + 4;
    const OS_PROCESS_COUNT: u32 = OS_STATE_BASE + 8;
    const INITIAL_PROCESS_READY: i32 = 1;
    const PROCESS_TABLE_BASE: u32 = OS_STATE_BASE + 0x100;
    const PROCESS_ENTRY_SIZE: u32 = 16;
    const PROCESS_STATE_OFFSET: u32 = 0;
    const PROCESS_ENTRY_OFFSET: u32 = 4;
    const PROCESS_STACK_PTR_OFFSET: u32 = 8;
    const PROCESS_EXIT_CODE_OFFSET: u32 = 12;
    const PROCESS_RUNNABLE: i32 = 1;
    const PROCESS_RUNNING: i32 = 2;
    const PROCESS_EXITED: i32 = 3;
    const USER_PROCESS_FUNCTION_INDEX: i32 = 1;

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
    fn computer_machine_rejects_second_boot_cpu() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let first = image(vec![Instruction::ReturnUnit], 0);
        let second = image(vec![Instruction::ReturnUnit], 0);

        assert_eq!(machine.spawn_boot_cpu(first, 128).unwrap(), 0);

        let error = machine.spawn_boot_cpu(second, 128).unwrap_err();
        assert_eq!(error, "boot CPU is already spawned");
    }

    #[test]
    fn computer_machine_rejects_missing_cpu_id() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        let error = machine.run_cpu_until_signal(7).unwrap_err();

        assert_eq!(error, "CPU 7 is not present");
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
    fn computer_machine_constants_match_bare_metal_abi_v0() {
        assert_eq!(ComputerMachine::CONTROL_BASE, computer_abi::CONTROL_BASE);
        assert_eq!(
            ComputerMachine::CONTROL_STATUS,
            computer_abi::CONTROL_STATUS
        );
        assert_eq!(
            ComputerMachine::CONTROL_PANIC_CODE,
            computer_abi::CONTROL_PANIC_CODE,
        );
        assert_eq!(
            ComputerMachine::CONTROL_EXIT_CODE,
            computer_abi::CONTROL_EXIT_CODE,
        );
        assert_eq!(ComputerMachine::CONTROL_SIZE, computer_abi::CONTROL_SIZE);
        assert_eq!(ComputerMachine::DEBUG_BASE, computer_abi::DEBUG_BASE);
        assert_eq!(ComputerMachine::DEBUG_WRITE, computer_abi::DEBUG_WRITE);
        assert_eq!(ComputerMachine::DEBUG_SIZE, computer_abi::DEBUG_SIZE);
        assert_eq!(ComputerMachine::STATUS_RESET, computer_abi::STATUS_RESET);
        assert_eq!(
            ComputerMachine::STATUS_BOOTING,
            computer_abi::STATUS_BOOTING
        );
        assert_eq!(ComputerMachine::STATUS_READY, computer_abi::STATUS_READY);
        assert_eq!(ComputerMachine::STATUS_HALTED, computer_abi::STATUS_HALTED);
        assert_eq!(ComputerMachine::STATUS_PANIC, computer_abi::STATUS_PANIC);
    }

    #[test]
    fn computer_mmio_device_sizes_match_bare_metal_abi_v0() {
        let control = ComputerControlDevice::new();
        let debug = DebugSerialDevice::new();

        assert_eq!(control.size(), computer_abi::CONTROL_SIZE);
        assert_eq!(debug.size(), computer_abi::DEBUG_SIZE);
    }

    #[test]
    fn bare_metal_program_halt_sets_machine_halted_status_and_exit_code() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::I32Const { dst: 0, value: 7 },
                Instruction::ReturnI32 { src: 0 },
            ],
            1,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltI32(7),
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
        assert_eq!(machine.exit_code(), 7);
        assert_eq!(machine.panic_code(), 0);
    }

    #[test]
    fn bare_metal_program_writes_debug_serial_output() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: ComputerMachine::DEBUG_WRITE,
                },
                Instruction::I32Const {
                    dst: 1,
                    value: i32::from(b'H'),
                },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::I32Const {
                    dst: 2,
                    value: i32::from(b'I'),
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.debug_output_bytes(), b"HI");
        assert_eq!(machine.debug_output_string(), "HI");
    }

    #[test]
    fn bare_metal_firmware_marks_ready_writes_debug_and_halts() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: computer_abi::CONTROL_STATUS,
                },
                Instruction::AddrConst {
                    dst: 1,
                    value: computer_abi::DEBUG_WRITE,
                },
                Instruction::I32Const {
                    dst: 2,
                    value: computer_abi::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::I32Const {
                    dst: 3,
                    value: i32::from(b'O'),
                },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 4,
                    value: i32::from(b'K'),
                },
                Instruction::Store32 { addr: 1, src: 4 },
                Instruction::I32Const {
                    dst: 5,
                    value: computer_abi::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 5 },
                Instruction::ReturnUnit,
            ],
            6,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

        assert_eq!(
            machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.debug_output_string(), "OK");
        assert_eq!(machine.control_status(), computer_abi::STATUS_HALTED);
        assert_eq!(machine.exit_code(), 0);
    }

    #[test]
    fn bare_metal_program_fault_marks_machine_panicked() {
        let mut machine = ComputerMachine::new(1024).unwrap();
        let firmware = image(
            vec![
                Instruction::I32Const { dst: 0, value: 10 },
                Instruction::I32Const { dst: 1, value: 0 },
                Instruction::I32Div {
                    dst: 2,
                    lhs: 0,
                    rhs: 1,
                },
                Instruction::ReturnUnit,
            ],
            3,
        );

        let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();
        let error = machine.run_boot_cpu_until_signal(cpu_id).unwrap_err();

        assert_eq!(error, "division by zero");
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_ne!(machine.panic_code(), 0);
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
    fn legacy_ckl_os_research_boot_kernel_initializes_os_state_and_marks_machine_ready() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
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
                Instruction::AddrConst {
                    dst: 2,
                    value: OS_STATE_BASE,
                },
                Instruction::I32Const {
                    dst: 3,
                    value: OS_MAGIC,
                },
                Instruction::Store32 { addr: 2, src: 3 },
                Instruction::AddrConst {
                    dst: 4,
                    value: OS_STATE_BASE + 4,
                },
                Instruction::I32Const {
                    dst: 5,
                    value: INITIAL_PROCESS_READY,
                },
                Instruction::Store32 { addr: 4, src: 5 },
                Instruction::I32Const {
                    dst: 6,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 6 },
                Instruction::ReturnUnit,
            ],
            7,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
        assert_eq!(
            machine.memory().load_i32(OS_STATE_BASE + 4).unwrap(),
            INITIAL_PROCESS_READY,
        );
    }

    #[test]
    fn legacy_ckl_os_research_boot_kernel_initializes_guest_process_table() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
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
                Instruction::AddrConst {
                    dst: 2,
                    value: OS_STATE_BASE,
                },
                Instruction::I32Const {
                    dst: 3,
                    value: OS_MAGIC,
                },
                Instruction::Store32 { addr: 2, src: 3 },
                Instruction::AddrConst {
                    dst: 4,
                    value: OS_CURRENT_PID,
                },
                Instruction::I32Const { dst: 5, value: 0 },
                Instruction::Store32 { addr: 4, src: 5 },
                Instruction::AddrConst {
                    dst: 6,
                    value: OS_PROCESS_COUNT,
                },
                Instruction::I32Const { dst: 7, value: 2 },
                Instruction::Store32 { addr: 6, src: 7 },
                Instruction::AddrConst {
                    dst: 8,
                    value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 9,
                    value: PROCESS_RUNNING,
                },
                Instruction::Store32 { addr: 8, src: 9 },
                Instruction::AddrConst {
                    dst: 10,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 11,
                    value: 0x0008_0000,
                },
                Instruction::Store32 { addr: 10, src: 11 },
                Instruction::AddrConst {
                    dst: 12,
                    value: PROCESS_TABLE_BASE + PROCESS_STACK_PTR_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 13,
                    value: 0x0010_0000,
                },
                Instruction::Store32 { addr: 12, src: 13 },
                Instruction::AddrConst {
                    dst: 14,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 15,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::Store32 { addr: 14, src: 15 },
                Instruction::AddrConst {
                    dst: 16,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_ENTRY_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 17,
                    value: 0x0008_0100,
                },
                Instruction::Store32 { addr: 16, src: 17 },
                Instruction::AddrConst {
                    dst: 18,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STACK_PTR_OFFSET,
                },
                Instruction::AddrConst {
                    dst: 19,
                    value: 0x0010_1000,
                },
                Instruction::Store32 { addr: 18, src: 19 },
                Instruction::I32Const {
                    dst: 20,
                    value: ComputerMachine::STATUS_READY,
                },
                Instruction::Store32 { addr: 0, src: 20 },
                Instruction::ReturnUnit,
            ],
            21,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
        assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 0);
        assert_eq!(machine.memory().load_i32(OS_PROCESS_COUNT).unwrap(), 2);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNING,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNABLE,
        );
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
    fn boot_kernel_can_panic_through_control_mmio() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
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
                    value: ComputerMachine::STATUS_BOOTING,
                },
                Instruction::Store32 { addr: 0, src: 2 },
                Instruction::I32Const {
                    dst: 3,
                    value: 0x0BAD,
                },
                Instruction::Store32 { addr: 1, src: 3 },
                Instruction::I32Const {
                    dst: 4,
                    value: ComputerMachine::STATUS_PANIC,
                },
                Instruction::Store32 { addr: 0, src: 4 },
                Instruction::ReturnUnit,
            ],
            5,
        );

        let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
        assert_eq!(machine.panic_code(), 0x0BAD);
    }

    #[test]
    fn legacy_ckl_os_research_scheduler_fixture_rotates_running_process_state() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        machine.memory_mut().store_i32(OS_CURRENT_PID, 0).unwrap();
        machine
            .memory_mut()
            .store_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET, PROCESS_RUNNING)
            .unwrap();
        machine
            .memory_mut()
            .store_i32(
                PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                PROCESS_RUNNABLE,
            )
            .unwrap();
        let scheduler = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: OS_CURRENT_PID,
                },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::I32Const { dst: 2, value: 0 },
                Instruction::I32Eq {
                    dst: 3,
                    lhs: 1,
                    rhs: 2,
                },
                Instruction::JumpIfFalse {
                    cond: 3,
                    target: 18,
                },
                Instruction::AddrConst {
                    dst: 4,
                    value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
                },
                Instruction::Load32 { dst: 5, addr: 4 },
                Instruction::I32Const {
                    dst: 6,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::I32Eq {
                    dst: 7,
                    lhs: 5,
                    rhs: 6,
                },
                Instruction::JumpIfFalse {
                    cond: 7,
                    target: 18,
                },
                Instruction::AddrConst {
                    dst: 8,
                    value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                },
                Instruction::I32Const {
                    dst: 9,
                    value: PROCESS_RUNNABLE,
                },
                Instruction::Store32 { addr: 8, src: 9 },
                Instruction::I32Const {
                    dst: 10,
                    value: PROCESS_RUNNING,
                },
                Instruction::Store32 { addr: 4, src: 10 },
                Instruction::I32Const { dst: 11, value: 1 },
                Instruction::Store32 { addr: 0, src: 11 },
                Instruction::ReturnUnit,
                Instruction::ReturnUnit,
            ],
            12,
        );

        let cpu_id = machine.spawn_cpu(scheduler, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 1);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNABLE,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_RUNNING,
        );
    }

    #[test]
    fn legacy_ckl_os_research_kernel_launches_static_user_process_and_records_exit_code() {
        let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
        let kernel = Image {
            language_version: "rux-low-1".to_string(),
            memory_size: 0x0002_0000,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![
                Function {
                    name: "kernel".to_string(),
                    register_count: 18,
                    parameters: Vec::new(),
                    instructions: vec![
                        Instruction::AddrConst {
                            dst: 0,
                            value: ComputerMachine::CONTROL_STATUS,
                        },
                        Instruction::I32Const {
                            dst: 1,
                            value: ComputerMachine::STATUS_BOOTING,
                        },
                        Instruction::Store32 { addr: 0, src: 1 },
                        Instruction::AddrConst {
                            dst: 2,
                            value: OS_STATE_BASE,
                        },
                        Instruction::I32Const {
                            dst: 3,
                            value: OS_MAGIC,
                        },
                        Instruction::Store32 { addr: 2, src: 3 },
                        Instruction::AddrConst {
                            dst: 4,
                            value: OS_CURRENT_PID,
                        },
                        Instruction::I32Const { dst: 5, value: 0 },
                        Instruction::Store32 { addr: 4, src: 5 },
                        Instruction::AddrConst {
                            dst: 6,
                            value: OS_PROCESS_COUNT,
                        },
                        Instruction::I32Const { dst: 7, value: 1 },
                        Instruction::Store32 { addr: 6, src: 7 },
                        Instruction::AddrConst {
                            dst: 8,
                            value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
                        },
                        Instruction::I32Const {
                            dst: 9,
                            value: PROCESS_RUNNING,
                        },
                        Instruction::Store32 { addr: 8, src: 9 },
                        Instruction::AddrConst {
                            dst: 10,
                            value: PROCESS_TABLE_BASE + PROCESS_ENTRY_OFFSET,
                        },
                        Instruction::I32Const {
                            dst: 11,
                            value: USER_PROCESS_FUNCTION_INDEX,
                        },
                        Instruction::Store32 { addr: 10, src: 11 },
                        Instruction::Load32 { dst: 12, addr: 10 },
                        Instruction::I32Eq {
                            dst: 13,
                            lhs: 12,
                            rhs: 11,
                        },
                        Instruction::JumpIfFalse {
                            cond: 13,
                            target: 29,
                        },
                        Instruction::CallStatic {
                            return_register: Some(14),
                            function_index: USER_PROCESS_FUNCTION_INDEX as usize,
                            arguments: Vec::new(),
                        },
                        Instruction::AddrConst {
                            dst: 15,
                            value: PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET,
                        },
                        Instruction::Store32 { addr: 15, src: 14 },
                        Instruction::I32Const {
                            dst: 16,
                            value: PROCESS_EXITED,
                        },
                        Instruction::Store32 { addr: 8, src: 16 },
                        Instruction::I32Const {
                            dst: 17,
                            value: ComputerMachine::STATUS_READY,
                        },
                        Instruction::Store32 { addr: 0, src: 17 },
                        Instruction::ReturnUnit,
                        Instruction::I32Const {
                            dst: 17,
                            value: ComputerMachine::STATUS_PANIC,
                        },
                        Instruction::Store32 { addr: 0, src: 17 },
                        Instruction::ReturnUnit,
                    ],
                },
                Function {
                    name: "user_main".to_string(),
                    register_count: 1,
                    parameters: Vec::new(),
                    instructions: vec![
                        Instruction::I32Const { dst: 0, value: 42 },
                        Instruction::ReturnI32 { src: 0 },
                    ],
                },
            ],
        };

        let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

        assert_eq!(
            machine.run_cpu_until_signal(cpu_id).unwrap(),
            LowImageSignal::HaltUnit,
        );
        assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
                .unwrap(),
            PROCESS_EXITED,
        );
        assert_eq!(
            machine
                .memory()
                .load_i32(PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET)
                .unwrap(),
            42,
        );
    }

    #[test]
    fn computer_memory_map_describes_ram_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let ram = map.region("ram").unwrap();

        assert_eq!(ram.base, computer_abi::RAM_BASE);
        assert_eq!(ram.size, 1024);
        assert!(ram.readable);
        assert!(ram.writable);
    }

    #[test]
    fn computer_memory_map_describes_control_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let control = map.region("control").unwrap();

        assert_eq!(control.base, computer_abi::CONTROL_BASE);
        assert_eq!(control.size, computer_abi::CONTROL_SIZE);
        assert!(control.readable);
        assert!(control.writable);
    }

    #[test]
    fn computer_memory_map_describes_debug_serial_mmio_region() {
        let machine = ComputerMachine::new(1024).unwrap();
        let map = machine.memory_map();
        let debug = map.region("debug").unwrap();

        assert_eq!(debug.base, computer_abi::DEBUG_BASE);
        assert_eq!(debug.size, computer_abi::DEBUG_SIZE);
        assert!(debug.readable);
        assert!(debug.writable);
    }

    fn image(instructions: Vec<Instruction>, register_count: usize) -> Image {
        Image {
            language_version: "rux-low-1".to_string(),
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
