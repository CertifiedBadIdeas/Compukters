use crate::computer::stats::K16ComputerGpuStatsSnapshot;
use crate::computer_abi;
use crate::low_bus::MmioDevice;
use crate::low_machine::{MachineMemory, MemoryFault};
use crate::retained_gpu::{
    ResultCode, RetainedDisplayHost, RetainedDisplayHostFault, ServerboundOutcome,
    SubmissionOutcome, DISPLAY_HEIGHT, DISPLAY_WIDTH, MAX_CLIP_DEPTH, MAX_DRAW_COMMANDS,
    MAX_DRAW_LIST_BYTES, MAX_PACKET_BYTES, MAX_RESOURCES, MAX_RESOURCE_BYTES,
    MAX_TOTAL_RESOURCE_BYTES, MAX_TRANSACTION_OPERATIONS,
};

pub(crate) struct GpuDevice {
    retained: RetainedDisplayHost,
    submission_address: u32,
    submission_length: u32,
    result_code: ResultCode,
    error_operation_index: u32,
    error_byte_offset: u32,
    stats: K16ComputerGpuStatsSnapshot,
}

impl GpuDevice {
    pub(crate) const SIZE: u32 = computer_abi::GPU0_SIZE;
    const PACKET_HEADER_BYTES: u32 = 24;

    pub(crate) fn new() -> Result<Self, MemoryFault> {
        Ok(Self {
            retained: RetainedDisplayHost::try_new().map_err(|error| {
                MemoryFault::new(format!("computer gpu0 construction failed: {error}"))
            })?,
            submission_address: 0,
            submission_length: 0,
            result_code: ResultCode::Ok,
            error_operation_index: u32::MAX,
            error_byte_offset: u32::MAX,
            stats: K16ComputerGpuStatsSnapshot::default(),
        })
    }

    pub(crate) fn stats_snapshot(&self) -> K16ComputerGpuStatsSnapshot {
        self.stats
    }

    pub(crate) fn attach_viewer(
        &mut self,
        token: u64,
        computer_id: u32,
    ) -> Result<u64, RetainedDisplayHostFault> {
        self.retained.attach_viewer(token, computer_id)
    }

    pub(crate) fn detach_viewer(&mut self, token: u64) -> bool {
        self.retained.detach_viewer(token)
    }

    pub(crate) fn accept_serverbound(
        &mut self,
        token: u64,
        payload: &[u8],
    ) -> Result<ServerboundOutcome, RetainedDisplayHostFault> {
        self.retained.accept_serverbound(token, payload)
    }

    pub(crate) fn drain_payload(&mut self, token: u64) -> Option<Vec<u8>> {
        self.retained.drain_payload(token)
    }

    pub(crate) fn drain_payload_batch(
        &mut self,
    ) -> Result<Option<Vec<u8>>, RetainedDisplayHostFault> {
        self.retained.drain_payload_batch()
    }

    pub(crate) fn advance_tick(&mut self) -> Result<(), RetainedDisplayHostFault> {
        self.retained.advance_tick()
    }

    pub(crate) fn authoritative_payload_bytes(&self) -> usize {
        self.retained.gpu().authoritative_payload_bytes()
    }

    fn load_register(&self, offset: u32) -> Result<i32, MemoryFault> {
        let value = match offset {
            0 => computer_abi::GPU0_DEVICE_ABI_VERSION_VALUE,
            4 => i32::from(DISPLAY_WIDTH),
            8 => i32::from(DISPLAY_HEIGHT),
            12 => computer_abi::GPU0_PACKET_VERSION_VALUE,
            16 => MAX_PACKET_BYTES as i32,
            20 => MAX_TRANSACTION_OPERATIONS as i32,
            24 => MAX_RESOURCES as i32,
            28 => MAX_RESOURCE_BYTES as i32,
            32 => MAX_TOTAL_RESOURCE_BYTES as i32,
            36 => MAX_DRAW_LIST_BYTES as i32,
            40 => MAX_DRAW_COMMANDS as i32,
            44 => MAX_CLIP_DEPTH as i32,
            48 => self.submission_address as i32,
            52 => self.submission_length as i32,
            60 => self.result_code as u32 as i32,
            64 => self.error_operation_index as i32,
            68 => self.error_byte_offset as i32,
            72 => self.retained.gpu().commit_sequence() as u32 as i32,
            76 => (self.retained.gpu().commit_sequence() >> 32) as u32 as i32,
            _ => {
                return Err(MemoryFault::new(format!(
                    "computer gpu0 offset {offset} is not readable",
                )))
            }
        };
        Ok(value)
    }

    fn store_register(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        match offset {
            48 => {
                self.submission_address = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            52 => {
                self.submission_length = u32::from_le_bytes(value.to_le_bytes());
                Ok(())
            }
            56 => Err(MemoryFault::new(
                "computer gpu0 SUBMIT requires guest RAM access".to_string(),
            )),
            _ => Err(MemoryFault::new(format!(
                "computer gpu0 offset {offset} is not writable",
            ))),
        }
    }

    fn submit(&mut self, memory: &MachineMemory) -> Result<(), MemoryFault> {
        self.reset_result();
        if self.submission_address % 4 != 0
            || self.submission_length % 4 != 0
            || !(Self::PACKET_HEADER_BYTES..=MAX_PACKET_BYTES as u32)
                .contains(&self.submission_length)
        {
            self.reject_copy(ResultCode::InvalidArgument);
            return Ok(());
        }
        let Some(end) = self.submission_address.checked_add(self.submission_length) else {
            self.reject_copy(ResultCode::OutOfBounds);
            return Ok(());
        };
        if end as usize > memory.len() {
            self.reject_copy(ResultCode::OutOfBounds);
            return Ok(());
        }

        let packet_len = self.submission_length as usize;
        let mut packet = Vec::new();
        packet.try_reserve_exact(packet_len).map_err(|_| {
            MemoryFault::new("computer gpu0 packet copy allocation failed".to_string())
        })?;
        for offset in 0..self.submission_length {
            packet.push(
                memory
                    .load_u8(self.submission_address + offset)
                    .expect("gpu0 guest packet range was prevalidated"),
            );
        }

        match self
            .retained
            .submit(&packet)
            .map_err(|error| MemoryFault::new(format!("computer gpu0 host fault: {error}")))?
        {
            SubmissionOutcome::Committed { .. } => self.reset_result(),
            SubmissionOutcome::Rejected(rejection) => {
                self.result_code = rejection.code;
                self.error_operation_index = rejection.operation_index;
                self.error_byte_offset = rejection.byte_offset;
            }
        }
        Ok(())
    }

    fn reset_result(&mut self) {
        self.result_code = ResultCode::Ok;
        self.error_operation_index = u32::MAX;
        self.error_byte_offset = u32::MAX;
    }

    fn reject_copy(&mut self, code: ResultCode) {
        self.result_code = code;
        self.error_operation_index = u32::MAX;
        self.error_byte_offset = u32::MAX;
    }
}

impl MmioDevice for GpuDevice {
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
        if offset == 56 {
            let _ = value;
            return self.submit(memory);
        }
        self.store_register(offset, value)
    }
}
