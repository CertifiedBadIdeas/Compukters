use super::serverbound::{decode_serverbound, ServerboundMessage};
use super::{
    encode_delta, encode_snapshot, CommittedDamage, DamageSubmissionOutcome, NetworkEncodeError,
    ResourceManifest, RetainedGpu, RetainedGpuFault, ServerboundOutcome, ServerboundRejection,
    SubmissionOutcome,
};

pub const MAX_VIEWERS: usize = 64;
const ACK_DEADLINE_TICKS: u64 = 100;
const DRAIN_BATCH_MAGIC: u32 = 0x4e52_444b;
const DRAIN_BATCH_VERSION: u16 = 1;
const DRAIN_BATCH_HEADER_BYTES: usize = 16;
const DRAIN_BATCH_ENTRY_HEADER_BYTES: usize = 16;

#[derive(Debug, thiserror::Error)]
pub enum RetainedDisplayHostFault {
    #[error("retained display viewer identity is invalid")]
    InvalidViewerIdentity,
    #[error("retained display viewer limit exceeded")]
    ViewerLimitExceeded,
    #[error(transparent)]
    Gpu(#[from] RetainedGpuFault),
    #[error(transparent)]
    Encode(#[from] NetworkEncodeError),
}

struct InFlight {
    target_sequence: u64,
    deadline_tick: u64,
    delivered: bool,
}

struct ViewerSession {
    token: u64,
    computer_id: u32,
    epoch: u64,
    published_manifest: ResourceManifest,
    in_flight: Option<InFlight>,
    pending: Option<CommittedDamage>,
    outbound: Option<Vec<u8>>,
}

pub struct RetainedDisplayHost {
    gpu: RetainedGpu,
    viewers: Vec<ViewerSession>,
    next_epoch: u64,
    current_tick: u64,
}

impl RetainedDisplayHost {
    pub fn try_new() -> Result<Self, RetainedDisplayHostFault> {
        let mut viewers = Vec::new();
        viewers
            .try_reserve_exact(MAX_VIEWERS)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        Ok(Self {
            gpu: RetainedGpu::try_new()?,
            viewers,
            next_epoch: 1,
            current_tick: 0,
        })
    }

    pub fn gpu(&self) -> &RetainedGpu {
        &self.gpu
    }

    pub fn submit(&mut self, packet: &[u8]) -> Result<SubmissionOutcome, RetainedDisplayHostFault> {
        if self.viewers.is_empty() {
            return Ok(self.gpu.submit(packet)?);
        }
        match self.gpu.submit_with_damage(packet)? {
            DamageSubmissionOutcome::Rejected(rejection) => {
                Ok(SubmissionOutcome::Rejected(rejection))
            }
            DamageSubmissionOutcome::Committed { sequence, damage } => {
                for viewer in &mut self.viewers {
                    if let Some(pending) = &mut viewer.pending {
                        pending.try_merge(&damage, &viewer.published_manifest, &self.gpu)?;
                    } else {
                        if damage.base_sequence() != viewer.published_manifest.sequence() {
                            return Err(RetainedGpuFault::CorruptState.into());
                        }
                        viewer.pending = Some(damage.try_clone_metadata()?);
                    }
                }
                Ok(SubmissionOutcome::Committed { sequence })
            }
        }
    }

    pub fn attach_viewer(
        &mut self,
        token: u64,
        computer_id: u32,
    ) -> Result<u64, RetainedDisplayHostFault> {
        if token == 0 || computer_id == 0 {
            return Err(RetainedDisplayHostFault::InvalidViewerIdentity);
        }
        if let Ok(index) = self
            .viewers
            .binary_search_by_key(&token, |viewer| viewer.token)
        {
            self.viewers.remove(index);
        }
        if self.viewers.len() == MAX_VIEWERS {
            return Err(RetainedDisplayHostFault::ViewerLimitExceeded);
        }
        let epoch = self.allocate_epoch()?;
        let manifest = ResourceManifest::try_from_gpu(&self.gpu)?;
        let snapshot = encode_snapshot(computer_id, epoch, &self.gpu)?;
        let deadline_tick = self.deadline_tick()?;
        let index = self
            .viewers
            .binary_search_by_key(&token, |viewer| viewer.token)
            .unwrap_or_else(|index| index);
        self.viewers.insert(
            index,
            ViewerSession {
                token,
                computer_id,
                epoch,
                published_manifest: manifest,
                in_flight: Some(InFlight {
                    target_sequence: self.gpu.commit_sequence(),
                    deadline_tick,
                    delivered: false,
                }),
                pending: None,
                outbound: Some(snapshot),
            },
        );
        Ok(epoch)
    }

    pub fn detach_viewer(&mut self, token: u64) -> bool {
        let Ok(index) = self
            .viewers
            .binary_search_by_key(&token, |viewer| viewer.token)
        else {
            return false;
        };
        self.viewers.remove(index);
        true
    }

    pub fn drain_payload(&mut self, token: u64) -> Option<Vec<u8>> {
        let index = self
            .viewers
            .binary_search_by_key(&token, |viewer| viewer.token)
            .ok()?;
        let viewer = &mut self.viewers[index];
        let payload = viewer.outbound.take()?;
        if let Some(in_flight) = &mut viewer.in_flight {
            in_flight.delivered = true;
        }
        Some(payload)
    }

    pub fn drain_payload_batch(&mut self) -> Result<Option<Vec<u8>>, RetainedDisplayHostFault> {
        let mut payload_count = 0usize;
        let mut total_len = DRAIN_BATCH_HEADER_BYTES;
        for viewer in &self.viewers {
            let Some(payload) = viewer.outbound.as_ref() else {
                continue;
            };
            payload_count = payload_count
                .checked_add(1)
                .ok_or(NetworkEncodeError::LengthOverflow)?;
            total_len = total_len
                .checked_add(DRAIN_BATCH_ENTRY_HEADER_BYTES)
                .and_then(|length| length.checked_add(payload.len()))
                .ok_or(NetworkEncodeError::LengthOverflow)?;
        }
        if payload_count == 0 {
            return Ok(None);
        }
        let total_len_u32 =
            u32::try_from(total_len).map_err(|_| NetworkEncodeError::LengthOverflow)?;
        let payload_count_u32 =
            u32::try_from(payload_count).map_err(|_| NetworkEncodeError::LengthOverflow)?;
        let mut batch = Vec::new();
        batch
            .try_reserve_exact(total_len)
            .map_err(|_| NetworkEncodeError::Allocation)?;
        batch.extend_from_slice(&DRAIN_BATCH_MAGIC.to_le_bytes());
        batch.extend_from_slice(&DRAIN_BATCH_VERSION.to_le_bytes());
        batch.extend_from_slice(&0u16.to_le_bytes());
        batch.extend_from_slice(&total_len_u32.to_le_bytes());
        batch.extend_from_slice(&payload_count_u32.to_le_bytes());
        for viewer in &mut self.viewers {
            let Some(payload) = viewer.outbound.take() else {
                continue;
            };
            let payload_len =
                u32::try_from(payload.len()).map_err(|_| NetworkEncodeError::LengthOverflow)?;
            batch.extend_from_slice(&viewer.token.to_le_bytes());
            batch.extend_from_slice(&payload_len.to_le_bytes());
            batch.extend_from_slice(&0u32.to_le_bytes());
            batch.extend_from_slice(&payload);
            if let Some(in_flight) = &mut viewer.in_flight {
                in_flight.delivered = true;
            }
        }
        debug_assert_eq!(batch.len(), total_len);
        Ok(Some(batch))
    }

    pub fn accept_serverbound(
        &mut self,
        token: u64,
        payload: &[u8],
    ) -> Result<ServerboundOutcome, RetainedDisplayHostFault> {
        let message = match decode_serverbound(payload) {
            Ok(message) => message,
            Err(()) => {
                return Ok(ServerboundOutcome::Rejected(
                    ServerboundRejection::Malformed,
                ));
            }
        };
        let Ok(index) = self
            .viewers
            .binary_search_by_key(&token, |viewer| viewer.token)
        else {
            return Ok(if matches!(message, ServerboundMessage::Resync { .. }) {
                ServerboundOutcome::ReattachRequired
            } else {
                ServerboundOutcome::Rejected(ServerboundRejection::UnknownViewer)
            });
        };
        let viewer = &self.viewers[index];
        let (computer_id, viewer_epoch) = match message {
            ServerboundMessage::Ack {
                computer_id,
                viewer_epoch,
                ..
            }
            | ServerboundMessage::Resync {
                computer_id,
                viewer_epoch,
            } => (computer_id, viewer_epoch),
        };
        if viewer.computer_id != computer_id || viewer.epoch != viewer_epoch {
            return Ok(ServerboundOutcome::Rejected(
                ServerboundRejection::AckMismatch,
            ));
        }
        match message {
            ServerboundMessage::Ack {
                target_sequence, ..
            } => {
                let Some(in_flight) = &viewer.in_flight else {
                    return Ok(ServerboundOutcome::Rejected(
                        ServerboundRejection::AckMismatch,
                    ));
                };
                if !in_flight.delivered || in_flight.target_sequence != target_sequence {
                    return Ok(ServerboundOutcome::Rejected(
                        ServerboundRejection::AckMismatch,
                    ));
                }
                self.viewers[index].in_flight = None;
                Ok(ServerboundOutcome::Acknowledged)
            }
            ServerboundMessage::Resync { .. } => {
                let epoch = self.allocate_epoch()?;
                let manifest = ResourceManifest::try_from_gpu(&self.gpu)?;
                let snapshot = encode_snapshot(computer_id, epoch, &self.gpu)?;
                let deadline_tick = self.deadline_tick()?;
                let viewer = &mut self.viewers[index];
                viewer.epoch = epoch;
                viewer.published_manifest = manifest;
                viewer.pending = None;
                viewer.outbound = Some(snapshot);
                viewer.in_flight = Some(InFlight {
                    target_sequence: self.gpu.commit_sequence(),
                    deadline_tick,
                    delivered: false,
                });
                Ok(ServerboundOutcome::Resynchronized {
                    viewer_epoch: epoch,
                })
            }
        }
    }

    pub fn advance_tick(&mut self) -> Result<(), RetainedDisplayHostFault> {
        self.current_tick = self
            .current_tick
            .checked_add(1)
            .ok_or(RetainedGpuFault::CounterExhausted)?;
        let current_tick = self.current_tick;
        self.viewers.retain(|viewer| {
            viewer
                .in_flight
                .as_ref()
                .is_none_or(|in_flight| current_tick < in_flight.deadline_tick)
        });
        let deadline_tick = self.deadline_tick()?;
        for viewer in &mut self.viewers {
            if viewer.in_flight.is_some() {
                continue;
            }
            let Some(pending) = viewer.pending.take() else {
                continue;
            };
            let payload = encode_delta(
                viewer.computer_id,
                viewer.epoch,
                &viewer.published_manifest,
                &pending,
                &self.gpu,
            )?;
            viewer.published_manifest = ResourceManifest::try_from_gpu(&self.gpu)?;
            viewer.in_flight = Some(InFlight {
                target_sequence: pending.target_sequence(),
                deadline_tick,
                delivered: false,
            });
            viewer.outbound = Some(payload);
        }
        Ok(())
    }

    pub fn viewer_count(&self) -> usize {
        self.viewers.len()
    }

    pub fn pending_descriptor_count(&self) -> usize {
        self.viewers
            .iter()
            .filter_map(|viewer| viewer.pending.as_ref())
            .map(CommittedDamage::descriptor_count)
            .sum()
    }

    fn allocate_epoch(&mut self) -> Result<u64, RetainedDisplayHostFault> {
        if self.next_epoch == u64::MAX {
            return Err(RetainedGpuFault::CounterExhausted.into());
        }
        let epoch = self.next_epoch;
        self.next_epoch += 1;
        Ok(epoch)
    }

    fn deadline_tick(&self) -> Result<u64, RetainedDisplayHostFault> {
        self.current_tick
            .checked_add(ACK_DEADLINE_TICKS)
            .ok_or(RetainedGpuFault::CounterExhausted.into())
    }
}
