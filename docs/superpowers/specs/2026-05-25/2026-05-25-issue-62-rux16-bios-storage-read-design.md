# Rux16 BIOS Storage Read Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Context

The current Rux16 boot path can start directly from read-only BIOS flash mapped into the machine bus. That replaces the older bridge where a `LowImage` BIOS had to hand off to Rux16 guest RAM.

The next BIOS step is not executing a disk program yet. First, BIOS code must prove it can access persistent storage as a guest-visible device: configure `storage0` MMIO registers, read a block into RAM, and make an observable decision from the bytes that arrived in RAM.

## Goal

Add a native Rux16 BIOS smoke path where BIOS starts from flash, reads block 0 from `storage0` into guest RAM through MMIO, and reports bytes loaded from RAM through existing debug/control MMIO.

## Architecture

`RuxComputerHandle` will get a constructor that combines the new Rux16 BIOS flash boot path with in-memory `storage0` media. The host remains responsible only for attaching devices and providing flash/media bytes. The BIOS program runs as Rux16 guest code and performs the storage read by writing `STORAGE0_LBA_*`, `STORAGE0_BLOCK_COUNT`, `STORAGE0_BUFFER_ADDR`, and `STORAGE0_COMMAND`.

The storage device is already synchronous in native tests: after BIOS writes `STORAGE_COMMAND_READ_BLOCKS`, the block is copied into guest RAM. BIOS then loads bytes from the RAM staging buffer and writes them to debug serial. This verifies the data path without adding a bootloader jump or host decode path.

## Out of Scope

- Jumping to a stage2/kernel program from RAM.
- Filesystem or partition-table parsing.
- Host-side RUXI decode/start handoff.
- Any fallback from Rux16 BIOS storage boot to `LowImage`.
- Asynchronous storage interrupts or pending-handoff scheduling.

## Verification

- Native Rust test: Rux16 BIOS from flash reads block 0 from `storage0` into RAM and writes the loaded magic bytes to debug MMIO.
- Native Rust test: the BIOS reports the storage read status through control MMIO.
- Regression: existing `rux_computer` and native `rux-vm` tests continue to pass.
