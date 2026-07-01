use crate::computer::stats::K16ComputerStorageStatsSnapshot;
use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::{MachineMemory, MemoryFault};
use std::collections::HashSet;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

pub(crate) struct StoragePortDevice {
    status: i32,
    error: i32,
    lba_low: u32,
    lba_high: u32,
    block_count: u32,
    buffer_addr: u32,
    bytes_done: u32,
    sequence: u64,
    stats: K16ComputerStorageStatsSnapshot,
    media: Option<Box<dyn StorageMedia>>,
    cache: StorageBlockCache,
    read_lba_profile: HashSet<u64>,
    read_ownership: StorageReadOwnershipTracker,
}

pub(crate) struct StoragePortControllerSnapshot {
    pub(crate) status: i32,
    pub(crate) error: i32,
    pub(crate) lba_low: u32,
    pub(crate) lba_high: u32,
    pub(crate) block_count: u32,
    pub(crate) buffer_addr: u32,
    pub(crate) bytes_done: u32,
    pub(crate) sequence: u64,
}

pub(crate) trait StorageMedia {
    fn len(&self) -> u64;

    fn is_read_only(&self) -> bool;

    fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault>;

    fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault>;

    fn flush(&mut self) -> Result<(), MemoryFault>;

    fn snapshot_bytes(&self) -> Option<Vec<u8>>;
}

#[derive(Clone, Copy)]
struct StorageBlockCacheSlot {
    valid: bool,
    lba: u64,
    last_used: u64,
    bytes: [u8; StoragePortDevice::BLOCK_SIZE as usize],
}

impl StorageBlockCacheSlot {
    const fn empty() -> Self {
        Self {
            valid: false,
            lba: 0,
            last_used: 0,
            bytes: [0; StoragePortDevice::BLOCK_SIZE as usize],
        }
    }
}

struct StorageBlockCache {
    slots: [StorageBlockCacheSlot; StorageBlockCache::SLOT_COUNT],
    clock: u64,
}

impl StorageBlockCache {
    // 32 slots keep the dynamic loader's observed /bin command working set
    // without re-reading backend blocks. Payload cache cost: 16 KiB per
    // storage device, plus slot metadata.
    const SLOT_COUNT: usize = 32;

    const fn new() -> Self {
        Self {
            slots: [StorageBlockCacheSlot::empty(); Self::SLOT_COUNT],
            clock: 0,
        }
    }

    fn copy_to(
        &mut self,
        lba: u64,
        out: &mut [u8; StoragePortDevice::BLOCK_SIZE as usize],
    ) -> bool {
        for slot in &mut self.slots {
            if slot.valid && slot.lba == lba {
                self.clock = self.clock.wrapping_add(1);
                slot.last_used = self.clock;
                *out = slot.bytes;
                return true;
            }
        }
        false
    }

    fn store(&mut self, lba: u64, bytes: &[u8; StoragePortDevice::BLOCK_SIZE as usize]) {
        let slot_index = self.slot_for(lba);
        self.clock = self.clock.wrapping_add(1);
        self.slots[slot_index] = StorageBlockCacheSlot {
            valid: true,
            lba,
            last_used: self.clock,
            bytes: *bytes,
        };
    }

    fn slot_for(&self, lba: u64) -> usize {
        let mut first_invalid = None;
        let mut oldest_index = 0;
        let mut oldest_age = u64::MAX;
        for (index, slot) in self.slots.iter().enumerate() {
            if slot.valid && slot.lba == lba {
                return index;
            }
            if !slot.valid && first_invalid.is_none() {
                first_invalid = Some(index);
            }
            if slot.valid && slot.last_used < oldest_age {
                oldest_index = index;
                oldest_age = slot.last_used;
            }
        }
        first_invalid.unwrap_or(oldest_index)
    }
}

#[derive(Debug)]
pub(crate) struct K16VolumeFileStorageMedia {
    file: File,
    len: u64,
}

impl K16VolumeFileStorageMedia {
    const MAGIC: &'static [u8; 6] = b"K16VOL";
    const VERSION: u16 = 1;
    const HEADER_SIZE: u64 = 16;

    pub(crate) fn open(path: impl AsRef<Path>) -> Result<Self, MemoryFault> {
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(path.as_ref())
            .map_err(|error| {
                MemoryFault::new(format!(
                    "failed to open K16VOL file {}: {error}",
                    path.as_ref().display()
                ))
            })?;
        let file_len = file
            .metadata()
            .map_err(|error| MemoryFault::new(format!("failed to stat K16VOL file: {error}")))?
            .len();
        if file_len < Self::HEADER_SIZE {
            return Err(MemoryFault::new(format!(
                "truncated K16VOL header: file has {file_len} bytes",
            )));
        }

        let mut header = [0; Self::HEADER_SIZE as usize];
        file.seek(SeekFrom::Start(0))
            .map_err(|error| MemoryFault::new(format!("failed to seek K16VOL header: {error}")))?;
        file.read_exact(&mut header)
            .map_err(|error| MemoryFault::new(format!("failed to read K16VOL header: {error}")))?;

        if &header[0..6] != Self::MAGIC {
            return Err(MemoryFault::new("invalid K16VOL magic".to_string()));
        }
        let version = u16::from_le_bytes([header[6], header[7]]);
        if version != Self::VERSION {
            return Err(MemoryFault::new(format!(
                "unsupported K16VOL version {version}",
            )));
        }
        let len = u64::from_le_bytes([
            header[8], header[9], header[10], header[11], header[12], header[13], header[14],
            header[15],
        ]);
        if len == 0 {
            return Err(MemoryFault::new(
                "K16VOL logical size must be positive".to_string(),
            ));
        }
        let expected_len = Self::HEADER_SIZE
            .checked_add(len)
            .ok_or_else(|| MemoryFault::new("K16VOL file length overflows u64".to_string()))?;
        if file_len != expected_len {
            return Err(MemoryFault::new(format!(
                "K16VOL file length {file_len} does not match logical size {len}",
            )));
        }

        Ok(Self { file, len })
    }

