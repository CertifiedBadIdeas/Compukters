use crate::computer::profile::ComputerMachineProfile;
use crate::rux16::{Rux16CpuSnapshot, Rux16CpuSnapshotState};

pub const COMPUTER_SNAPSHOT_V1_MAGIC: &[u8; 8] = b"RUXSNAP\0";
pub const COMPUTER_SNAPSHOT_V1_VERSION: u16 = 1;
pub const COMPUTER_SNAPSHOT_V1_HEADER_SIZE: usize = 32;
pub const COMPUTER_SNAPSHOT_V1_RUX16_CPU_KIND: u32 = 1;
pub const COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE: usize = 112;
const NO_BOOT_CPU: u32 = u32::MAX;
const RUX16_CPU_STATE_RUNNING: u32 = 1;
const RUX16_CPU_STATE_HALTED: u32 = 2;
const RUX16_CPU_STATE_TRAPPED: u32 = 3;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMachineSnapshotHeader {
    pub version: u16,
    pub header_size: u16,
    pub flags: u32,
    pub ram_size: u64,
    pub cpu_count: u32,
    pub boot_cpu_id: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerMachineSnapshot<'a> {
    pub header: ComputerMachineSnapshotHeader,
    pub ram: &'a [u8],
    pub cpus: Vec<ComputerCpuSnapshotRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ComputerCpuSnapshotRecord {
    Rux16 {
        cpu: Rux16CpuSnapshot,
        max_steps: u64,
    },
}

pub fn encode_snapshot_v1(
    ram: &[u8],
    boot_cpu_id: Option<usize>,
    cpus: &[ComputerCpuSnapshotRecord],
) -> Result<Vec<u8>, String> {
    let ram_size =
        u64::try_from(ram.len()).map_err(|_| "snapshot RAM size does not fit u64".to_string())?;
    let cpu_count =
        u32::try_from(cpus.len()).map_err(|_| "snapshot CPU count does not fit u32".to_string())?;
    let boot_cpu_id = match boot_cpu_id {
        Some(id) => {
            u32::try_from(id).map_err(|_| "snapshot boot CPU id does not fit u32".to_string())?
        }
        None => NO_BOOT_CPU,
    };
    let cpu_records_size = cpus
        .len()
        .checked_mul(COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE)
        .ok_or_else(|| "snapshot CPU record size overflows usize".to_string())?;
    let capacity = COMPUTER_SNAPSHOT_V1_HEADER_SIZE
        .checked_add(ram.len())
        .and_then(|size| size.checked_add(cpu_records_size))
        .ok_or_else(|| "snapshot size overflows usize".to_string())?;
    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(COMPUTER_SNAPSHOT_V1_MAGIC);
    write_u16(&mut bytes, COMPUTER_SNAPSHOT_V1_VERSION);
    write_u16(&mut bytes, COMPUTER_SNAPSHOT_V1_HEADER_SIZE as u16);
    write_u32(&mut bytes, 0);
    write_u64(&mut bytes, ram_size);
    write_u32(&mut bytes, cpu_count);
    write_u32(&mut bytes, boot_cpu_id);
    bytes.extend_from_slice(ram);
    for cpu in cpus {
        encode_cpu_record(&mut bytes, cpu)?;
    }
    Ok(bytes)
}

pub fn decode_snapshot_v1(bytes: &[u8]) -> Result<ComputerMachineSnapshot<'_>, String> {
    if bytes.len() < COMPUTER_SNAPSHOT_V1_HEADER_SIZE {
        return Err("ComputerMachine snapshot is smaller than the v1 header".to_string());
    }
    if &bytes[..8] != COMPUTER_SNAPSHOT_V1_MAGIC {
        return Err("invalid ComputerMachine snapshot magic".to_string());
    }
    let version = read_u16(bytes, 8)?;
    if version != COMPUTER_SNAPSHOT_V1_VERSION {
        return Err(format!(
            "unsupported ComputerMachine snapshot version {version}"
        ));
    }
    let header_size = read_u16(bytes, 10)?;
    if usize::from(header_size) != COMPUTER_SNAPSHOT_V1_HEADER_SIZE {
        return Err(format!(
            "unsupported ComputerMachine snapshot header size {header_size}"
        ));
    }
    let flags = read_u32(bytes, 12)?;
    if flags != 0 {
        return Err(format!(
            "unsupported ComputerMachine snapshot flags {flags:#010x}"
        ));
    }
    let ram_size = read_u64(bytes, 16)?;
    let cpu_count = read_u32(bytes, 24)?;
    let boot_cpu_id = match read_u32(bytes, 28)? {
        NO_BOOT_CPU => None,
        id => Some(id),
    };
    let ram_len = usize::try_from(ram_size)
        .map_err(|_| "ComputerMachine snapshot RAM size does not fit usize".to_string())?;
    let expected_len = COMPUTER_SNAPSHOT_V1_HEADER_SIZE
        .checked_add(ram_len)
        .and_then(|size| {
            usize::try_from(cpu_count).ok().and_then(|count| {
                count
                    .checked_mul(COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE)
                    .and_then(|cpu_bytes| size.checked_add(cpu_bytes))
            })
        })
        .ok_or_else(|| "ComputerMachine snapshot size overflows usize".to_string())?;
    if bytes.len() != expected_len {
        let payload_len = bytes.len().saturating_sub(COMPUTER_SNAPSHOT_V1_HEADER_SIZE);
        let expected_payload_len = expected_len - COMPUTER_SNAPSHOT_V1_HEADER_SIZE;
        return Err(format!(
            "ComputerMachine snapshot declares {expected_payload_len} payload bytes but file has {payload_len} payload bytes"
        ));
    }
    let ram_start = COMPUTER_SNAPSHOT_V1_HEADER_SIZE;
    let ram_end = ram_start + ram_len;
    let mut cpus = Vec::with_capacity(cpu_count as usize);
    let mut cpu_offset = ram_end;
    for index in 0..cpu_count {
        let cpu_bytes = &bytes[cpu_offset..cpu_offset + COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE];
        cpus.push(decode_cpu_record(cpu_bytes, index)?);
        cpu_offset += COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE;
    }
    Ok(ComputerMachineSnapshot {
        header: ComputerMachineSnapshotHeader {
            version,
            header_size,
            flags,
            ram_size,
            cpu_count,
            boot_cpu_id,
        },
        ram: &bytes[ram_start..ram_end],
        cpus,
    })
}

