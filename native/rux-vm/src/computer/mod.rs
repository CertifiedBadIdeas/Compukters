pub(crate) mod devices;
pub mod handle;
pub mod machine;
pub mod profile;
pub mod snapshot;

pub use devices::ComputerTextDisplaySnapshot;
pub use handle::{RuxComputerControl, RuxComputerHandle, RuxComputerTextDisplaySnapshot};
pub use machine::{
    BootHandoffError, ComputerMachine, ComputerMemoryMap, ComputerMemoryRegion, CpuId,
};
pub use profile::{ComputerHardwareConfig, ComputerMachineProfile};
pub use snapshot::{
    decode_snapshot_v1, ComputerCpuSnapshotRecord, ComputerDeviceSnapshotRecord,
    ComputerMachineSnapshot, ComputerMachineSnapshotHeader, COMPUTER_SNAPSHOT_V1_HEADER_SIZE,
    COMPUTER_SNAPSHOT_V1_MAGIC, COMPUTER_SNAPSHOT_V1_RUX16_CPU_RECORD_SIZE,
};
