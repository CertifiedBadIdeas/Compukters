/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use crate::k16::{K16CachedDecoder, K16Cpu, K16InstructionProfile, K16Signal};
use crate::low_bus::{
    MachineBus, MachineBusStatsSnapshot, MachineBusTrafficSnapshot, MmioDevice,
    MmioDeviceTrafficSnapshot,
};
use crate::low_machine::MemoryFault;
use std::hint::black_box;

const MEMORY_SIZE: usize = 1024;
const DATA_ADDR: u32 = 512;
const MMIO_ADDR: u32 = 0x1000_0000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VmBenchmarkWorkload {
    ComputeLoop,
    MemoryLoop,
    MmioLoop,
    BranchMix,
    CallLoop,
}

impl VmBenchmarkWorkload {
    pub fn name(self) -> &'static str {
        match self {
            Self::ComputeLoop => "compute-loop",
            Self::MemoryLoop => "memory-loop",
            Self::MmioLoop => "mmio-loop",
            Self::BranchMix => "branch-mix",
            Self::CallLoop => "call-loop",
        }
    }

    pub fn all() -> &'static [Self] {
        &[
            Self::ComputeLoop,
            Self::MemoryLoop,
            Self::MmioLoop,
            Self::BranchMix,
            Self::CallLoop,
        ]
    }
}

