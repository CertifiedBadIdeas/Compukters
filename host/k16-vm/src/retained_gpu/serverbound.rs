use super::network::{NetworkEncodeError, NETWORK_MAGIC, NETWORK_VERSION};

const ACK_KIND: u16 = 3;
const RESYNC_KIND: u16 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum ResyncReason {
    BaseMismatch = 1,
    ReplicaStateLost = 2,
    RenderResourceLost = 3,
    MessageValidationFailed = 4,
    AtomicInstallFailed = 5,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServerboundRejection {
    UnknownViewer,
    Malformed,
    AckMismatch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServerboundOutcome {
    Acknowledged,
    Resynchronized { viewer_epoch: u64 },
    ReattachRequired,
    Rejected(ServerboundRejection),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum ServerboundMessage {
    Ack {
        computer_id: u32,
        viewer_epoch: u64,
        target_sequence: u64,
    },
    Resync {
        computer_id: u32,
        viewer_epoch: u64,
    },
}

pub fn encode_ack(
    computer_id: u32,
    viewer_epoch: u64,
    target_sequence: u64,
) -> Result<Vec<u8>, NetworkEncodeError> {
    let mut bytes = encode_header(ACK_KIND, 32, computer_id, viewer_epoch)?;
    push_u64(&mut bytes, target_sequence);
    Ok(bytes)
}

pub fn encode_resync_request(
    computer_id: u32,
    viewer_epoch: u64,
    current_sequence: Option<u64>,
    reason: ResyncReason,
) -> Result<Vec<u8>, NetworkEncodeError> {
    let mut bytes = encode_header(RESYNC_KIND, 40, computer_id, viewer_epoch)?;
    push_u64(&mut bytes, current_sequence.unwrap_or(0));
    push_u16(&mut bytes, reason as u16);
    push_u16(&mut bytes, u16::from(current_sequence.is_some()));
    push_u32(&mut bytes, 0);
    Ok(bytes)
}

pub(super) fn decode_serverbound(bytes: &[u8]) -> Result<ServerboundMessage, ()> {
    if bytes.len() < 24
        || read_u32(bytes, 0) != NETWORK_MAGIC
        || read_u16(bytes, 4) != NETWORK_VERSION
        || read_u32(bytes, 8) as usize != bytes.len()
    {
        return Err(());
    }
    let kind = read_u16(bytes, 6);
    let computer_id = read_u32(bytes, 12);
    let viewer_epoch = read_u64(bytes, 16);
    if computer_id == 0 || viewer_epoch == 0 {
        return Err(());
    }
    match kind {
        ACK_KIND if bytes.len() == 32 => Ok(ServerboundMessage::Ack {
            computer_id,
            viewer_epoch,
            target_sequence: read_u64(bytes, 24),
        }),
        RESYNC_KIND if bytes.len() == 40 => {
            let current_sequence = read_u64(bytes, 24);
            let reason = read_u16(bytes, 32);
            let flags = read_u16(bytes, 34);
            let reserved = read_u32(bytes, 36);
            if !(1..=5).contains(&reason)
                || flags & !1 != 0
                || reserved != 0
                || (flags & 1 == 0 && current_sequence != 0)
            {
                return Err(());
            }
            Ok(ServerboundMessage::Resync {
                computer_id,
                viewer_epoch,
            })
        }
        _ => Err(()),
    }
}

fn encode_header(
    kind: u16,
    total_len: u32,
    computer_id: u32,
    viewer_epoch: u64,
) -> Result<Vec<u8>, NetworkEncodeError> {
    if computer_id == 0 || viewer_epoch == 0 {
        return Err(NetworkEncodeError::InvalidIdentity);
    }
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(total_len as usize)
        .map_err(|_| NetworkEncodeError::Allocation)?;
    push_u32(&mut bytes, NETWORK_MAGIC);
    push_u16(&mut bytes, NETWORK_VERSION);
    push_u16(&mut bytes, kind);
    push_u32(&mut bytes, total_len);
    push_u32(&mut bytes, computer_id);
    push_u64(&mut bytes, viewer_epoch);
    Ok(bytes)
}

fn read_u16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("checked field"))
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("checked field"))
}

fn push_u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
