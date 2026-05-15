use crate::computer_machine::{ComputerMachine, CpuId};
use crate::low_image::decode_image;
use crate::low_image_runner::LowImageSignal;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuxComputerControl {
    pub status: i32,
    pub exit_code: i32,
    pub panic_code: i32,
}

pub struct RuxComputerHandle {
    machine: ComputerMachine,
    boot_cpu: CpuId,
}

impl RuxComputerHandle {
    pub fn create(
        image_bytes: &[u8],
        memory_size: usize,
        slice_budget_nanos: u64,
    ) -> Result<Self, String> {
        let image = decode_image(image_bytes).map_err(|error| error.to_string())?;
        let mut machine = ComputerMachine::new(memory_size).map_err(|error| error.to_string())?;
        let boot_cpu = machine.spawn_boot_cpu(image, slice_budget_nanos.max(1))?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        self.machine.run_boot_cpu_until_signal(self.boot_cpu)
    }

    pub fn control(&self) -> RuxComputerControl {
        RuxComputerControl {
            status: self.machine.control_status(),
            exit_code: self.machine.exit_code(),
            panic_code: self.machine.panic_code(),
        }
    }

    pub fn debug_output_bytes(&self) -> &[u8] {
        self.machine.debug_output_bytes()
    }

    pub fn drain_debug_output_bytes(&mut self) -> Vec<u8> {
        self.machine.drain_debug_output_bytes()
    }

    pub fn push_serial_input(&mut self, bytes: &[u8]) {
        self.machine.push_serial_input(bytes);
    }
}