    fn payload_offset(&self, offset: u64, len: usize) -> Result<u64, MemoryFault> {
        let len = u64::try_from(len)
            .map_err(|_| MemoryFault::new("K16VOL access length does not fit u64".to_string()))?;
        let end = offset
            .checked_add(len)
            .ok_or_else(|| MemoryFault::new("K16VOL access range overflows u64".to_string()))?;
        if end > self.len {
            return Err(MemoryFault::new(format!(
                "K16VOL payload access {offset}..{end} exceeds logical size {}",
                self.len,
            )));
        }
        Self::HEADER_SIZE
            .checked_add(offset)
            .ok_or_else(|| MemoryFault::new("K16VOL file offset overflows u64".to_string()))
    }
}

impl StorageMedia for K16VolumeFileStorageMedia {
    fn len(&self) -> u64 {
        self.len
    }

    fn is_read_only(&self) -> bool {
        false
    }

    fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
        let file_offset = self.payload_offset(offset, dst.len())?;
        self.file
            .seek(SeekFrom::Start(file_offset))
            .map_err(|error| MemoryFault::new(format!("failed to seek K16VOL read: {error}")))?;
        self.file
            .read_exact(dst)
            .map_err(|error| MemoryFault::new(format!("failed to read K16VOL payload: {error}")))
    }

    fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
        let file_offset = self.payload_offset(offset, src.len())?;
        self.file
            .seek(SeekFrom::Start(file_offset))
            .map_err(|error| MemoryFault::new(format!("failed to seek K16VOL write: {error}")))?;
        self.file
            .write_all(src)
            .map_err(|error| MemoryFault::new(format!("failed to write K16VOL payload: {error}")))
    }

    fn flush(&mut self) -> Result<(), MemoryFault> {
        self.file
            .sync_data()
            .map_err(|error| MemoryFault::new(format!("failed to flush K16VOL payload: {error}")))
    }

    fn snapshot_bytes(&self) -> Option<Vec<u8>> {
        None
    }
}

pub(crate) struct InMemoryStorageMedia {
    bytes: Vec<u8>,
    read_only: bool,
}

impl InMemoryStorageMedia {
    pub(crate) fn new(bytes: Vec<u8>, read_only: bool) -> Self {
        Self { bytes, read_only }
    }
}

impl StorageMedia for InMemoryStorageMedia {
    fn len(&self) -> u64 {
        self.bytes.len() as u64
    }

    fn is_read_only(&self) -> bool {
        self.read_only
    }

    fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
        let offset = usize::try_from(offset)
            .map_err(|_| MemoryFault::new("storage0 read offset does not fit usize".to_string()))?;
        let end = offset
            .checked_add(dst.len())
            .ok_or_else(|| MemoryFault::new("storage0 read range overflow".to_string()))?;
        let Some(bytes) = self.bytes.get(offset..end) else {
            return Err(MemoryFault::new(
                "storage0 read range is out of bounds".to_string(),
            ));
        };
        dst.copy_from_slice(bytes);
        Ok(())
    }

    fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
        let offset = usize::try_from(offset).map_err(|_| {
            MemoryFault::new("storage0 write offset does not fit usize".to_string())
        })?;
        let end = offset
            .checked_add(src.len())
            .ok_or_else(|| MemoryFault::new("storage0 write range overflow".to_string()))?;
        let Some(bytes) = self.bytes.get_mut(offset..end) else {
            return Err(MemoryFault::new(
                "storage0 write range is out of bounds".to_string(),
            ));
        };
        bytes.copy_from_slice(src);
        Ok(())
    }

    fn flush(&mut self) -> Result<(), MemoryFault> {
        Ok(())
    }

    fn snapshot_bytes(&self) -> Option<Vec<u8>> {
        Some(self.bytes.clone())
    }
}

impl StoragePortDevice {
    pub(crate) const SIZE: u32 = computer_abi::STORAGE0_SIZE;
    const BLOCK_SIZE: u32 = 512;

    pub(crate) fn new_absent() -> Self {
        Self {
            status: computer_abi::STORAGE_STATUS_READY,
            error: computer_abi::STORAGE_ERROR_NONE,
            lba_low: 0,
            lba_high: 0,
            block_count: 0,
            buffer_addr: 0,
            bytes_done: 0,
            sequence: 0,
            stats: K16ComputerStorageStatsSnapshot::default(),
            media: None,
            cache: StorageBlockCache::new(),
            read_lba_profile: HashSet::new(),
            read_ownership: StorageReadOwnershipTracker::new(0),
        }
    }