impl std::str::FromStr for VmBenchmarkWorkload {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "compute-loop" => Ok(Self::ComputeLoop),
            "memory-loop" => Ok(Self::MemoryLoop),
            "mmio-loop" => Ok(Self::MmioLoop),
            "branch-mix" => Ok(Self::BranchMix),
            "call-loop" => Ok(Self::CallLoop),
            _ => Err(format!("unknown VM benchmark workload: {value}")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VmBenchmarkSample {
    pub workload: VmBenchmarkWorkload,
    pub vm: &'static str,
    pub iterations: u32,
    pub checksum: u32,
    pub best_nanos: u128,
}

impl VmBenchmarkSample {
    pub fn nanos_per_iteration(self) -> f64 {
        if self.iterations == 0 {
            0.0
        } else {
            self.best_nanos as f64 / f64::from(self.iterations)
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VmStatsReportSample {
    pub workload: VmBenchmarkWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub cpu_steps: u64,
    pub bus: MachineBusStatsSnapshot,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VmInstructionProfileReportSample {
    pub workload: VmBenchmarkWorkload,
    pub iterations: u32,
    pub checksum: u32,
    pub cpu_steps: u64,
    pub bus: MachineBusStatsSnapshot,
    pub profile: K16InstructionProfile,
}

impl VmInstructionProfileReportSample {
    pub fn data_ram_loads(&self) -> u64 {
        self.bus.ram.loads.saturating_sub(self.profile.fetch_words)
    }

    pub fn data_ram_bytes_read(&self) -> u64 {
        self.bus
            .ram
            .bytes_read
            .saturating_sub(self.profile.fetch_bytes)
    }

    pub fn data_ram_stores(&self) -> u64 {
        self.bus.ram.stores
    }
}

pub fn benchmark_output_header() -> String {
    format_benchmark_columns(
        "workload",
        "vm",
        "iterations",
        "checksum",
        "best_nanos",
        "nanos/iter",
        "vs_native",
        "native_pct",
    )
}

pub fn vm_stats_report_header() -> String {
    format_vm_stats_report_columns(
        "workload",
        "iterations",
        "checksum",
        "cpu_steps",
        "ram_loads",
        "ram_stores",
        "ram_bytes_read",
        "ram_bytes_written",
        "mmio_loads",
        "mmio_stores",
        "mmio_bytes_read",
        "mmio_bytes_written",
        "mmio_devices",
    )
}

pub fn instruction_profile_report_header() -> String {
    format_instruction_profile_report_columns(
        "workload",
        "iterations",
        "checksum",
        "cpu_steps",
        "fetch_decode_ns",
        "execute_ns",
        "fetch_words",
        "fetch_bytes",
        "immediate_ops",
        "alu_ops",
        "load_store_ops",
        "branch_ops",
        "control_ops",
        "system_ops",
        "data_ram_loads",
        "data_ram_stores",
        "data_ram_bytes_read",
        "ram_loads",
        "ram_stores",
        "mmio_loads",
        "mmio_stores",
    )
}

pub fn format_vm_stats_report_sample(sample: &VmStatsReportSample) -> String {
    format_vm_stats_report_columns(
        sample.workload.name(),
        &sample.iterations.to_string(),
        &sample.checksum.to_string(),
        &sample.cpu_steps.to_string(),
        &sample.bus.ram.loads.to_string(),
        &sample.bus.ram.stores.to_string(),
        &sample.bus.ram.bytes_read.to_string(),
        &sample.bus.ram.bytes_written.to_string(),
        &sample.bus.mmio.loads.to_string(),
        &sample.bus.mmio.stores.to_string(),
        &sample.bus.mmio.bytes_read.to_string(),
        &sample.bus.mmio.bytes_written.to_string(),
        &sample.bus.mmio_devices.len().to_string(),
    )
}

pub fn format_instruction_profile_report_sample(
    sample: &VmInstructionProfileReportSample,
) -> String {
    format_instruction_profile_report_columns(
        sample.workload.name(),
        &sample.iterations.to_string(),
        &sample.checksum.to_string(),
        &sample.cpu_steps.to_string(),
        &sample.profile.fetch_decode_nanos.to_string(),
        &sample.profile.execute_nanos.to_string(),
        &sample.profile.fetch_words.to_string(),
        &sample.profile.fetch_bytes.to_string(),
        &sample.profile.families.immediate.to_string(),
        &sample.profile.families.alu.to_string(),
        &sample.profile.families.load_store.to_string(),
        &sample.profile.families.branch.to_string(),
        &sample.profile.families.control.to_string(),
        &sample.profile.families.system.to_string(),
        &sample.data_ram_loads().to_string(),
        &sample.data_ram_stores().to_string(),
        &sample.data_ram_bytes_read().to_string(),
        &sample.bus.ram.loads.to_string(),
        &sample.bus.ram.stores.to_string(),
        &sample.bus.mmio.loads.to_string(),
        &sample.bus.mmio.stores.to_string(),
    )
}

pub fn format_benchmark_sample(sample: &VmBenchmarkSample, native_best_nanos: u128) -> String {
    let native_ratio = if native_best_nanos == 0 {
        0.0
    } else {
        sample.best_nanos as f64 / native_best_nanos as f64
    };
    let ratio = format!("{native_ratio:.3}x");
    let percent = format!("{:.1}%", native_ratio * 100.0);
    format_benchmark_columns(
        sample.workload.name(),
        sample.vm,
        &sample.iterations.to_string(),
        &sample.checksum.to_string(),
        &sample.best_nanos.to_string(),
        &format!("{:.3}", sample.nanos_per_iteration()),
        &ratio,
        &percent,
    )
}

fn format_benchmark_columns(
    workload: &str,
    vm: &str,
    iterations: &str,
    checksum: &str,
    best_nanos: &str,
    nanos_per_iteration: &str,
    native_ratio: &str,
    native_percent: &str,
) -> String {
    format!(
        "{workload:<14} {vm:<12} {iterations:>10} {checksum:>10} {best_nanos:>12} {nanos_per_iteration:>12} {native_ratio:>10} {native_percent:>11}",
    )
}

fn format_vm_stats_report_columns(
    workload: &str,
    iterations: &str,
    checksum: &str,
    cpu_steps: &str,
    ram_loads: &str,
    ram_stores: &str,
    ram_bytes_read: &str,
    ram_bytes_written: &str,
    mmio_loads: &str,
    mmio_stores: &str,
    mmio_bytes_read: &str,
    mmio_bytes_written: &str,
    mmio_devices: &str,
) -> String {
    format!(
        "{workload:<14} {iterations:>10} {checksum:>10} {cpu_steps:>10} {ram_loads:>10} {ram_stores:>10} {ram_bytes_read:>14} {ram_bytes_written:>17} {mmio_loads:>11} {mmio_stores:>11} {mmio_bytes_read:>15} {mmio_bytes_written:>18} {mmio_devices:>12}",
    )
}

fn format_instruction_profile_report_columns(
    workload: &str,
    iterations: &str,
    checksum: &str,
    cpu_steps: &str,
    fetch_decode_nanos: &str,
    execute_nanos: &str,
    fetch_words: &str,
    fetch_bytes: &str,
    immediate_ops: &str,
    alu_ops: &str,
    load_store_ops: &str,
    branch_ops: &str,
    control_ops: &str,
    system_ops: &str,
    data_ram_loads: &str,
    data_ram_stores: &str,
    data_ram_bytes_read: &str,
    ram_loads: &str,
    ram_stores: &str,
    mmio_loads: &str,
    mmio_stores: &str,
) -> String {
    format!(
        "{workload:<14} {iterations:>10} {checksum:>10} {cpu_steps:>10} {fetch_decode_nanos:>15} {execute_nanos:>12} {fetch_words:>11} {fetch_bytes:>11} {immediate_ops:>13} {alu_ops:>8} {load_store_ops:>14} {branch_ops:>10} {control_ops:>11} {system_ops:>10} {data_ram_loads:>14} {data_ram_stores:>15} {data_ram_bytes_read:>19} {ram_loads:>10} {ram_stores:>10} {mmio_loads:>11} {mmio_stores:>11}",
    )
}

pub fn run_k16_workload(workload: VmBenchmarkWorkload, iterations: u32) -> Result<u32, String> {
    Ok(run_k16_workload_stats(workload, iterations)?.checksum)
}

pub fn run_k16_workload_cached_decode(
    workload: VmBenchmarkWorkload,
    iterations: u32,
) -> Result<u32, String> {
    let mut bus = MachineBus::new(MEMORY_SIZE).map_err(|error| error.to_string())?;
    if workload == VmBenchmarkWorkload::MmioLoop {
        bus.map_mmio(MMIO_ADDR, Box::new(BenchmarkRegisterDevice { value: 0 }))
            .map_err(|error| error.to_string())?;
    }
    let (words, result_register) = k16_workload(workload, iterations);
    write_words(&mut bus, 0, &words)?;
    let mut cpu = K16Cpu::new(0);
    let mut decoder = K16CachedDecoder::new();
    match cpu
        .run_until_signal_with_decoder(&mut bus, &mut decoder, k16_max_steps(workload, iterations))
        .map_err(|error| error.to_string())?
    {
        K16Signal::Halt => Ok(cpu.register(result_register)),
        signal => Err(format!("unexpected K16 signal: {signal:?}")),
    }
}

pub fn run_k16_workload_stats(
    workload: VmBenchmarkWorkload,
    iterations: u32,
) -> Result<VmStatsReportSample, String> {
    let mut bus = MachineBus::new(MEMORY_SIZE).map_err(|error| error.to_string())?;
    if workload == VmBenchmarkWorkload::MmioLoop {
        bus.map_mmio(MMIO_ADDR, Box::new(BenchmarkRegisterDevice { value: 0 }))
            .map_err(|error| error.to_string())?;
    }
    let (words, result_register) = k16_workload(workload, iterations);
    write_words(&mut bus, 0, &words)?;
    let mut cpu = K16Cpu::new(0);
    match cpu
        .run_until_signal(&mut bus, k16_max_steps(workload, iterations))
        .map_err(|error| error.to_string())?
    {
        K16Signal::Halt => Ok(VmStatsReportSample {
            workload,
            iterations,
            checksum: cpu.register(result_register),
            cpu_steps: cpu.snapshot().metrics_steps,
            bus: bus.stats_snapshot(),
        }),
        signal => Err(format!("unexpected K16 signal: {signal:?}")),
    }
}

pub fn run_k16_workload_instruction_profile(
    workload: VmBenchmarkWorkload,
    iterations: u32,
) -> Result<VmInstructionProfileReportSample, String> {
    let mut bus = MachineBus::new(MEMORY_SIZE).map_err(|error| error.to_string())?;
    if workload == VmBenchmarkWorkload::MmioLoop {
        bus.map_mmio(MMIO_ADDR, Box::new(BenchmarkRegisterDevice { value: 0 }))
            .map_err(|error| error.to_string())?;
    }
    let (words, result_register) = k16_workload(workload, iterations);
    write_words(&mut bus, 0, &words)?;
    let bus_stats_before_run = bus.stats_snapshot();
    let mut cpu = K16Cpu::new(0);
    let mut profile = K16InstructionProfile::default();
    match cpu
        .run_until_signal_with_instruction_profile(
            &mut bus,
            k16_max_steps(workload, iterations),
            &mut profile,
        )
        .map_err(|error| error.to_string())?
    {
        K16Signal::Halt => Ok(VmInstructionProfileReportSample {
            workload,
            iterations,
            checksum: cpu.register(result_register),
            cpu_steps: cpu.snapshot().metrics_steps,
            bus: subtract_bus_stats(&bus.stats_snapshot(), &bus_stats_before_run),
            profile,
        }),
        signal => Err(format!("unexpected K16 signal: {signal:?}")),
    }
}

fn subtract_bus_stats(
    after: &MachineBusStatsSnapshot,
    before: &MachineBusStatsSnapshot,
) -> MachineBusStatsSnapshot {
    MachineBusStatsSnapshot {
        ram: subtract_bus_traffic(after.ram, before.ram),
        mmio: subtract_bus_traffic(after.mmio, before.mmio),
        mmio_devices: after
            .mmio_devices
            .iter()
            .map(|after_device| {
                let before_traffic = before
                    .mmio_devices
                    .iter()
                    .find(|before_device| before_device.device_id == after_device.device_id)
                    .map(|before_device| before_device.traffic)
                    .unwrap_or_default();
                MmioDeviceTrafficSnapshot {
                    device_id: after_device.device_id,
                    base: after_device.base,
                    size: after_device.size,
                    traffic: subtract_bus_traffic(after_device.traffic, before_traffic),
                }
            })
            .collect(),
    }
}

fn subtract_bus_traffic(
    after: MachineBusTrafficSnapshot,
    before: MachineBusTrafficSnapshot,
) -> MachineBusTrafficSnapshot {
    MachineBusTrafficSnapshot {
        loads: after.loads.saturating_sub(before.loads),
        stores: after.stores.saturating_sub(before.stores),
        bytes_read: after.bytes_read.saturating_sub(before.bytes_read),
        bytes_written: after.bytes_written.saturating_sub(before.bytes_written),
    }
}

pub fn run_native_rust_workload(
    workload: VmBenchmarkWorkload,
    iterations: u32,
) -> Result<u32, String> {
    Ok(match workload {
        VmBenchmarkWorkload::ComputeLoop => native_compute_loop(iterations),
        VmBenchmarkWorkload::MemoryLoop => native_memory_loop(iterations),
        VmBenchmarkWorkload::MmioLoop => native_mmio_loop(iterations),
        VmBenchmarkWorkload::BranchMix => native_branch_mix(iterations),
        VmBenchmarkWorkload::CallLoop => native_call_loop(iterations),
    })
}

fn k16_workload(workload: VmBenchmarkWorkload, iterations: u32) -> (Vec<u16>, usize) {
    match workload {
        VmBenchmarkWorkload::ComputeLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 1), halt()]);
            words.extend(add(1, 1, 2));
            words.push(branch_if_nonzero(2, -7));
            (words, 1)
        }
        VmBenchmarkWorkload::MemoryLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const32(4),
                low16(DATA_ADDR),
                high16(DATA_ADDR),
                const32(6),
                low16(22),
                high16(22),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 2), load32(5, 4), halt(), load32(5, 4)]);
            words.extend(add(5, 5, 2));
            words.push(store32(4, 5));
            words.extend(add(1, 1, 2));
            words.push(jump(6));
            (words, 5)
        }
        VmBenchmarkWorkload::MmioLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const32(4),
                low16(MMIO_ADDR),
                high16(MMIO_ADDR),
                const32(6),
                low16(22),
                high16(22),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 2), load32(5, 4), halt()]);
            words.extend(add(1, 1, 2));
            words.extend([store32(4, 1), load32(5, 4), jump(6)]);
            (words, 5)
        }
        VmBenchmarkWorkload::BranchMix => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const4(4, 0),
                const32(5),
                low16(20),
                high16(20),
                const4(6, 3),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 1), halt()]);
            words.extend(and(7, 1, 2));
            words.push(branch_if_zero(7, 3));
            words.extend(add(4, 4, 6));
            words.push(branch_if_nonzero(2, 2));
            words.extend(add(4, 4, 2));
            words.extend(add(1, 1, 2));
            words.push(jump(5));
            (words, 4)
        }
        VmBenchmarkWorkload::CallLoop => {
            let mut words = vec![
                const32(0),
                low16(iterations),
                high16(iterations),
                const4(1, 0),
                const4(2, 1),
                const4(4, 0),
                const32(15),
                low16(MEMORY_SIZE as u32),
                high16(MEMORY_SIZE as u32),
                const32(5),
                low16(30),
                high16(30),
                const32(6),
                low16(46),
                high16(46),
            ];
            words.extend(eq(3, 1, 0));
            words.extend([branch_if_zero(3, 1), halt(), call(6)]);
            words.extend(add(1, 1, 2));
            words.push(jump(5));
            words.extend(add(4, 4, 2));
            words.push(ret());
            (words, 4)
        }
    }
}

