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

#![allow(
    dead_code,
    reason = "the arena is introduced before the machine-owned JIT dispatcher"
)]

use super::backend::CodeBlob;
use memmap2::{Mmap, MmapMut};
use std::mem;
use thiserror::Error;

const PAGE_BYTES: usize = 4096;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct CompiledBlockId(usize);

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub(crate) enum ArenaError {
    #[error("RV32 JIT executable code arena capacity must be positive")]
    InvalidCapacity,
    #[error("RV32 JIT executable code arena is at its configured capacity")]
    Capacity,
    #[error("RV32 JIT code blob is invalid: {0}")]
    InvalidBlob(String),
    #[error("RV32 JIT executable mapping failed: {0}")]
    Mapping(String),
}

struct PendingBlock {
    id: CompiledBlockId,
    mapping: MmapMut,
    blob: CodeBlob,
}

struct SealedBlock {
    id: CompiledBlockId,
    mapping: Mmap,
}

pub(crate) struct ExecutableCodeArena {
    max_reserved_bytes: usize,
    reserved_bytes: usize,
    emitted_bytes: usize,
    next_id: usize,
    pending: Vec<PendingBlock>,
    sealed: Vec<SealedBlock>,
}

impl ExecutableCodeArena {
    pub(crate) fn new(max_reserved_bytes: usize) -> Result<Self, ArenaError> {
        if max_reserved_bytes == 0 || !max_reserved_bytes.is_multiple_of(PAGE_BYTES) {
            return Err(ArenaError::InvalidCapacity);
        }
        Ok(Self {
            max_reserved_bytes,
            reserved_bytes: 0,
            emitted_bytes: 0,
            next_id: 0,
            pending: Vec::new(),
            sealed: Vec::new(),
        })
    }

    pub(crate) fn stage(&mut self, blob: CodeBlob) -> Result<CompiledBlockId, ArenaError> {
        let emitted_bytes = blob.bytes().len();
        let reserved_bytes = page_rounded_bytes(emitted_bytes).ok_or(ArenaError::Capacity)?;
        if reserved_bytes > self.max_reserved_bytes.saturating_sub(self.reserved_bytes) {
            return Err(ArenaError::Capacity);
        }
        let mut mapping = MmapMut::map_anon(reserved_bytes)
            .map_err(|error| ArenaError::Mapping(error.to_string()))?;
        mapping[..emitted_bytes].copy_from_slice(blob.bytes());
        let id = CompiledBlockId(self.next_id);
        self.next_id = self.next_id.saturating_add(1);
        self.reserved_bytes += reserved_bytes;
        self.emitted_bytes += emitted_bytes;
        self.pending.push(PendingBlock { id, mapping, blob });
        Ok(id)
    }

    pub(crate) fn seal_batch(&mut self) -> Result<(), ArenaError> {
        for pending in &self.pending {
            pending
                .blob
                .validate_relocations()
                .map_err(ArenaError::InvalidBlob)?;
        }
        let pending = mem::take(&mut self.pending);
        let mut sealed = Vec::with_capacity(pending.len());
        for pending in pending {
            pending
                .mapping
                .flush()
                .map_err(|error| ArenaError::Mapping(error.to_string()))?;
            let mapping = pending
                .mapping
                .make_exec()
                .map_err(|error| ArenaError::Mapping(error.to_string()))?;
            sealed.push(SealedBlock {
                id: pending.id,
                mapping,
            });
        }
        self.sealed.extend(sealed);
        Ok(())
    }

    pub(crate) fn entry_address(&self, id: CompiledBlockId) -> Option<*const u8> {
        self.sealed
            .iter()
            .find(|block| block.id == id)
            .map(|block| block.mapping.as_ptr())
    }

    pub(crate) fn reserved_bytes(&self) -> usize {
        self.reserved_bytes
    }

    pub(crate) fn emitted_bytes(&self) -> usize {
        self.emitted_bytes
    }
}

fn page_rounded_bytes(emitted_bytes: usize) -> Option<usize> {
    emitted_bytes
        .max(1)
        .checked_add(PAGE_BYTES - 1)
        .map(|bytes| bytes / PAGE_BYTES * PAGE_BYTES)
}

#[cfg(test)]
mod tests {
    use super::{ArenaError, ExecutableCodeArena};
    use crate::rv32_jit::backend::CodeBlob;

    const PAGE_BYTES: usize = 4096;

    #[cfg(target_arch = "x86_64")]
    #[test]
    fn blocks_are_invisible_until_the_rw_batch_is_sealed_rx() {
        let mut arena = ExecutableCodeArena::new(PAGE_BYTES).unwrap();
        let pending = arena
            .stage(CodeBlob::new(vec![0xb8, 7, 0, 0, 0, 0xc3], vec![]))
            .unwrap();

        assert!(arena.entry_address(pending).is_none());
        assert_eq!(arena.emitted_bytes(), 6);
        assert_eq!(arena.reserved_bytes(), PAGE_BYTES);
        arena.seal_batch().unwrap();
        let entry: unsafe extern "C" fn() -> u32 =
            unsafe { std::mem::transmute(arena.entry_address(pending).unwrap()) };
        assert_eq!(unsafe { entry() }, 7);
    }

    #[test]
    fn arena_rejects_a_second_page_when_the_configured_cap_is_one_page() {
        let mut arena = ExecutableCodeArena::new(PAGE_BYTES).unwrap();
        arena
            .stage(CodeBlob::new(vec![0; PAGE_BYTES], vec![]))
            .unwrap();
        arena.seal_batch().unwrap();

        assert_eq!(
            arena.stage(CodeBlob::new(vec![0xc3], vec![])),
            Err(ArenaError::Capacity)
        );
    }
}
