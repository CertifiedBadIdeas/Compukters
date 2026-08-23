use std::sync::{Arc, OnceLock};

use compukter_vm::{
    verify_artifact, AdmissionError, AdvanceOutcome, ArtifactLimits, CapabilityBinding,
    ExecutionProfile, GuestTrap, HostFailure, HostResponse, HostValueInput, HostValueType,
    HostValueView, ManagedAllocationFailure, OperationSchema, QuotaExhaustion, RequestId,
    ResumeError, RunError, Session, VmFault,
};

use crate::handle_table::{HandleError, HandleTable};

static SESSIONS: OnceLock<HandleTable<Session>> = OnceLock::new();

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
}

pub(crate) fn create(artifact_bytes: Vec<u8>) -> Result<u64, CreateError> {
    let artifact = verify_artifact(Arc::from(artifact_bytes), ArtifactLimits::default())
        .map_err(|_| CreateError::Verification)?;
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&string_argument, HostValueType::Unit),
        OperationSchema::asynchronous(&string_argument, HostValueType::Unit),
        OperationSchema::asynchronous(&[], HostValueType::String),
    ];
    let terminal = CapabilityBinding::new("compukter", "terminal", 1, 0, &operations);
    let mut session =
        Session::admit(artifact, profile(), &[terminal]).map_err(CreateError::Admission)?;
    session.start(&[]).map_err(CreateError::Run)?;
    sessions().insert(session).map_err(CreateError::Handle)
}

pub(crate) fn advance(
    handle: u64,
    guest_budget: u32,
    maintenance_budget: u32,
) -> Result<OwnedOutcome, BridgeError> {
    sessions()
        .with(handle, |session| {
            session
                .advance(guest_budget, maintenance_budget)
                .map(copy_outcome)
                .map_err(BridgeError::Run)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn resume(
    handle: u64,
    request_id: u64,
    response: &OwnedResponse,
) -> Result<(), BridgeError> {
    let request_id = RequestId::new(request_id).ok_or(BridgeError::InvalidRequestId)?;
    sessions()
        .with(handle, |session| {
            let borrowed = match response {
                OwnedResponse::SuccessUnit => HostResponse::Success(HostValueInput::Unit),
                OwnedResponse::SuccessString(units) => {
                    HostResponse::Success(HostValueInput::String(units))
                }
                OwnedResponse::Failure(failure) => HostResponse::Failure(*failure),
            };
            session
                .resume(request_id, borrowed)
                .map_err(BridgeError::Resume)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn close(handle: u64) -> Result<(), BridgeError> {
    sessions().close(handle).map_err(BridgeError::Handle)
}

fn sessions() -> &'static HandleTable<Session> {
    SESSIONS.get_or_init(HandleTable::default)
}

fn copy_outcome(outcome: AdvanceOutcome<'_>) -> OwnedOutcome {
    match outcome {
        AdvanceOutcome::SliceExhausted => OwnedOutcome::SliceExhausted,
        AdvanceOutcome::HostRequest(request) => {
            let arguments = (0..request.arguments().len())
                .filter_map(|index| request.arguments().get(index).map(copy_value))
                .collect();
            OwnedOutcome::HostRequest(OwnedRequest {
                id: request.id().get(),
                namespace: request.namespace().to_owned(),
                name: request.name().to_owned(),
                abi_major: request.abi_major(),
                abi_minor: request.abi_minor(),
                operation: request.operation(),
                arguments,
            })
        }
        AdvanceOutcome::AllocationExhausted(value) => OwnedOutcome::AllocationExhausted(value),
        AdvanceOutcome::QuotaExhausted(value) => OwnedOutcome::QuotaExhausted(value),
        AdvanceOutcome::Halted(value) => OwnedOutcome::Halted(value.map(copy_value)),
        AdvanceOutcome::Crashed(value) => OwnedOutcome::Crashed(value),
        AdvanceOutcome::Faulted(value) => OwnedOutcome::Faulted(value),
        AdvanceOutcome::HostFailed(value) => OwnedOutcome::HostFailed(value),
    }
}

fn copy_value(value: HostValueView<'_>) -> OwnedValue {
    match value {
        HostValueView::I32(value) => OwnedValue::I32(value),
        HostValueView::I64(value) => OwnedValue::I64(value),
        HostValueView::F32(value) => OwnedValue::F32(value),
        HostValueView::F64(value) => OwnedValue::F64(value),
        HostValueView::Bool(value) => OwnedValue::Bool(value),
        HostValueView::Char(value) => OwnedValue::Char(value),
        HostValueView::String(value) => OwnedValue::String(value.to_vec()),
    }
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

    fn next_request(handle: u64) -> OwnedRequest {
        loop {
            match advance(handle, 64, 64).unwrap() {
                OwnedOutcome::SliceExhausted => {}
                OwnedOutcome::HostRequest(request) => return request,
                other => panic!("unexpected outcome: {other:?}"),
            }
        }
    }

    #[test]
    fn session_requests_are_copied_and_utf16_responses_resume() {
        let handle = create(terminal_artifact()).unwrap();
        let output = vec![0x003e, 0x0020, 0xd83d, 0xde00];
        for operation in [0, 1] {
            let request = next_request(handle);
            assert_eq!(operation, request.operation);
            assert_eq!(vec![OwnedValue::String(output.clone())], request.arguments);
            resume(handle, request.id, &OwnedResponse::SuccessUnit).unwrap();
        }
        let request = next_request(handle);
        assert_eq!(2, request.operation);
        let input = vec![0x0041, 0xd800, 0x0100, 0xdc00, 0x0042];
        resume(handle, request.id, &OwnedResponse::SuccessString(input)).unwrap();
        close(handle).unwrap();
        assert!(matches!(
            advance(handle, 64, 64),
            Err(BridgeError::Handle(HandleError::Stale))
        ));
    }

    #[test]
    fn resume_rejects_zero_and_wrong_request_ids() {
        let handle = create(terminal_artifact()).unwrap();
        let request = next_request(handle);
        assert_eq!(
            Err(BridgeError::InvalidRequestId),
            resume(handle, 0, &OwnedResponse::SuccessUnit)
        );
        assert_eq!(
            Err(BridgeError::Resume(ResumeError::WrongRequestId)),
            resume(handle, request.id + 1, &OwnedResponse::SuccessUnit),
        );
        close(handle).unwrap();
    }

    #[test]
    fn copied_host_failure_is_a_typed_terminal_outcome() {
        let handle = create(terminal_artifact()).unwrap();
        let request = next_request(handle);
        let failure = HostFailure::new(compukter_vm::HostFailureKind::EndOfFile, 17);
        resume(handle, request.id, &OwnedResponse::Failure(failure)).unwrap();

        assert_eq!(
            OwnedOutcome::HostFailed(failure),
            advance(handle, 64, 64).unwrap()
        );
        close(handle).unwrap();
    }
}