    pub(crate) fn with_media(bytes: Vec<u8>, read_only: bool) -> Result<Self, MemoryFault> {
        Self::with_media_backend(Box::new(InMemoryStorageMedia::new(bytes, read_only)))
    }

    pub(crate) fn with_media_backend(media: Box<dyn StorageMedia>) -> Result<Self, MemoryFault> {
        let len = media.len();
        if len % u64::from(Self::BLOCK_SIZE) != 0 {
            return Err(MemoryFault::new(format!(
                "storage0 media size {} is not a multiple of block size {}",
                len,
                Self::BLOCK_SIZE,
            )));
        }
        let mut device = Self::new_absent();
        device.read_ownership = StorageReadOwnershipTracker::new(len / u64::from(Self::BLOCK_SIZE));
        device.media = Some(media);
        Ok(device)
    }

    pub(crate) fn media_bytes(&self) -> Option<Vec<u8>> {
        self.media.as_ref().and_then(|media| media.snapshot_bytes())
    }

    pub(crate) fn stats_snapshot(&self) -> K16ComputerStorageStatsSnapshot {
        self.stats
    }

    pub(crate) fn controller_snapshot(&self) -> StoragePortControllerSnapshot {
        StoragePortControllerSnapshot {
            status: self.status,
            error: self.error,
            lba_low: self.lba_low,
            lba_high: self.lba_high,
            block_count: self.block_count,
            buffer_addr: self.buffer_addr,
            bytes_done: self.bytes_done,
            sequence: self.sequence,
        }
    }

    pub(crate) fn restore_controller_snapshot(&mut self, snapshot: StoragePortControllerSnapshot) {
        self.status = snapshot.status;
        self.error = snapshot.error;
        self.lba_low = snapshot.lba_low;
        self.lba_high = snapshot.lba_high;
        self.block_count = snapshot.block_count;
        self.buffer_addr = snapshot.buffer_addr;
        self.bytes_done = snapshot.bytes_done;
        self.sequence = snapshot.sequence;
    }

    fn execute_command(&mut self, command: i32, memory: Option<&mut MachineMemory>) {
        self.sequence = self.sequence.wrapping_add(1);
        self.bytes_done = 0;
        match command {
            computer_abi::STORAGE_COMMAND_NOP => {
                self.status = computer_abi::STORAGE_STATUS_DONE;
                self.error = computer_abi::STORAGE_ERROR_NONE;
            }
            computer_abi::STORAGE_COMMAND_FLUSH => match self.media.as_mut() {
                Some(media) => match media.flush() {
                    Ok(()) => {
                        self.stats.flush_commands = self.stats.flush_commands.wrapping_add(1);
                        self.status = computer_abi::STORAGE_STATUS_DONE;
                        self.error = computer_abi::STORAGE_ERROR_NONE;
                    }
                    Err(_) => self.fail(computer_abi::STORAGE_ERROR_IO_ERROR),
                },
                None => self.fail(computer_abi::STORAGE_ERROR_MEDIA_ABSENT),
            },
            computer_abi::STORAGE_COMMAND_READ_BLOCKS
            | computer_abi::STORAGE_COMMAND_WRITE_BLOCKS => self.execute_transfer(command, memory),
            _ => {
                self.fail(computer_abi::STORAGE_ERROR_INVALID_COMMAND);
            }
        }
    }

