use crate::computer::{
    BootHandoffError, ComputerMachine, ComputerMachineProfile, ComputerTextDisplaySnapshot, CpuId,
};
use crate::rux16::Rux16Signal;
use crate::ruxe;
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

    pub fn restore_rux16_bios_flash_snapshot_with_storage0_path(
        bios_flash: &[u8],
        memory_size: usize,
        storage0_path: impl AsRef<Path>,
        snapshot: &[u8],
    ) -> Result<Self, String> {
        if bios_flash.is_empty() {
            return Err("Rux16 BIOS flash is empty".to_string());
        }
        let profile =
            ComputerMachineProfile::computer_v1_with_storage0_path(memory_size, storage0_path);
        let mut machine = ComputerMachine::restore_snapshot_v1(profile, snapshot)?;
        machine.map_rux16_bios_flash(bios_flash.to_vec())?;
        let boot_cpu = machine
            .boot_cpu_id()
            .ok_or_else(|| "Rux computer snapshot has no boot CPU".to_string())?;
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

    pub fn restore_rux16_bios_flash_snapshot_path_with_storage0_path(
        bios_flash_path: impl AsRef<Path>,
        memory_size: usize,
        storage0_path: impl AsRef<Path>,
        snapshot: &[u8],
    ) -> Result<Self, String> {
        let bios_flash_path = bios_flash_path.as_ref();
        let bios_flash = fs::read(bios_flash_path).map_err(|error| {
            format!(
                "Cannot read Rux16 BIOS flash at {}: {error}",
                bios_flash_path.display(),
            )
        })?;
        Self::restore_rux16_bios_flash_snapshot_with_storage0_path(
            &bios_flash,
            memory_size,
            storage0_path,
            snapshot,
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

    pub fn snapshot_v1(&self) -> Result<Vec<u8>, String> {
        self.machine.snapshot_v1()
    }

    pub fn write_guest_ram_bytes(&mut self, address: u32, bytes: &[u8]) -> Result<(), String> {
        self.machine.write_guest_ram_bytes(address, bytes)
    }

    pub fn read_guest_ram_bytes(&self, address: u32, byte_len: u32) -> Result<Vec<u8>, String> {
        self.machine.read_guest_ram_bytes(address, byte_len)
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

    pub fn exec_ruxe_program_from_bytes(
        &mut self,
        program: &[u8],
        max_steps: u64,
    ) -> Result<CpuId, String> {
        let executable = ruxe::decode_program_rux16_executable(program)?;
        self.machine
            .write_guest_ram_bytes(executable.load_addr, &executable.payload)?;
        self.machine
            .boot_handoff_rux16_from_ram(executable.entry_pc, 2, max_steps)
            .map_err(|error| error.to_string())
    }
}