fn k16_max_steps(workload: VmBenchmarkWorkload, iterations: u32) -> u64 {
    match workload {
        VmBenchmarkWorkload::ComputeLoop => u64::from(iterations) * 4 + 16,
        VmBenchmarkWorkload::MemoryLoop => u64::from(iterations) * 7 + 16,
        VmBenchmarkWorkload::MmioLoop => u64::from(iterations) * 7 + 16,
        VmBenchmarkWorkload::BranchMix => u64::from(iterations) * 12 + 32,
        VmBenchmarkWorkload::CallLoop => u64::from(iterations) * 10 + 32,
    }
}

fn native_compute_loop(iterations: u32) -> u32 {
    let mut counter = 0_u32;
    while black_box(counter) != iterations {
        counter = counter.wrapping_add(1);
    }
    counter
}

fn native_memory_loop(iterations: u32) -> u32 {
    let mut counter = 0_u32;
    let mut cell = 0_u32;
    while black_box(counter) != iterations {
        cell = black_box(cell).wrapping_add(1);
        counter = counter.wrapping_add(1);
    }
    cell
}

fn native_mmio_loop(iterations: u32) -> u32 {
    let mut counter = 0_u32;
    let mut register = NativeBenchmarkRegister { value: 0 };
    while black_box(counter) != iterations {
        counter = counter.wrapping_add(1);
        register.store(counter);
        black_box(register.load());
    }
    register.load()
}

