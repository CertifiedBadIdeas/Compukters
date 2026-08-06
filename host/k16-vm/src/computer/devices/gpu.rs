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
        K16ComputerGpuStatsSnapshot {
            resource_count: self.retained.gpu().resources().len() as u64,
            authoritative_payload_bytes: self.retained.gpu().authoritative_payload_bytes() as u64,
            viewer_count: self.retained.viewer_count() as u64,
            ..self.stats
        }
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
        let outcome = self.retained.accept_serverbound(token, payload)?;
        if matches!(outcome, ServerboundOutcome::Resynchronized { .. }) {
            self.stats.resync_requests = self.stats.resync_requests.saturating_add(1);
        }
        Ok(outcome)
    }

    pub(crate) fn drain_payload(&mut self, token: u64) -> Option<Vec<u8>> {
        let payload = self.retained.drain_payload(token)?;
        self.record_publication(&payload);
        Some(payload)
    }

    pub(crate) fn drain_payload_batch(
        &mut self,
    ) -> Result<Option<Vec<u8>>, RetainedDisplayHostFault> {
        let batch = self.retained.drain_payload_batch()?;
        if let Some(bytes) = batch.as_ref() {
            let count = u32::from_le_bytes(
                bytes[12..16]
                    .try_into()
                    .expect("validated retained batch header"),
            );
            let mut offset = 16usize;
            for _ in 0..count {
                let payload_len = u32::from_le_bytes(
                    bytes[offset + 8..offset + 12]
                        .try_into()
                        .expect("validated retained batch entry"),
                ) as usize;
                let payload_start = offset + 16;
                let payload_end = payload_start + payload_len;
                self.record_publication(&bytes[payload_start..payload_end]);
                offset = payload_end;
            }
        }
        Ok(batch)
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
        self.stats.submission_attempts = self.stats.submission_attempts.saturating_add(1);
        self.reset_result();
        if self.submission_address % 4 != 0
            || self.submission_length % 4 != 0
            || !(Self::PACKET_HEADER_BYTES..=MAX_PACKET_BYTES as u32)
                .contains(&self.submission_length)
        {
            self.stats.rejected_submissions = self.stats.rejected_submissions.saturating_add(1);
            self.reject_copy(ResultCode::InvalidArgument);
            return Ok(());
        }
        let Some(end) = self.submission_address.checked_add(self.submission_length) else {
            self.stats.rejected_submissions = self.stats.rejected_submissions.saturating_add(1);
            self.reject_copy(ResultCode::OutOfBounds);
            return Ok(());
        };
        if end as usize > memory.len() {
            self.stats.rejected_submissions = self.stats.rejected_submissions.saturating_add(1);
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
        self.stats.submitted_bytes = self.stats.submitted_bytes.saturating_add(packet_len as u64);

        match self
            .retained
            .submit(&packet)
            .map_err(|error| MemoryFault::new(format!("computer gpu0 host fault: {error}")))?
        {
            SubmissionOutcome::Committed { .. } => {
                self.stats.committed_submissions =
                    self.stats.committed_submissions.saturating_add(1);
                self.reset_result();
            }
            SubmissionOutcome::Rejected(rejection) => {
                self.stats.rejected_submissions = self.stats.rejected_submissions.saturating_add(1);
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

    fn record_publication(&mut self, payload: &[u8]) {
        self.stats.network_payload_bytes = self
            .stats
            .network_payload_bytes
            .saturating_add(payload.len() as u64);
        match payload
            .get(6..8)
            .and_then(|bytes| bytes.try_into().ok())
            .map(u16::from_le_bytes)
        {
            Some(1) => {
                self.stats.snapshot_payloads = self.stats.snapshot_payloads.saturating_add(1)
            }
            Some(2) => self.stats.delta_payloads = self.stats.delta_payloads.saturating_add(1),
            _ => {}
        }
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
