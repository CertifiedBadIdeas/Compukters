use crate::low_bus::{MachineBus, MmioDevice, MmioDeviceId};
use crate::low_image::Image;
use crate::low_image_runner::{LowImageCpu, LowImageVm};
use crate::low_machine::{MachineMemory, MemoryBus, MemoryFault};

const GPIO_REGISTER_COUNT: usize = 16;

pub struct MicrocontrollerMachine {
    bus: MachineBus,
    gpio_device_id: MmioDeviceId,
    timer_device_id: MmioDeviceId,
}

impl MicrocontrollerMachine {
    pub const GPIO_BASE: u32 = 0x1000_0000;
    pub const TIMER_BASE: u32 = 0x1000_0100;

    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        let mut bus = MachineBus::new(memory_size)?;
        let gpio_device_id = bus.map_mmio(Self::GPIO_BASE, Box::new(GpioDevice::new()))?;
        let timer_device_id = bus.map_mmio(Self::TIMER_BASE, Box::new(TimerDevice::new()))?;
        Ok(Self {
            bus,
            gpio_device_id,
            timer_device_id,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        self.bus.memory()
    }

    pub fn create_firmware_cpu(
        &mut self,
        image: Image,
        slice_budget_nanos: u64,
    ) -> Result<LowImageCpu<'_>, String> {
        LowImageVm::create_cpu_with_bus(image, slice_budget_nanos, &mut self.bus)
    }

    pub fn gpio_register(&self, index: usize) -> Option<i32> {
        self.bus
            .device::<GpioDevice>(self.gpio_device_id)
            .and_then(|device| device.register(index))
    }

    pub fn set_timer_ticks(&mut self, ticks: i32) {
        if let Some(timer) = self.bus.device_mut::<TimerDevice>(self.timer_device_id) {
            timer.set_ticks(ticks);
        }
    }
}

struct GpioDevice {
    registers: [i32; GPIO_REGISTER_COUNT],
}

impl GpioDevice {
    fn new() -> Self {
        Self {
            registers: [0; GPIO_REGISTER_COUNT],
        }
    }

    fn register(&self, index: usize) -> Option<i32> {
        self.registers.get(index).copied()
    }

    fn register_index(offset: u32) -> Result<usize, MemoryFault> {
        if offset % 4 != 0 {
            return Err(MemoryFault::new(format!(
                "gpio register offset {offset} is not aligned",
            )));
        }
        let index = (offset / 4) as usize;
        if index >= GPIO_REGISTER_COUNT {
            return Err(MemoryFault::new(format!(
                "gpio register index {index} is outside {GPIO_REGISTER_COUNT} registers",
            )));
        }
        Ok(index)
    }
}

impl MmioDevice for GpioDevice {
    fn size(&self) -> u32 {
        (GPIO_REGISTER_COUNT as u32) * 4
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        Ok(self.registers[Self::register_index(offset)?])
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        let index = Self::register_index(offset)?;
        self.registers[index] = value;
        Ok(())
    }
}

struct TimerDevice {
    ticks: i32,
}

impl TimerDevice {
    fn new() -> Self {
        Self { ticks: 0 }
    }

    fn set_ticks(&mut self, ticks: i32) {
        self.ticks = ticks;
    }

    fn check_offset(offset: u32) -> Result<(), MemoryFault> {
        if offset == 0 {
            Ok(())
        } else {
            Err(MemoryFault::new(format!(
                "timer register offset {offset} is outside timer register 0",
            )))
        }
    }
}

impl MmioDevice for TimerDevice {
    fn size(&self) -> u32 {
        4
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        Self::check_offset(offset)?;
        Ok(self.ticks)
    }

    fn store_i32(&mut self, offset: u32, _value: i32) -> Result<(), MemoryFault> {
        Self::check_offset(offset)?;
        Err(MemoryFault::new(
            "timer register 0 is read-only".to_string(),
        ))
    }
}

impl MemoryBus for MicrocontrollerMachine {
    fn len(&self) -> usize {
        self.bus.len()
    }

    fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        self.bus.load_i32(address)
    }

    fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.bus.store_i32(address, value)
    }

    fn load_u8(&self, address: u32) -> Result<u8, MemoryFault> {
        self.bus.load_u8(address)
    }

    fn store_u8(&mut self, address: u32, value: u8) -> Result<(), MemoryFault> {
        self.bus.store_u8(address, value)
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

    #[test]
    fn microcontroller_firmware_can_read_timer_mmio_register() {
        let mut machine = MicrocontrollerMachine::new(256).unwrap();
        machine.set_timer_ticks(12_345);
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: MicrocontrollerMachine::TIMER_BASE,
                },
                Instruction::Load32 { dst: 1, addr: 0 },
                Instruction::ReturnI32 { src: 1 },
            ],
            2,
        );

        let mut cpu = machine.create_firmware_cpu(firmware, 128).unwrap();

        assert_eq!(
            cpu.run_until_signal().unwrap(),
            LowImageSignal::HaltI32(12_345),
        );
    }

    #[test]
    fn microcontroller_timer_mmio_register_is_read_only() {
        let mut machine = MicrocontrollerMachine::new(256).unwrap();
        let firmware = image(
            vec![
                Instruction::AddrConst {
                    dst: 0,
                    value: MicrocontrollerMachine::TIMER_BASE,
                },
                Instruction::I32Const { dst: 1, value: 99 },
                Instruction::Store32 { addr: 0, src: 1 },
                Instruction::ReturnUnit,
            ],
            2,
        );

        let mut cpu = machine.create_firmware_cpu(firmware, 128).unwrap();

        let error = cpu.run_until_signal().unwrap_err();

        assert_eq!(error, "timer register 0 is read-only");
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
