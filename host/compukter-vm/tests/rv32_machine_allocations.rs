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

#[path = "support/rv32_elf.rs"]
#[allow(dead_code)]
mod rv32_elf_support;

use compukter_vm::rv32_machine::{
    Rv32ExecutionBackendConfig, Rv32Machine, Rv32MachineConfig, Rv32MachineOutcome,
};
use compukter_vm::rv32im::encoding::{addi, csrrs, csrrw, ecall, jal, materialize, mret};
use rv32_elf_support::{Elf32Builder, LoadSegment};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};

const CSR_MTVEC: u16 = 0x305;
const CSR_MEPC: u16 = 0x341;

struct CountingAllocator;

static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static ALLOCATED_BYTES: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { System.dealloc(pointer, layout) }
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        ALLOCATED_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
        unsafe { System.realloc(pointer, layout, new_size) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

#[test]
fn steady_state_trap_entry_and_return_allocate_nothing() {
    let [vector_hi, vector_lo] = materialize(1, 0x2000);
    let main = [
        vector_hi,
        vector_lo,
        csrrw(0, CSR_MTVEC, 1),
        ecall(),
        jal(0, -4),
    ];
    let handler = [
        csrrs(2, CSR_MEPC, 0),
        addi(2, 2, 4),
        csrrw(0, CSR_MEPC, 2),
        mret(),
    ];
    let words = |words: &[u32]| {
        words
            .iter()
            .copied()
            .flat_map(u32::to_le_bytes)
            .collect::<Vec<_>>()
    };
    let elf = Elf32Builder::new(0x1000)
        .load(LoadSegment::rx(0x1000, words(&main)))
        .load(LoadSegment::rx(0x2000, words(&handler)))
        .load(LoadSegment::rw_with_mem_size(0x3000, [], 0x1000))
        .finish();

    for execution in [
        Rv32ExecutionBackendConfig::Cached { sets: 64 },
        Rv32ExecutionBackendConfig::Predecoded,
    ] {
        let mut machine = Rv32Machine::from_elf(
            &elf,
            Rv32MachineConfig {
                ram_size: 0x10_000,
                debug_limit: 0,
                execution,
            },
        )
        .unwrap();
        assert!(matches!(
            machine.run(128).unwrap(),
            Rv32MachineOutcome::BudgetExhausted { .. }
        ));

        ALLOCATIONS.store(0, Ordering::Relaxed);
        ALLOCATED_BYTES.store(0, Ordering::Relaxed);
        let outcome = machine.run(4096).unwrap();
        let allocations = ALLOCATIONS.load(Ordering::Relaxed);
        let allocated_bytes = ALLOCATED_BYTES.load(Ordering::Relaxed);

        assert!(matches!(
            outcome,
            Rv32MachineOutcome::BudgetExhausted { .. }
        ));
        assert_eq!(allocations, 0, "{execution:?} allocated in steady state");
        assert_eq!(
            allocated_bytes, 0,
            "{execution:?} allocated bytes in steady state"
        );
    }
}
