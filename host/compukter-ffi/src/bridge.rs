use std::sync::{Arc, OnceLock};

use compukter_vm::{
    verify_artifact, AdmissionError, ArtifactLimits, ComputerAdvanceOutcome, ComputerError,
    ComputerMachine, ComputerStartError, ComputerValue, ExecutionProfile, GuestTrap, HostFailure,
    HostResponse, HostValueInput, ManagedAllocationFailure, QuotaExhaustion, ResumeError, RunError,
    TerminalInputError, TerminalKey, TerminalKeyAction, TerminalKeyEvent, TerminalModifiers,
    TerminalSnapshot, TerminalUpdate, VmFault,
};

use crate::handle_table::{HandleError, HandleTable};

static SESSIONS: OnceLock<HandleTable<ComputerMachine>> = OnceLock::new();

#[derive(Debug)]
pub(crate) enum CreateError {
    Verification,
    Admission(AdmissionError),
    Run(RunError),
    Handle(HandleError),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedValue {
    I32(i32),
    I64(i64),
    F32(u32),
    F64(u64),
    Bool(bool),
    Char(u16),
    String(Vec<u16>),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct OwnedRequest {
    pub id: u64,
    pub namespace: String,
    pub name: String,
    pub abi_major: u16,
    pub abi_minor: u16,
    pub operation: u32,
    pub arguments: Vec<OwnedValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedOutcome {
    SliceExhausted,
    HostRequest(OwnedRequest),
    AllocationExhausted(ManagedAllocationFailure),
    QuotaExhausted(QuotaExhaustion),
    Halted(Option<OwnedValue>),
    Crashed(GuestTrap),
    Faulted(VmFault),
    HostFailed(HostFailure),
    WaitingForLine,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedResponse {
    SuccessUnit,
    SuccessString(Vec<u16>),
    Failure(HostFailure),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum BridgeError {
    Handle(HandleError),
    Run(RunError),
    Resume(ResumeError),
    InvalidRequestId,
    InvalidOperation,
}

pub(crate) fn create(artifact_bytes: Vec<u8>) -> Result<u64, CreateError> {
    let artifact = verify_artifact(Arc::from(artifact_bytes), ArtifactLimits::default())
        .map_err(|_| CreateError::Verification)?;
    let computer =
        ComputerMachine::start(artifact, profile(), &[], &[]).map_err(|error| match error {
            ComputerStartError::Admission(error) => CreateError::Admission(error),
            ComputerStartError::Start(error) => CreateError::Run(error),
        })?;
    sessions().insert(computer).map_err(CreateError::Handle)
}

pub(crate) fn advance(
    handle: u64,
    guest_budget: u32,
    maintenance_budget: u32,
) -> Result<OwnedOutcome, BridgeError> {
    sessions()
        .with(handle, |computer| {
            computer
                .advance(guest_budget, maintenance_budget)
                .map(copy_outcome)
                .map_err(copy_error)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn resume(
    handle: u64,
    request_id: u64,
    response: &OwnedResponse,
) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |computer| {
            let borrowed = match response {
                OwnedResponse::SuccessUnit => HostResponse::Success(HostValueInput::Unit),
                OwnedResponse::SuccessString(units) => {
                    HostResponse::Success(HostValueInput::String(units))
                }
                OwnedResponse::Failure(failure) => HostResponse::Failure(*failure),
            };
            computer
                .resume_host_request(request_id, borrowed)
                .map_err(copy_error)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn terminal_commit(handle: u64) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |computer| {
            computer.terminal_mut().commit();
        })
        .map_err(BridgeError::Handle)
}

pub(crate) fn terminal_full_state(handle: u64) -> Result<TerminalSnapshot, BridgeError> {
    sessions()
        .with(handle, |computer| {
            match computer.terminal().changes_since(u64::MAX) {
                TerminalUpdate::Full(snapshot) => snapshot,
                _ => unreachable!("future revision always requests a full terminal state"),
            }
        })
        .map_err(BridgeError::Handle)
}

pub(crate) fn terminal_changes_since(
    handle: u64,
    revision: u64,
) -> Result<TerminalUpdate, BridgeError> {
    sessions()
        .with(handle, |computer| {
            computer.terminal().changes_since(revision)
        })
        .map_err(BridgeError::Handle)
}

pub(crate) fn terminal_key(
    handle: u64,
    key: u16,
    action: u32,
    modifiers: u32,
) -> Result<(), BridgeError> {
    let key = TerminalKey::try_from(key).map_err(|_| BridgeError::InvalidOperation)?;
    let action = match action {
        0 => TerminalKeyAction::Press,
        1 => TerminalKeyAction::Repeat,
        _ => return Err(BridgeError::InvalidOperation),
    };
    let modifiers = u8::try_from(modifiers)
        .ok()
        .and_then(|value| TerminalModifiers::new(value).ok())
        .ok_or(BridgeError::InvalidOperation)?;
    sessions()
        .with(handle, |computer| {
            computer
                .terminal_mut()
                .push_key(TerminalKeyEvent::new(key, action, modifiers))
        })
        .map_err(BridgeError::Handle)?
        .map_err(copy_input_error)
}

pub(crate) fn terminal_text(handle: u64, text: &str) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |computer| computer.terminal_mut().push_text(text))
        .map_err(BridgeError::Handle)?
        .map_err(copy_input_error)
}

pub(crate) fn terminal_compatibility_line(handle: u64, units: &[u16]) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |computer| {
            computer
                .provide_compatibility_line(units)
                .map_err(copy_error)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn close(handle: u64) -> Result<(), BridgeError> {
    sessions().close(handle).map_err(BridgeError::Handle)
}

fn sessions() -> &'static HandleTable<ComputerMachine> {
    SESSIONS.get_or_init(HandleTable::default)
}

fn copy_outcome(outcome: ComputerAdvanceOutcome) -> OwnedOutcome {
    match outcome {
        ComputerAdvanceOutcome::SliceExhausted => OwnedOutcome::SliceExhausted,
        ComputerAdvanceOutcome::WaitingForLine => OwnedOutcome::WaitingForLine,
        ComputerAdvanceOutcome::HostRequest(request) => OwnedOutcome::HostRequest(OwnedRequest {
            id: request.id,
            namespace: request.namespace.into(),
            name: request.name.into(),
            abi_major: request.abi_major,
            abi_minor: request.abi_minor,
            operation: request.operation,
            arguments: request
                .arguments
                .into_vec()
                .into_iter()
                .map(copy_value)
                .collect(),
        }),
        ComputerAdvanceOutcome::AllocationExhausted(value) => {
            OwnedOutcome::AllocationExhausted(value)
        }
        ComputerAdvanceOutcome::QuotaExhausted(value) => OwnedOutcome::QuotaExhausted(value),
        ComputerAdvanceOutcome::Halted(value) => OwnedOutcome::Halted(value.map(copy_value)),
        ComputerAdvanceOutcome::Crashed(value) => OwnedOutcome::Crashed(value),
        ComputerAdvanceOutcome::Faulted(value) => OwnedOutcome::Faulted(value),
        ComputerAdvanceOutcome::HostFailed(value) => OwnedOutcome::HostFailed(value),
    }
}

fn copy_value(value: ComputerValue) -> OwnedValue {
    match value {
        ComputerValue::I32(value) => OwnedValue::I32(value),
        ComputerValue::I64(value) => OwnedValue::I64(value),
        ComputerValue::F32(value) => OwnedValue::F32(value),
        ComputerValue::F64(value) => OwnedValue::F64(value),
        ComputerValue::Bool(value) => OwnedValue::Bool(value),
        ComputerValue::Char(value) => OwnedValue::Char(value),
        ComputerValue::String(value) => OwnedValue::String(value.into_vec()),
    }
}

fn copy_error(error: ComputerError) -> BridgeError {
    match error {
        ComputerError::Run(error) => BridgeError::Run(error),
        ComputerError::Resume(error) => BridgeError::Resume(error),
        ComputerError::InvalidRequestId => BridgeError::InvalidRequestId,
        ComputerError::InvalidTerminalRequest | ComputerError::NoPendingCompatibilityLine => {
            BridgeError::InvalidOperation
        }
    }
}

fn copy_input_error(_error: TerminalInputError) -> BridgeError {
    BridgeError::InvalidOperation
}

fn profile() -> ExecutionProfile {
    ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_host_requests: 64,
        maximum_events: 64,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        standard_library_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn terminal_artifact() -> Vec<u8> {
        let encoded = include_str!("../../compukter-vm/tests/fixtures/terminal-session.hex")
            .trim()
            .as_bytes();
        encoded
            .chunks_exact(2)
            .map(|pair| {
                let digit = |value| match value {
                    b'0'..=b'9' => value - b'0',
                    b'a'..=b'f' => value - b'a' + 10,
                    _ => panic!("invalid fixture hex"),
                };
                (digit(pair[0]) << 4) | digit(pair[1])
            })
            .collect()
    }

    fn advance_until_waiting(handle: u64) {
        loop {
            match advance(handle, 64, 64).unwrap() {
                OwnedOutcome::SliceExhausted => {}
                OwnedOutcome::WaitingForLine => return,
                other => panic!("unexpected outcome: {other:?}"),
            }
        }
    }

    #[test]
    fn computer_consumes_terminal_requests_and_retains_state() {
        let handle = create(terminal_artifact()).unwrap();
        advance_until_waiting(handle);
        terminal_commit(handle).unwrap();
        let state = terminal_full_state(handle).unwrap();
        assert_eq!('>' as u32, state.cells()[0].code_point());
        let input = vec![0x0041, 0xd800, 0x0100, 0xdc00, 0x0042];
        terminal_compatibility_line(handle, &input).unwrap();
        close(handle).unwrap();
        assert!(matches!(
            advance(handle, 64, 64),
            Err(BridgeError::Handle(HandleError::Stale))
        ));
    }
}