fn native_branch_mix(iterations: u32) -> u32 {
    let mut counter = 0_u32;
    let mut checksum = 0_u32;
    while black_box(counter) != iterations {
        if black_box(counter) & 1 != 0 {
            checksum = checksum.wrapping_add(3);
        } else {
            checksum = checksum.wrapping_add(1);
        }
        counter = counter.wrapping_add(1);
    }
    checksum
}

fn native_call_loop(iterations: u32) -> u32 {
    let mut counter = 0_u32;
    let mut checksum = 0_u32;
    while black_box(counter) != iterations {
        checksum = native_call_loop_helper(checksum);
        counter = counter.wrapping_add(1);
    }
    checksum
}

#[inline(never)]
fn native_call_loop_helper(value: u32) -> u32 {
    black_box(value).wrapping_add(1)
}

struct NativeBenchmarkRegister {
    value: u32,
}

impl NativeBenchmarkRegister {
    #[inline(never)]
    fn load(&self) -> u32 {
        black_box(self.value)
    }

    #[inline(never)]
    fn store(&mut self, value: u32) {
        self.value = black_box(value);
    }
}

struct BenchmarkRegisterDevice {
    value: i32,
}

impl MmioDevice for BenchmarkRegisterDevice {
    fn size(&self) -> u32 {
        4
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        if offset == 0 {
            Ok(self.value)
        } else {
            Err(MemoryFault::new(format!(
                "benchmark register offset {offset} is not mapped"
            )))
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset == 0 {
            self.value = value;
            Ok(())
        } else {
            Err(MemoryFault::new(format!(
                "benchmark register offset {offset} is not mapped"
            )))
        }
    }
}

fn write_words(bus: &mut MachineBus, address: u32, words: &[u16]) -> Result<(), String> {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_u16(address + (index as u32 * 2), word)
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn const4(dst: u8, value: u8) -> u16 {
    0x1000 | (u16::from(dst) << 8) | u16::from(value & 0x0f)
}

fn const32(dst: u8) -> u16 {
    0xe001 | (u16::from(dst) << 8)
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x2, lhs, rhs)
}

fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x8, lhs, rhs)
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

fn jump(target: u8) -> u16 {
    0x7000 | (u16::from(target) << 8)
}

fn call(target: u8) -> u16 {
    0x8000 | (u16::from(target) << 8)
}

fn ret() -> u16 {
    0x9000
}

fn halt() -> u16 {
    0x0001
}

fn low16(value: u32) -> u16 {
    value as u16
}

fn high16(value: u32) -> u16 {
    (value >> 16) as u16
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!((-8..=7).contains(&value));
    u16::from((value as i16 & 0x000f) as u8)
}