pub(crate) fn validate_snapshot_ram_matches_profile(
    profile: &ComputerMachineProfile,
    snapshot: &ComputerMachineSnapshot<'_>,
) -> Result<(), String> {
    if snapshot.ram.len() != profile.memory_size {
        return Err(format!(
            "ComputerMachine snapshot RAM size {} does not match profile memory size {}",
            snapshot.ram.len(),
            profile.memory_size
        ));
    }
    Ok(())
}

fn write_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn write_u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn encode_cpu_record(
    bytes: &mut Vec<u8>,
    record: &ComputerCpuSnapshotRecord,
) -> Result<(), String> {
    match record {
        ComputerCpuSnapshotRecord::Rux16 { cpu, max_steps } => {
            write_u32(bytes, COMPUTER_SNAPSHOT_V1_RUX16_CPU_KIND);
            write_u32(bytes, encode_rux16_state(cpu.state));
            write_u64(bytes, *max_steps);
            write_u32(bytes, cpu.pc);
            write_u32(bytes, cpu.trap_vector);
            write_u32(bytes, cpu.trap_cause);
            write_u32(bytes, cpu.trap_pc);
            write_u32(bytes, cpu.trap_value);
            write_u32(bytes, 0);
            for register in cpu.registers {
                write_u32(bytes, register);
            }
            write_u64(bytes, cpu.metrics_steps);
        }
    }
    Ok(())
}

fn decode_cpu_record(bytes: &[u8], index: u32) -> Result<ComputerCpuSnapshotRecord, String> {
    let kind = read_u32(bytes, 0)?;
    if kind != COMPUTER_SNAPSHOT_V1_RUX16_CPU_KIND {
        return Err(format!(
            "unsupported ComputerMachine snapshot CPU {index} kind {kind}"
        ));
    }
    let state = decode_rux16_state(read_u32(bytes, 4)?)?;
    let max_steps = read_u64(bytes, 8)?;
    let pc = read_u32(bytes, 16)?;
    let trap_vector = read_u32(bytes, 20)?;
    let trap_cause = read_u32(bytes, 24)?;
    let trap_pc = read_u32(bytes, 28)?;
    let trap_value = read_u32(bytes, 32)?;
    let reserved = read_u32(bytes, 36)?;
    if reserved != 0 {
        return Err(format!(
            "unsupported ComputerMachine snapshot CPU {index} reserved field {reserved:#010x}"
        ));
    }
    let mut registers = [0_u32; 16];
    for (register_index, register) in registers.iter_mut().enumerate() {
        *register = read_u32(bytes, 40 + register_index * 4)?;
    }
    let metrics_steps = read_u64(bytes, 104)?;
    Ok(ComputerCpuSnapshotRecord::Rux16 {
        cpu: Rux16CpuSnapshot {
            pc,
            registers,
            trap_vector,
            trap_cause,
            trap_pc,
            trap_value,
            state,
            metrics_steps,
        },
        max_steps,
    })
}

fn encode_rux16_state(state: Rux16CpuSnapshotState) -> u32 {
    match state {
        Rux16CpuSnapshotState::Running => RUX16_CPU_STATE_RUNNING,
        Rux16CpuSnapshotState::Halted => RUX16_CPU_STATE_HALTED,
        Rux16CpuSnapshotState::Trapped => RUX16_CPU_STATE_TRAPPED,
    }
}

fn decode_rux16_state(state: u32) -> Result<Rux16CpuSnapshotState, String> {
    match state {
        RUX16_CPU_STATE_RUNNING => Ok(Rux16CpuSnapshotState::Running),
        RUX16_CPU_STATE_HALTED => Ok(Rux16CpuSnapshotState::Halted),
        RUX16_CPU_STATE_TRAPPED => Ok(Rux16CpuSnapshotState::Trapped),
        _ => Err(format!(
            "unsupported ComputerMachine snapshot Rux16 CPU state {state}"
        )),
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let raw = bytes
        .get(offset..offset + 2)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u16::from_le_bytes(raw.try_into().unwrap()))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let raw = bytes
        .get(offset..offset + 4)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u32::from_le_bytes(raw.try_into().unwrap()))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, String> {
    let raw = bytes
        .get(offset..offset + 8)
        .ok_or_else(|| "ComputerMachine snapshot header is truncated".to_string())?;
    Ok(u64::from_le_bytes(raw.try_into().unwrap()))
}
