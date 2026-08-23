use compukter_vm::{
    AdmissionError, HostFailureKind, QuotaKind, RunError, TerminalCell, TerminalChange,
    TerminalSnapshot, TerminalUpdate,
};

use crate::bridge::{CreateError, OwnedOutcome, OwnedRequest, OwnedValue};
use crate::handle_table::HandleError;

pub(crate) fn encode_create(outcome: Result<u64, CreateError>) -> Vec<u8> {
    let encoder = match outcome {
        Ok(handle) => {
            let mut encoder = Encoder::new(0);
            encoder.u64(handle);
            return encoder.finish();
        }
        Err(CreateError::Verification) => Encoder::new(1),
        Err(CreateError::Admission(error)) => {
            let mut encoder = Encoder::new(2);
            encoder.u16(admission_code(error));
            encoder
        }
        Err(CreateError::Run(error)) => {
            let mut encoder = Encoder::new(3);
            encoder.u16(run_code(error));
            encoder
        }
        Err(CreateError::Handle(error)) => {
            let mut encoder = Encoder::new(4);
            encoder.u8(handle_code(error));
            encoder
        }
    };
    encoder.finish()
}

pub(crate) fn encode_outcome(outcome: OwnedOutcome) -> Vec<u8> {
    let encoder = match outcome {
        OwnedOutcome::SliceExhausted => Encoder::new(0),
        OwnedOutcome::HostRequest(request) => {
            let mut encoder = Encoder::new(1);
            encoder.request(&request);
            encoder
        }
        OwnedOutcome::AllocationExhausted(value) => {
            let mut encoder = Encoder::new(2);
            encoder.u8(u8::from(value.collection_attempted));
            encoder
        }
        OwnedOutcome::QuotaExhausted(value) => {
            let mut encoder = Encoder::new(3);
            encoder.u8(match value.kind {
                QuotaKind::HostRequestCodeUnits => 0,
                QuotaKind::HostRequests => 1,
                QuotaKind::AcceptedResponses => 2,
            });
            encoder.u64(value.limit);
            encoder.u64(value.consumed);
            encoder
        }
        OwnedOutcome::Halted(value) => {
            let mut encoder = Encoder::new(4);
            match value {
                Some(value) => {
                    encoder.u8(1);
                    encoder.value(&value);
                }
                None => encoder.u8(0),
            }
            encoder
        }
        OwnedOutcome::Crashed(value) => {
            let mut encoder = Encoder::new(5);
            encoder.u8(value as u8);
            encoder
        }
        OwnedOutcome::Faulted(value) => {
            let mut encoder = Encoder::new(6);
            encoder.u8(value as u8);
            encoder
        }
        OwnedOutcome::HostFailed(value) => {
            let mut encoder = Encoder::new(7);
            encoder.u8(host_failure_code(value.kind()));
            encoder.u32(value.code());
            encoder
        }
        OwnedOutcome::WaitingForTerminalEvent => Encoder::new(9),
    };
    encoder.finish()
}

pub(crate) fn encode_terminal_full(snapshot: TerminalSnapshot) -> Vec<u8> {
    let mut encoder = Encoder::new(2);
    encoder.u64(snapshot.revision());
    encoder.u16(compukter_vm::TERMINAL_WIDTH);
    encoder.u16(compukter_vm::TERMINAL_HEIGHT);
    encoder.u32(u32::try_from(snapshot.cells().len()).expect("terminal cell count fits u32"));
    for cell in snapshot.cells() {
        encoder.cell(*cell);
    }
    encoder.u16(snapshot.cursor_position().x());
    encoder.u16(snapshot.cursor_position().y());
    encoder.u8(u8::from(snapshot.cursor_visible()));
    encoder.finish()
}

