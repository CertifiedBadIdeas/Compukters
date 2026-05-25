use crate::computer::{
    BootHandoffError, ComputerMachine, ComputerMachineProfile, ComputerTextDisplaySnapshot, CpuId,
};
use crate::rux16::Rux16Signal;
use std::fs;
use std::path::Path;

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxComputerTextDisplaySnapshot {
    pub columns: u32,
    pub rows: u32,
    pub cursor_x: u32,
    pub cursor_y: u32,
    pub sequence: u64,
    pub cells: Vec<u8>,
}

impl From<ComputerTextDisplaySnapshot> for RuxComputerTextDisplaySnapshot {
    fn from(snapshot: ComputerTextDisplaySnapshot) -> Self {
        Self {
            columns: snapshot.columns,
            rows: snapshot.rows,
            cursor_x: snapshot.cursor_x,
            cursor_y: snapshot.cursor_y,
            sequence: snapshot.sequence,
            cells: snapshot.cells,
        }
    }
}

impl RuxComputerHandle {
    pub fn create_rux16_bios_flash(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
    ) -> Result<Self, String> {
        let (machine, boot_cpu) =
            ComputerMachine::from_rux16_bios_flash(bios_flash, memory_size, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_rux16_bios_flash_with_storage0_media(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
        storage0_media: Vec<u8>,
    ) -> Result<Self, String> {
        let profile = ComputerMachineProfile::computer_v1_with_storage0_media(
            memory_size,
            storage0_media,
            false,
        );
        let (machine, boot_cpu) =
            ComputerMachine::from_rux16_bios_flash_with_profile(bios_flash, profile, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_rux16_bios_flash_with_storage0_path(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
        storage0_path: impl AsRef<Path>,
    ) -> Result<Self, String> {
        let profile =
            ComputerMachineProfile::computer_v1_with_storage0_path(memory_size, storage0_path);
        let (machine, boot_cpu) =
            ComputerMachine::from_rux16_bios_flash_with_profile(bios_flash, profile, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_rux16_bios_flash_path_with_storage0_path(
        bios_flash_path: impl AsRef<Path>,
        memory_size: usize,
        max_steps: u64,
        storage0_path: impl AsRef<Path>,
    ) -> Result<Self, String> {
        let bios_flash_path = bios_flash_path.as_ref();
        let bios_flash = fs::read(bios_flash_path).map_err(|error| {
            format!(
                "Cannot read Rux16 BIOS flash at {}: {error}",
                bios_flash_path.display(),
            )
        })?;
        Self::create_rux16_bios_flash_with_storage0_path(
            &bios_flash,
            memory_size,
            max_steps,
            storage0_path,
        )
    }

    pub fn run_rux16_until_signal(&mut self) -> Result<Rux16Signal, String> {
        self.machine.run_boot_rux16_until_signal(self.boot_cpu)
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

    pub fn display0_snapshot(&self) -> Option<RuxComputerTextDisplaySnapshot> {
        self.machine.display0_snapshot().map(Into::into)
    }

    pub fn push_serial_input(&mut self, bytes: &[u8]) {
        self.machine.push_serial_input(bytes);
    }

    pub fn storage0_media_snapshot(&self) -> Option<Vec<u8>> {
        self.machine.storage0_media_bytes()
    }

    pub fn write_guest_ram_bytes(&mut self, address: u32, bytes: &[u8]) -> Result<(), String> {
        self.machine.write_guest_ram_bytes(address, bytes)
    }

    pub fn boot_handoff_rux16_from_guest_ram(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
    ) -> Result<CpuId, BootHandoffError> {
        self.machine
            .boot_handoff_rux16_from_ram(entry_pc, byte_len, max_steps)
    }
}
