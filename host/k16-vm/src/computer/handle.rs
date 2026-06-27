use crate::computer::{
    BootHandoffError, ComputerMachine, ComputerMachineProfile, CpuId, K16ComputerStatsSnapshot,
};
use crate::display::DisplayFrameDelta;
use crate::k16::K16Signal;
use crate::k16e;
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct K16ComputerControl {
    pub status: i32,
    pub exit_code: i32,
    pub panic_code: i32,
}

pub struct K16ComputerHandle {
    machine: ComputerMachine,
    boot_cpu: CpuId,
}

impl K16ComputerHandle {
    pub fn create_k16_bios_flash(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
    ) -> Result<Self, String> {
        let (machine, boot_cpu) =
            ComputerMachine::from_k16_bios_flash(bios_flash, memory_size, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_k16_bios_flash_with_storage0_media(
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
            ComputerMachine::from_k16_bios_flash_with_profile(bios_flash, profile, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_k16_bios_flash_with_storage0_path(
        bios_flash: &[u8],
        memory_size: usize,
        max_steps: u64,
        storage0_path: impl AsRef<Path>,
    ) -> Result<Self, String> {
        let profile =
            ComputerMachineProfile::computer_v1_with_storage0_path(memory_size, storage0_path);
        let (machine, boot_cpu) =
            ComputerMachine::from_k16_bios_flash_with_profile(bios_flash, profile, max_steps)?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn restore_k16_bios_flash_snapshot_with_storage0_path(
        bios_flash: &[u8],
        memory_size: usize,
        storage0_path: impl AsRef<Path>,
        snapshot: &[u8],
    ) -> Result<Self, String> {
        if bios_flash.is_empty() {
            return Err("K16 BIOS flash is empty".to_string());
        }
        let profile =
            ComputerMachineProfile::computer_v1_with_storage0_path(memory_size, storage0_path);
        let mut machine = ComputerMachine::restore_snapshot_v1(profile, snapshot)?;
        machine.map_k16_bios_flash(bios_flash.to_vec())?;
        let boot_cpu = machine
            .boot_cpu_id()
            .ok_or_else(|| "K16 computer snapshot has no boot CPU".to_string())?;
        Ok(Self { machine, boot_cpu })
    }

    pub fn create_k16_bios_flash_path_with_storage0_path(
        bios_flash_path: impl AsRef<Path>,
        memory_size: usize,
        max_steps: u64,
        storage0_path: impl AsRef<Path>,
    ) -> Result<Self, String> {
        let bios_flash_path = bios_flash_path.as_ref();
        let bios_flash = fs::read(bios_flash_path).map_err(|error| {
            format!(
                "Cannot read K16 BIOS flash at {}: {error}",
                bios_flash_path.display(),
            )
        })?;
        Self::create_k16_bios_flash_with_storage0_path(
            &bios_flash,
            memory_size,
            max_steps,
            storage0_path,
        )
    }

    pub fn restore_k16_bios_flash_snapshot_path_with_storage0_path(
        bios_flash_path: impl AsRef<Path>,
        memory_size: usize,
        storage0_path: impl AsRef<Path>,
        snapshot: &[u8],
    ) -> Result<Self, String> {
        let bios_flash_path = bios_flash_path.as_ref();
        let bios_flash = fs::read(bios_flash_path).map_err(|error| {
            format!(
                "Cannot read K16 BIOS flash at {}: {error}",
                bios_flash_path.display(),
            )
        })?;
        Self::restore_k16_bios_flash_snapshot_with_storage0_path(
            &bios_flash,
            memory_size,
            storage0_path,
            snapshot,
        )
    }

    pub fn run_k16_until_signal(&mut self) -> Result<K16Signal, String> {
        self.machine.run_boot_k16_until_signal(self.boot_cpu)
    }

    pub fn advance_game_tick(&mut self) {
        self.machine.advance_game_tick();
    }

    pub fn control(&self) -> K16ComputerControl {
        K16ComputerControl {
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

    pub fn drain_gpu0_frames(&mut self) -> Vec<DisplayFrameDelta> {
        self.machine.drain_gpu0_frames()
    }

    pub fn push_serial_input(&mut self, bytes: &[u8]) {
        self.machine.push_serial_input(bytes);
    }

    pub fn push_keyboard_key_down(&mut self, code: u32, repeat: bool, modifiers: i32) {
        self.machine.push_keyboard_key_down(code, repeat, modifiers);
    }

    pub fn push_keyboard_key_up(&mut self, code: u32, modifiers: i32) {
        self.machine.push_keyboard_key_up(code, modifiers);
    }

    pub fn push_keyboard_char(&mut self, byte: u8) {
        self.machine.push_keyboard_char(byte);
    }

    pub fn push_keyboard_paste_byte(&mut self, byte: u8) {
        self.machine.push_keyboard_paste_byte(byte);
    }

    pub fn storage0_media_snapshot(&self) -> Option<Vec<u8>> {
        self.machine.storage0_media_bytes()
    }

    pub fn snapshot_v1(&self) -> Result<Vec<u8>, String> {
        self.machine.snapshot_v1()
    }

    pub fn stats_snapshot(&self) -> K16ComputerStatsSnapshot {
        self.machine.stats_snapshot()
    }

    pub fn write_guest_ram_bytes(&mut self, address: u32, bytes: &[u8]) -> Result<(), String> {
        self.machine.write_guest_ram_bytes(address, bytes)
    }

    pub fn read_guest_ram_bytes(&self, address: u32, byte_len: u32) -> Result<Vec<u8>, String> {
        self.machine.read_guest_ram_bytes(address, byte_len)
    }

    pub fn boot_handoff_k16_from_guest_ram(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
    ) -> Result<CpuId, BootHandoffError> {
        self.machine
            .boot_handoff_k16_from_ram(entry_pc, byte_len, max_steps)
    }

    pub fn boot_handoff_k16_from_guest_ram_with_stack(
        &mut self,
        entry_pc: u32,
        byte_len: u32,
        max_steps: u64,
        stack_top: u32,
    ) -> Result<CpuId, BootHandoffError> {
        self.machine
            .boot_handoff_k16_from_ram_with_stack(entry_pc, byte_len, max_steps, stack_top)
    }

    pub fn exec_k16e_program_from_bytes(
        &mut self,
        program: &[u8],
        max_steps: u64,
    ) -> Result<CpuId, String> {
        let executable = k16e::decode_program_k16_executable(program)?;
        self.machine
            .write_guest_ram_bytes(executable.load_addr, &executable.payload)?;
        let payload_size = u32::try_from(executable.payload.len())
            .map_err(|_| "K16E payload is too large".to_string())?;
        if executable.memory_size > payload_size {
            let zero_fill_addr = executable
                .load_addr
                .checked_add(payload_size)
                .ok_or_else(|| "K16E zero-fill range overflows".to_string())?;
            let zero_fill_len = executable.memory_size - payload_size;
            let zeros = vec![
                0;
                usize::try_from(zero_fill_len)
                    .map_err(|_| "K16E zero-fill range is too large".to_string())?
            ];
            self.machine.write_guest_ram_bytes(zero_fill_addr, &zeros)?;
        }
        self.machine
            .boot_handoff_k16_from_ram(executable.entry_pc, 2, max_steps)
            .map_err(|error| error.to_string())
    }
}