    fn execute_transfer(&mut self, command: i32, memory: Option<&mut MachineMemory>) {
        let Some(media) = self.media.as_mut() else {
            self.fail(computer_abi::STORAGE_ERROR_MEDIA_ABSENT);
            return;
        };
        if command == computer_abi::STORAGE_COMMAND_WRITE_BLOCKS && media.is_read_only() {
            self.fail(computer_abi::STORAGE_ERROR_WRITE_PROTECTED);
            return;
        }
        let byte_count = match self.block_count.checked_mul(Self::BLOCK_SIZE) {
            Some(value) => value,
            None => {
                self.fail(computer_abi::STORAGE_ERROR_BYTE_COUNT_OVERFLOW);
                return;
            }
        };
        let lba = (u64::from(self.lba_high) << 32) | u64::from(self.lba_low);
        let end_lba = match lba.checked_add(u64::from(self.block_count)) {
            Some(value) => value,
            None => {
                self.fail(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
                return;
            }
        };
        let capacity_blocks = media.len() / u64::from(Self::BLOCK_SIZE);
        if end_lba > capacity_blocks {
            self.fail(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS);
            return;
        }
        let Some(buffer_end) = self.buffer_addr.checked_add(byte_count) else {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        };
        let Some(memory) = memory else {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        };
        if buffer_end as usize > memory.len() {
            self.fail(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
            return;
        }
        match command {
            computer_abi::STORAGE_COMMAND_READ_BLOCKS => match Self::execute_read_blocks(
                &mut self.cache,
                &mut self.read_lba_profile,
                &mut self.read_ownership,
                media.as_mut(),
                memory,
                self.block_count,
                self.buffer_addr,
                lba,
            ) {
                Ok((read_profile, bytes_read)) => {
                    self.stats.read_commands = self.stats.read_commands.wrapping_add(1);
                    self.stats.media_read_blocks = self
                        .stats
                        .media_read_blocks
                        .wrapping_add(read_profile.total_blocks());
                    self.stats.bytes_read = self.stats.bytes_read.wrapping_add(bytes_read);
                    self.stats.unique_read_blocks = self
                        .stats
                        .unique_read_blocks
                        .wrapping_add(read_profile.unique_blocks);
                    self.stats.repeated_read_blocks = self
                        .stats
                        .repeated_read_blocks
                        .wrapping_add(read_profile.repeated_blocks);
                    self.stats.add_read_ownership(read_profile.ownership);
                }
                Err(error) => {
                    self.fail(error);
                    return;
                }
            },
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS => {
                if let Err(error) = Self::execute_write_blocks(
                    &mut self.cache,
                    media.as_mut(),
                    memory,
                    self.block_count,
                    self.buffer_addr,
                    lba,
                ) {
                    self.fail(error);
                    return;
                }
            }
            _ => unreachable!("transfer command is validated by caller"),
        }
        self.status = computer_abi::STORAGE_STATUS_DONE;
        self.error = computer_abi::STORAGE_ERROR_NONE;
        self.bytes_done = byte_count;
        match command {
            computer_abi::STORAGE_COMMAND_READ_BLOCKS => {}
            computer_abi::STORAGE_COMMAND_WRITE_BLOCKS => {
                self.stats.write_commands = self.stats.write_commands.wrapping_add(1);
                self.stats.media_write_blocks = self
                    .stats
                    .media_write_blocks
                    .wrapping_add(u64::from(self.block_count));
                self.stats.bytes_written =
                    self.stats.bytes_written.wrapping_add(u64::from(byte_count));
            }
            _ => unreachable!("transfer command is validated by caller"),
        }
    }

    fn execute_read_blocks(
        cache: &mut StorageBlockCache,
        read_lba_profile: &mut HashSet<u64>,
        read_ownership: &mut StorageReadOwnershipTracker,
        media: &mut dyn StorageMedia,
        memory: &mut MachineMemory,
        block_count: u32,
        buffer_addr: u32,
        lba: u64,
    ) -> Result<(StorageReadProfileDelta, u64), i32> {
        let mut media_read_commands = StorageReadProfileDelta::default();
        let mut media_bytes_read = 0_u64;
        for block_index in 0..block_count {
            let block_lba = lba + u64::from(block_index);
            let mut block = [0_u8; Self::BLOCK_SIZE as usize];
            if !cache.copy_to(block_lba, &mut block) {
                let media_offset = match block_lba.checked_mul(u64::from(Self::BLOCK_SIZE)) {
                    Some(value) => value,
                    None => return Err(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS),
                };
                if media.read_at(media_offset, &mut block).is_err() {
                    return Err(computer_abi::STORAGE_ERROR_IO_ERROR);
                }
                cache.store(block_lba, &block);
                if read_lba_profile.insert(block_lba) {
                    media_read_commands.unique_blocks =
                        media_read_commands.unique_blocks.wrapping_add(1);
                } else {
                    media_read_commands.repeated_blocks =
                        media_read_commands.repeated_blocks.wrapping_add(1);
                }
                media_read_commands
                    .ownership
                    .add(read_ownership.observe_read_block(block_lba, &block));
                media_bytes_read = media_bytes_read.wrapping_add(u64::from(Self::BLOCK_SIZE));
            }
            let memory_base = buffer_addr + block_index * Self::BLOCK_SIZE;
            for (offset, byte) in block.into_iter().enumerate() {
                if memory.store_u8(memory_base + offset as u32, byte).is_err() {
                    return Err(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS);
                }
            }
        }
        Ok((media_read_commands, media_bytes_read))
    }

    fn execute_write_blocks(
        cache: &mut StorageBlockCache,
        media: &mut dyn StorageMedia,
        memory: &MachineMemory,
        block_count: u32,
        buffer_addr: u32,
        lba: u64,
    ) -> Result<(), i32> {
        for block_index in 0..block_count {
            let block_lba = lba + u64::from(block_index);
            let mut block = [0_u8; Self::BLOCK_SIZE as usize];
            let memory_base = buffer_addr + block_index * Self::BLOCK_SIZE;
            for (offset, byte) in block.iter_mut().enumerate() {
                *byte = match memory.load_u8(memory_base + offset as u32) {
                    Ok(byte) => byte,
                    Err(_) => return Err(computer_abi::STORAGE_ERROR_BUFFER_OUT_OF_BOUNDS),
                };
            }
            let media_offset = match block_lba.checked_mul(u64::from(Self::BLOCK_SIZE)) {
                Some(value) => value,
                None => return Err(computer_abi::STORAGE_ERROR_LBA_OUT_OF_BOUNDS),
            };
            if media.write_at(media_offset, &block).is_err() {
                return Err(computer_abi::STORAGE_ERROR_IO_ERROR);
            }
            cache.store(block_lba, &block);
        }
        Ok(())
    }

    fn fail(&mut self, error: i32) {
        self.stats.failed_commands = self.stats.failed_commands.wrapping_add(1);
        self.status = computer_abi::STORAGE_STATUS_ERROR;
        self.error = error;
        self.bytes_done = 0;
    }

    fn load_register(&self, offset: u32) -> Result<i32, MemoryFault> {
        match offset {
            0 => Ok(computer_abi::STORAGE_VERSION),
            4 => Ok(self.status),
            8 => Ok(self.error),
            16 => Ok(Self::BLOCK_SIZE as i32),
            20 => Ok((self.capacity_blocks() as u32) as i32),
            24 => Ok((self.capacity_blocks() >> 32) as u32 as i32),
            28 => Ok(self.lba_low as i32),
            32 => Ok(self.lba_high as i32),
            36 => Ok(self.block_count as i32),
            40 => Ok(self.buffer_addr as i32),
            44 => Ok(self.bytes_done as i32),
            48 => Ok((self.sequence as u32) as i32),
            52 => Ok((self.sequence >> 32) as u32 as i32),
            56 => Ok(self.media_status()),
            _ => Err(MemoryFault::new(format!(
                "computer storage0 offset {offset} is not readable",
            ))),
        }
    }

    fn capacity_blocks(&self) -> u64 {
        self.media
            .as_ref()
            .map(|media| media.len() / u64::from(Self::BLOCK_SIZE))
            .unwrap_or(0)
    }

    fn media_status(&self) -> i32 {
        match &self.media {
            None => computer_abi::STORAGE_MEDIA_ABSENT,
            Some(media) if media.is_read_only() => computer_abi::STORAGE_MEDIA_READ_ONLY,
            Some(_) => computer_abi::STORAGE_MEDIA_PRESENT,
        }
    }

    fn store_register(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        match offset {
            12 => {
                self.execute_command(value, None);
                Ok(())
            }
            28 => {
                self.lba_low = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            32 => {
                self.lba_high = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            36 => {
                self.block_count = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            40 => {
                self.buffer_addr = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            _ => Err(MemoryFault::new(format!(
                "computer storage0 offset {offset} is not writable",
            ))),
        }
    }
}

#[derive(Default)]
struct StorageReadProfileDelta {
    unique_blocks: u64,
    repeated_blocks: u64,
    ownership: StorageReadOwnershipDelta,
}

impl StorageReadProfileDelta {
    fn total_blocks(&self) -> u64 {
        self.unique_blocks.wrapping_add(self.repeated_blocks)
    }
}

#[derive(Default)]
struct StorageReadOwnershipDelta {
    partition_table_blocks: u64,
    boot_metadata_blocks: u64,
    boot_data_blocks: u64,
    root_metadata_blocks: u64,
    root_data_blocks: u64,
    unknown_blocks: u64,
}

impl StorageReadOwnershipDelta {
    fn add(&mut self, ownership: StorageReadOwnership) {
        match ownership {
            StorageReadOwnership::PartitionTable => {
                self.partition_table_blocks = self.partition_table_blocks.wrapping_add(1);
            }
            StorageReadOwnership::BootMetadata => {
                self.boot_metadata_blocks = self.boot_metadata_blocks.wrapping_add(1);
            }
            StorageReadOwnership::BootData => {
                self.boot_data_blocks = self.boot_data_blocks.wrapping_add(1);
            }
            StorageReadOwnership::RootMetadata => {
                self.root_metadata_blocks = self.root_metadata_blocks.wrapping_add(1);
            }
            StorageReadOwnership::RootData => {
                self.root_data_blocks = self.root_data_blocks.wrapping_add(1);
            }
            StorageReadOwnership::Unknown => {
                self.unknown_blocks = self.unknown_blocks.wrapping_add(1);
            }
        }
    }
}

impl K16ComputerStorageStatsSnapshot {
    fn add_read_ownership(&mut self, delta: StorageReadOwnershipDelta) {
        self.partition_table_read_blocks = self
            .partition_table_read_blocks
            .wrapping_add(delta.partition_table_blocks);
        self.boot_metadata_read_blocks = self
            .boot_metadata_read_blocks
            .wrapping_add(delta.boot_metadata_blocks);
        self.boot_data_read_blocks = self
            .boot_data_read_blocks
            .wrapping_add(delta.boot_data_blocks);
        self.root_metadata_read_blocks = self
            .root_metadata_read_blocks
            .wrapping_add(delta.root_metadata_blocks);
        self.root_data_read_blocks = self
            .root_data_read_blocks
            .wrapping_add(delta.root_data_blocks);
        self.unknown_read_blocks = self.unknown_read_blocks.wrapping_add(delta.unknown_blocks);
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum StorageReadOwnership {
    PartitionTable,
    BootMetadata,
    BootData,
    RootMetadata,
    RootData,
    Unknown,
}

#[derive(Default)]
struct StorageReadOwnershipTracker {
    total_blocks: u64,
    boot_partition: Option<StoragePartitionRange>,
    root_partition: Option<StoragePartitionRange>,
    boot_fs: Option<K16FsMetadataLayout>,
    root_fs: Option<K16FsMetadataLayout>,
}

impl StorageReadOwnershipTracker {
    const K16PT_HEADER_SIZE: usize = 16;
    const K16PT_ENTRY_SIZE: usize = 32;
    const K16PT_MAX_ENTRIES: usize =
        (StoragePortDevice::BLOCK_SIZE as usize - Self::K16PT_HEADER_SIZE) / Self::K16PT_ENTRY_SIZE;

    fn new(total_blocks: u64) -> Self {
        Self {
            total_blocks,
            boot_partition: None,
            root_partition: None,
            boot_fs: None,
            root_fs: None,
        }
    }

    fn observe_read_block(
        &mut self,
        lba: u64,
        block: &[u8; StoragePortDevice::BLOCK_SIZE as usize],
    ) -> StorageReadOwnership {
        if lba == 0 {
            self.observe_partition_table(block);
        }

        if self.boot_partition.map(|partition| partition.start_lba) == Some(lba) {
            self.boot_fs = Self::decode_k16fs_metadata_layout(block, self.boot_partition);
        }
        if self.root_partition.map(|partition| partition.start_lba) == Some(lba) {
            self.root_fs = Self::decode_k16fs_metadata_layout(block, self.root_partition);
        }

        if lba == 0 {
            return StorageReadOwnership::PartitionTable;
        }
        if let Some(partition) = self.boot_partition {
            if let Some(relative_lba) = partition.relative_lba(lba) {
                return match self.boot_fs {
                    Some(layout) if layout.is_metadata_block(relative_lba) => {
                        StorageReadOwnership::BootMetadata
                    }
                    Some(_) => StorageReadOwnership::BootData,
                    None => StorageReadOwnership::Unknown,
                };
            }
        }
        if let Some(partition) = self.root_partition {
            if let Some(relative_lba) = partition.relative_lba(lba) {
                return match self.root_fs {
                    Some(layout) if layout.is_metadata_block(relative_lba) => {
                        StorageReadOwnership::RootMetadata
                    }
                    Some(_) => StorageReadOwnership::RootData,
                    None => StorageReadOwnership::Unknown,
                };
            }
        }
        StorageReadOwnership::Unknown
    }

    fn observe_partition_table(&mut self, block: &[u8; StoragePortDevice::BLOCK_SIZE as usize]) {
        let Some(entry_count) = Self::decode_k16pt_header(block) else {
            return;
        };
        for index in 0..entry_count {
            let offset = Self::K16PT_HEADER_SIZE + index * Self::K16PT_ENTRY_SIZE;
            let Some(entry) = StoragePartitionRange::decode(block, offset, self.total_blocks)
            else {
                continue;
            };
            match &block[offset..offset + 4] {
                b"BOOT" => self.boot_partition = Some(entry),
                b"ROOT" => self.root_partition = Some(entry),
                _ => {}
            }
        }
    }

    fn decode_k16pt_header(block: &[u8; StoragePortDevice::BLOCK_SIZE as usize]) -> Option<usize> {
        if &block[0..5] != b"K16PT" || block[5] != 1 || block[7] != 0 {
            return None;
        }
        if read_u32_le(block, 8)? != 0 || read_u32_le(block, 12)? != 1 {
            return None;
        }
        let entry_count = block[6] as usize;
        (entry_count <= Self::K16PT_MAX_ENTRIES).then_some(entry_count)
    }

    fn decode_k16fs_metadata_layout(
        block: &[u8; StoragePortDevice::BLOCK_SIZE as usize],
        partition: Option<StoragePartitionRange>,
    ) -> Option<K16FsMetadataLayout> {
        let partition = partition?;
        if &block[0..5] != b"K16FS" || block[5] != 1 || block[6] != 0 || block[7] != 0 {
            return None;
        }
        let block_size = read_u32_le(block, 0x08)?;
        if block_size != StoragePortDevice::BLOCK_SIZE {
            return None;
        }
        let total_blocks = read_u32_le(block, 0x0c)?;
        if total_blocks == 0 || u64::from(total_blocks) > partition.block_count {
            return None;
        }
        let bitmap_start_block = read_u32_le(block, 0x10)?;
        let bitmap_block_count = read_u32_le(block, 0x14)?;
        let inode_table_start_block = read_u32_le(block, 0x18)?;
        let inode_table_block_count = read_u32_le(block, 0x1c)?;
        let layout = K16FsMetadataLayout {
            bitmap_start_block,
            bitmap_block_count,
            inode_table_start_block,
            inode_table_block_count,
        };
        layout.is_valid(total_blocks).then_some(layout)
    }
}

#[derive(Clone, Copy)]
struct StoragePartitionRange {
    start_lba: u64,
    block_count: u64,
}

impl StoragePartitionRange {
    fn decode(
        block: &[u8; StoragePortDevice::BLOCK_SIZE as usize],
        offset: usize,
        total_blocks: u64,
    ) -> Option<Self> {
        let start_lba = u64::from(read_u32_le(block, offset + 8)?);
        let block_count = u64::from(read_u32_le(block, offset + 12)?);
        if start_lba < 1 || block_count == 0 {
            return None;
        }
        let end_lba = start_lba.checked_add(block_count)?;
        if end_lba > total_blocks {
            return None;
        }
        Some(Self {
            start_lba,
            block_count,
        })
    }

    fn relative_lba(&self, lba: u64) -> Option<u32> {
        if lba < self.start_lba || lba >= self.start_lba.checked_add(self.block_count)? {
            return None;
        }
        u32::try_from(lba - self.start_lba).ok()
    }
}

#[derive(Clone, Copy)]
struct K16FsMetadataLayout {
    bitmap_start_block: u32,
    bitmap_block_count: u32,
    inode_table_start_block: u32,
    inode_table_block_count: u32,
}

impl K16FsMetadataLayout {
    fn is_valid(&self, total_blocks: u32) -> bool {
        self.range_fits(
            self.bitmap_start_block,
            self.bitmap_block_count,
            total_blocks,
        ) && self.range_fits(
            self.inode_table_start_block,
            self.inode_table_block_count,
            total_blocks,
        )
    }

    fn is_metadata_block(&self, relative_lba: u32) -> bool {
        relative_lba == 0
            || self.contains(
                self.bitmap_start_block,
                self.bitmap_block_count,
                relative_lba,
            )
            || self.contains(
                self.inode_table_start_block,
                self.inode_table_block_count,
                relative_lba,
            )
    }

    fn range_fits(&self, start: u32, count: u32, total_blocks: u32) -> bool {
        count > 0
            && start
                .checked_add(count)
                .is_some_and(|end| end <= total_blocks)
    }

    fn contains(&self, start: u32, count: u32, block: u32) -> bool {
        start
            .checked_add(count)
            .is_some_and(|end| block >= start && block < end)
    }
}

fn read_u32_le(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset + 4)?.try_into().ok()?,
    ))
}

impl MmioDevice for StoragePortDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        self.load_register(offset)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        self.store_register(offset, value)
    }

    fn store_i32_with_memory(
        &mut self,
        offset: u32,
        value: i32,
        memory: &mut MachineMemory,
    ) -> Result<(), MemoryFault> {
        if offset == 12 {
            self.execute_command(value, Some(memory));
            return Ok(());
        }
        self.store_register(offset, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::fs;
    use std::rc::Rc;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct CountingFlushMedia {
        bytes: Vec<u8>,
        flush_count: Rc<Cell<u32>>,
    }

    struct CountingReadMedia {
        bytes: Vec<u8>,
        read_count: Rc<Cell<u32>>,
    }

    impl StorageMedia for CountingFlushMedia {
        fn len(&self) -> u64 {
            self.bytes.len() as u64
        }

        fn is_read_only(&self) -> bool {
            false
        }

        fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            dst.copy_from_slice(&self.bytes[offset..offset + dst.len()]);
            Ok(())
        }

        fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            self.bytes[offset..offset + src.len()].copy_from_slice(src);
            Ok(())
        }

        fn flush(&mut self) -> Result<(), MemoryFault> {
            self.flush_count.set(self.flush_count.get() + 1);
            Ok(())
        }

        fn snapshot_bytes(&self) -> Option<Vec<u8>> {
            Some(self.bytes.clone())
        }
    }

    impl StorageMedia for CountingReadMedia {
        fn len(&self) -> u64 {
            self.bytes.len() as u64
        }

        fn is_read_only(&self) -> bool {
            false
        }

        fn read_at(&mut self, offset: u64, dst: &mut [u8]) -> Result<(), MemoryFault> {
            self.read_count.set(self.read_count.get() + 1);
            let offset = offset as usize;
            dst.copy_from_slice(&self.bytes[offset..offset + dst.len()]);
            Ok(())
        }

        fn write_at(&mut self, offset: u64, src: &[u8]) -> Result<(), MemoryFault> {
            let offset = offset as usize;
            self.bytes[offset..offset + src.len()].copy_from_slice(src);
            Ok(())
        }

        fn flush(&mut self) -> Result<(), MemoryFault> {
            Ok(())
        }

        fn snapshot_bytes(&self) -> Option<Vec<u8>> {
            Some(self.bytes.clone())
        }
    }

    #[test]
    fn storage_port_flush_delegates_to_media_backend() {
        let flush_count = Rc::new(Cell::new(0));
        let media = CountingFlushMedia {
            bytes: vec![0; 512],
            flush_count: flush_count.clone(),
        };
        let mut device = StoragePortDevice::with_media_backend(Box::new(media)).unwrap();

        device
            .store_i32(12, computer_abi::STORAGE_COMMAND_FLUSH)
            .unwrap();

        assert_eq!(flush_count.get(), 1);
        assert_eq!(
            device.load_i32(4).unwrap(),
            computer_abi::STORAGE_STATUS_DONE,
        );
    }

    #[test]
    fn storage_port_read_blocks_reuses_cached_backend_block() {
        let read_count = Rc::new(Cell::new(0));
        let mut media_bytes = vec![0_u8; 512];
        media_bytes[0] = 0xA5;
        media_bytes[511] = 0x5A;
        let media = CountingReadMedia {
            bytes: media_bytes,
            read_count: read_count.clone(),
        };
        let mut device = StoragePortDevice::with_media_backend(Box::new(media)).unwrap();
        let mut memory = MachineMemory::zeroed(2048).unwrap();
        device.store_i32(28, 0).unwrap();
        device.store_i32(32, 0).unwrap();
        device.store_i32(36, 1).unwrap();
        device.store_i32(40, 512).unwrap();

        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();
        memory.store_u8(512, 0).unwrap();
        memory.store_u8(1023, 0).unwrap();
        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();

        assert_eq!(read_count.get(), 1);
        assert_eq!(memory.load_u8(512).unwrap(), 0xA5);
        assert_eq!(memory.load_u8(1023).unwrap(), 0x5A);
    }

    #[test]
    fn storage_port_stats_split_read_transfers_from_backend_blocks() {
        let read_count = Rc::new(Cell::new(0));
        let mut media_bytes = vec![0_u8; 512 * 3];
        media_bytes[0] = 0xA5;
        media_bytes[512] = 0xB6;
        media_bytes[1024] = 0xC7;
        let media = CountingReadMedia {
            bytes: media_bytes,
            read_count: read_count.clone(),
        };
        let mut device = StoragePortDevice::with_media_backend(Box::new(media)).unwrap();
        let mut memory = MachineMemory::zeroed(2048).unwrap();
        device.store_i32(28, 0).unwrap();
        device.store_i32(32, 0).unwrap();
        device.store_i32(36, 3).unwrap();
        device.store_i32(40, 512).unwrap();

        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();
        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();

        let stats = device.stats_snapshot();
        assert_eq!(read_count.get(), 3);
        assert_eq!(stats.read_commands, 2);
        assert_eq!(stats.media_read_blocks, 3);
        assert_eq!(stats.bytes_read, 1536);
    }

    #[test]
    fn storage_block_cache_retains_dynamic_loader_working_set() {
        let mut cache = StorageBlockCache::new();
        let mut out = [0_u8; StoragePortDevice::BLOCK_SIZE as usize];

        for lba in 0..32 {
            let block = [lba as u8; StoragePortDevice::BLOCK_SIZE as usize];
            cache.store(lba, &block);
        }

        assert!(cache.copy_to(0, &mut out));
        assert_eq!(out[0], 0);
    }

    #[test]
    fn storage_port_write_blocks_updates_cached_backend_block() {
        let read_count = Rc::new(Cell::new(0));
        let media = CountingReadMedia {
            bytes: vec![0_u8; 512],
            read_count: read_count.clone(),
        };
        let mut device = StoragePortDevice::with_media_backend(Box::new(media)).unwrap();
        let mut memory = MachineMemory::zeroed(2048).unwrap();
        device.store_i32(28, 0).unwrap();
        device.store_i32(32, 0).unwrap();
        device.store_i32(36, 1).unwrap();
        device.store_i32(40, 512).unwrap();

        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();
        memory.store_u8(512, 0xC3).unwrap();
        memory.store_u8(1023, 0x3C).unwrap();
        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_WRITE_BLOCKS, &mut memory)
            .unwrap();
        memory.store_u8(512, 0).unwrap();
        memory.store_u8(1023, 0).unwrap();
        device
            .store_i32_with_memory(12, computer_abi::STORAGE_COMMAND_READ_BLOCKS, &mut memory)
            .unwrap();

        assert_eq!(read_count.get(), 1);
        assert_eq!(memory.load_u8(512).unwrap(), 0xC3);
        assert_eq!(memory.load_u8(1023).unwrap(), 0x3C);
    }

    #[test]
    fn k16_volume_file_media_reads_writes_and_flushes_payload() {
        let path = temp_volume_path("read_write_flush");
        write_k16_volume(&path, &[0; 512]);
        let mut media = K16VolumeFileStorageMedia::open(&path).unwrap();

        media.write_at(511, &[0xA5]).unwrap();
        media.flush().unwrap();

        let bytes = fs::read(&path).unwrap();
        assert_eq!(bytes[16 + 511], 0xA5);

        let mut read = [0; 1];
        media.read_at(511, &mut read).unwrap();
        assert_eq!(read, [0xA5]);

        fs::remove_file(path).unwrap();
    }

    #[test]
    fn k16_volume_file_media_rejects_invalid_magic() {
        let path = temp_volume_path("invalid_magic");
        fs::write(&path, b"BADVOL\x01\x00\x00\x02\x00\x00\x00\x00\x00\x00").unwrap();

        let error = K16VolumeFileStorageMedia::open(&path).unwrap_err();

        assert!(error.to_string().contains("invalid K16VOL magic"));
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn k16_volume_file_storage_source_uses_k16_names() {
        let devices_source = include_str!("../devices.rs");
        let storage_source = include_str!("storage.rs");
        let profile_source = include_str!("../profile.rs");

        assert!(!devices_source.contains(&["Rux", "VolumeFile"].concat()));
        assert!(!storage_source.contains(&["Rux", "VolumeFile"].concat()));
        assert!(!profile_source.contains(&["Rux", "VolumeFile"].concat()));
        assert!(!profile_source.contains(&["with_", "rux", "_volume_file"].concat()));
    }

    fn write_k16_volume(path: &std::path::Path, payload: &[u8]) {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"K16VOL");
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&(payload.len() as u64).to_le_bytes());
        bytes.extend_from_slice(payload);
        fs::write(path, bytes).unwrap();
    }

    fn temp_volume_path(name: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("k16-vm-{name}-{}-{nanos}.kv", std::process::id()))
    }
}