pub(crate) fn encode_terminal_update(update: TerminalUpdate) -> Vec<u8> {
    match update {
        TerminalUpdate::Unchanged { revision } => {
            let mut encoder = Encoder::new(0);
            encoder.u64(revision);
            encoder.finish()
        }
        TerminalUpdate::Delta(delta) => {
            let mut encoder = Encoder::new(1);
            encoder.u64(delta.base_revision());
            encoder.u64(delta.target_revision());
            encoder
                .u32(u32::try_from(delta.changes().len()).expect("terminal change count fits u32"));
            for change in delta.changes() {
                encoder.change(change);
            }
            encoder.finish()
        }
        TerminalUpdate::Full(snapshot) => encode_terminal_full(snapshot),
    }
}

struct Encoder {
    bytes: Vec<u8>,
}

impl Encoder {
    fn new(tag: u8) -> Self {
        Self { bytes: vec![tag] }
    }

    fn finish(self) -> Vec<u8> {
        self.bytes
    }

    fn u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn u16(&mut self, value: u16) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u32(&mut self, value: u32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u64(&mut self, value: u64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn bytes(&mut self, value: &[u8]) {
        self.u32(u32::try_from(value.len()).expect("bounded bridge value length fits u32"));
        self.bytes.extend_from_slice(value);
    }

    fn request(&mut self, request: &OwnedRequest) {
        self.u64(request.id);
        self.bytes(request.namespace.as_bytes());
        self.bytes(request.name.as_bytes());
        self.u16(request.abi_major);
        self.u16(request.abi_minor);
        self.u32(request.operation);
        self.u32(
            u32::try_from(request.arguments.len()).expect("bounded host argument count fits u32"),
        );
        for argument in &request.arguments {
            self.value(argument);
        }
    }

    fn value(&mut self, value: &OwnedValue) {
        match value {
            OwnedValue::I32(value) => {
                self.u8(1);
                self.bytes.extend_from_slice(&value.to_le_bytes());
            }
            OwnedValue::I64(value) => {
                self.u8(2);
                self.bytes.extend_from_slice(&value.to_le_bytes());
            }
            OwnedValue::F32(value) => {
                self.u8(3);
                self.u32(*value);
            }
            OwnedValue::F64(value) => {
                self.u8(4);
                self.u64(*value);
            }
            OwnedValue::Bool(value) => {
                self.u8(5);
                self.u8(u8::from(*value));
            }
            OwnedValue::Char(value) => {
                self.u8(6);
                self.u16(*value);
            }
            OwnedValue::String(value) => {
                self.u8(7);
                self.u32(u32::try_from(value.len()).expect("bounded UTF-16 length fits u32"));
                for unit in value {
                    self.u16(*unit);
                }
            }
        }
    }

    fn cell(&mut self, cell: TerminalCell) {
        self.u32(cell.code_point());
        self.u8(cell.foreground());
        self.u8(cell.background());
    }

    fn change(&mut self, change: &TerminalChange) {
        match change {
            TerminalChange::Patch { start, cells } => {
                self.u8(0);
                self.u16(*start);
                self.u16(u16::try_from(cells.len()).expect("terminal patch length fits u16"));
                for cell in cells {
                    self.cell(*cell);
                }
            }
            TerminalChange::Fill {
                x,
                y,
                width,
                height,
                cell,
            } => {
                self.u8(1);
                self.u16(*x);
                self.u16(*y);
                self.u16(*width);
                self.u16(*height);
                self.cell(*cell);
            }
            TerminalChange::Scroll { rows, fill } => {
                self.u8(2);
                self.u16(*rows);
                self.cell(*fill);
            }
            TerminalChange::Cursor { position, visible } => {
                self.u8(3);
                self.u16(position.x());
                self.u16(position.y());
                self.u8(u8::from(*visible));
            }
            TerminalChange::Reset => self.u8(4),
        }
    }
}

fn admission_code(error: AdmissionError) -> u16 {
    match error {
        AdmissionError::CompilerAbiMismatch => 0,
        AdmissionError::StandardLibraryAbiMismatch => 1,
        AdmissionError::MissingCapability { .. } => 2,
        AdmissionError::HeapLimit { .. } => 3,
        AdmissionError::InvalidHeapSize { .. } => 4,
        AdmissionError::FrameStorageLimit { .. } => 5,
        AdmissionError::CallDepthLimit { .. } => 6,
        AdmissionError::CoroutineLimit { .. } => 7,
        AdmissionError::HostRequestLimit { .. } => 8,
        AdmissionError::EventLimit { .. } => 9,
        AdmissionError::SliceLimit { .. } => 10,
        AdmissionError::StoragePlanOverflow => 11,
        AdmissionError::AllocationFailed => 12,
        AdmissionError::InvalidEntry => 13,
        AdmissionError::DuplicateCapabilityBinding => 14,
        AdmissionError::CapabilityOperationCount { .. } => 15,
        AdmissionError::CapabilitySchema { .. } => 16,
        AdmissionError::SynchronousCapabilityUnsupported => 17,
    }
}

fn run_code(error: RunError) -> u16 {
    match error {
        RunError::AlreadyStarted => 0,
        RunError::NotStarted => 1,
        RunError::NotRunnable => 2,
        RunError::InvalidSliceBudget { .. } => 3,
        RunError::EntryArity { .. } => 4,
        RunError::EntryType { .. } => 5,
        RunError::ForeignReference { .. } => 6,
        RunError::DeadReference { .. } => 7,
    }
}

pub(crate) fn handle_code(error: HandleError) -> u8 {
    match error {
        HandleError::Invalid => 0,
        HandleError::Stale => 1,
        HandleError::Busy => 2,
        HandleError::Exhausted => 3,
        HandleError::Poisoned => 4,
    }
}

pub(crate) fn host_failure_code(kind: HostFailureKind) -> u8 {
    match kind {
        HostFailureKind::EndOfFile => 0,
        HostFailureKind::Unavailable => 1,
        HostFailureKind::InputOutput => 2,
        HostFailureKind::Cancelled => 3,
        HostFailureKind::Other => 4,
    }
}

#[cfg(test)]
mod tests {
    use compukter_vm::{GuestTrap, QuotaExhaustion, VmFault};

    use super::*;

    #[test]
    fn create_handle_has_a_fixed_little_endian_wire_form() {
        assert_eq!(
            vec![0, 8, 7, 6, 5, 4, 3, 2, 1],
            encode_create(Ok(0x0102_0304_0506_0708)),
        );
    }

    #[test]
    fn request_wire_form_owns_exact_utf16_code_units() {
        let outcome = OwnedOutcome::HostRequest(OwnedRequest {
            id: 9,
            namespace: "c".to_owned(),
            name: "t".to_owned(),
            abi_major: 1,
            abi_minor: 0,
            operation: 2,
            arguments: vec![OwnedValue::String(vec![0x0041, 0xd800, 0xdc00])],
        });

        assert_eq!(
            vec![
                1, 9, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, b'c', 1, 0, 0, 0, b't', 1, 0, 0, 0, 2, 0, 0,
                0, 1, 0, 0, 0, 7, 3, 0, 0, 0, 0x41, 0x00, 0x00, 0xd8, 0x00, 0xdc,
            ],
            encode_outcome(outcome),
        );
    }

    #[test]
    fn terminal_failure_variants_have_stable_scalar_wire_forms() {
        assert_eq!(
            vec![5, 0],
            encode_outcome(OwnedOutcome::Crashed(GuestTrap::DivisionByZero))
        );
        assert_eq!(
            vec![6, 7],
            encode_outcome(OwnedOutcome::Faulted(VmFault::HandleExhausted))
        );
        assert_eq!(
            vec![3, 1, 4, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0],
            encode_outcome(OwnedOutcome::QuotaExhausted(QuotaExhaustion {
                kind: QuotaKind::HostRequests,
                limit: 4,
                consumed: 3,
            })),
        );
    }

    #[test]
    fn terminal_event_wait_has_a_distinct_stable_wire_tag() {
        assert_eq!(
            vec![9],
            encode_outcome(OwnedOutcome::WaitingForTerminalEvent)
        );
    }
}
