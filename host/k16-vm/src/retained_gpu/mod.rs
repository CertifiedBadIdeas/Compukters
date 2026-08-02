mod damage;
mod draw_list;
mod network;
#[allow(dead_code)] // Wired into the transaction engine in the next implementation task.
mod packet;
mod replication;
mod resource;
mod serverbound;
mod transaction;

pub use draw_list::{
    DestinationRect, DrawCommand, DrawList, DrawListValidationError, SourceRect,
    UnresolvedDrawCommand, MAX_CLIP_DEPTH, MAX_DRAW_COMMANDS, MAX_DRAW_LIST_BYTES,
};
pub use network::{encode_delta, encode_snapshot, NetworkEncodeError, MAX_NETWORK_MESSAGE_BYTES};
pub use packet::ResultCode;
pub use replication::{RetainedDisplayHost, RetainedDisplayHostFault, MAX_VIEWERS};
pub use resource::{
    ImageRgb565, Mask1Bpp, MaskInstance, MaskInstanceBuffer, MaskInstanceRecord, Resource,
    ResourceValidationError, MASK_INSTANCE_OPAQUE_BACKGROUND,
};
pub use serverbound::{
    encode_ack, encode_resync_request, ResyncReason, ServerboundOutcome, ServerboundRejection,
};
pub use transaction::{
    DamageSubmissionOutcome, GuestRejection, RetainedGpu, RetainedGpuFault, SubmissionOutcome,
};

pub const DISPLAY_WIDTH: u16 = 320;
pub const DISPLAY_HEIGHT: u16 = 200;
pub const MAX_RESOURCES: usize = 128;
pub const MAX_RESOURCE_BYTES: usize = 131_072;
pub const MAX_TOTAL_RESOURCE_BYTES: usize = 262_144;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ResourceRef {
    pub id: u32,
    pub incarnation: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResourceEntry {
    pub id: u32,
    pub incarnation: u64,
    pub revision: u64,
    pub value: Resource,
}
pub use damage::{
    CommittedDamage, DamageRange, DamageRect, ManifestEntry, ResourceDamage, ResourceKind,
    ResourceManifest,
};
