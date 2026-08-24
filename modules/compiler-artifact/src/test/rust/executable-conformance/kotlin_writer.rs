use std::{fs, sync::Arc};

use compukter_vm::{
    verify_artifact, AdvanceOutcome, ArtifactLimits, CapabilityBinding, ExecutionProfile,
    HostResponse, HostValueInput, HostValueType, HostValueView, OperationSchema, Session,
};

#[test]
fn pinned_vm_verifies_kotlin_executable_instruction_artifact() {
    let path = std::env::var("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT")
        .expect("Gradle conformance task must provide the generated Kotlin artifact");
    let bytes = fs::read(path).expect("Kotlin writer output must exist");

    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must decode and verify Kotlin writer output");

    assert_eq!(verified.module_count(), 2);
}

#[test]
fn k2_char_array_program_executes_exact_utf16_materialization() {
    let Ok(path) = std::env::var("COMPUKTER_KOTLIN_SUBSET_ARTIFACT") else {
        return;
    };
    let bytes = fs::read(path).expect("K2 subset output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 subset output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        standard_library_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
    };
    let mut session = Session::admit(verified, profile, &[binding]).expect("K2 subset must admit");
    session.start(&[]).expect("K2 subset must start");

    let request_id = loop {
        match session.advance(64, 64).expect("K2 subset must advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequest(request) => {
                assert_eq!("compukter", request.namespace());
                assert_eq!("terminal", request.name());
                assert_eq!(0, request.operation());
                assert_eq!(
                    Some(HostValueView::String(&[0x41, 0xd83d, 0xde00, 0x5a])),
                    request.arguments().get(0),
                );
                break request.id();
            }
            outcome => panic!("unexpected K2 subset outcome before terminal write: {outcome:?}"),
        }
    };
    session
        .resume(request_id, HostResponse::Success(HostValueInput::Unit))
        .expect("terminal write must resume");
    loop {
        match session.advance(64, 64).expect("K2 subset must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 subset outcome after terminal write: {outcome:?}"),
        }
    }
}
