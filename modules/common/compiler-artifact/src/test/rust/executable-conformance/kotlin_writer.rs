use std::{fs, sync::Arc};

use compukter_vm::{
    verify_artifact, AdvanceOutcome, ArtifactLimits, CapabilityBinding, EntryArgumentLimits,
    EntryValue, ExecutionProfile, GuestTrap, HostResponse, HostValueInput, HostValueType,
    HostValueView, OperationSchema, RequestId, Session, TaskId,
};

fn main() {
    let mut arguments = std::env::args().skip(1);
    let scenario = arguments
        .next()
        .expect("a Kotlin-to-VM conformance scenario argument is required");
    assert!(
        arguments.next().is_none(),
        "exactly one Kotlin-to-VM conformance scenario argument is required"
    );

    match scenario.as_str() {
        "executable" => pinned_vm_verifies_kotlin_executable_instruction_artifact(),
        "int-array" => k2_int_array_executes_specialized_storage_and_traps(),
        "nullable-references" => k2_nullable_references_preserve_branch_and_call_semantics(),
        "int-loops" => k2_int_loops_execute_across_quota_slices_without_host_io(),
        "long" => k2_long_executes_arithmetic_conversions_comparisons_and_text(),
        "generic-functions" => k2_generic_functions_preserve_primitive_and_reference_values(),
        "generic-cell" => k2_generic_cell_preserves_typed_fields_and_aliases(),
        "generic-interface" => k2_generic_interface_dispatches_concrete_types(),
        "list" => k2_lists_retain_typed_elements(),
        "list-any" => k2_int_list_covariance_boxes_universal_reads(),
        "list-any-quota" => k2_int_list_universal_reads_survive_quota_and_gc(),
        "list-bounds" => k2_list_index_outside_bounds_traps(),
        "list-quota" => k2_list_iterator_resumes_across_quota_slices(),
        "generic-library" => k2_generic_library_specializes_in_consumer(),
        "reference-array" => k2_reference_arrays_retain_typed_guest_objects(),
        "float" => k2_float_executes_arithmetic_conversions_comparisons_and_text(),
        "string-compare" => k2_string_compare_uses_utf16_code_units(),
        "scalar-compare" => k2_char_and_boolean_compare_preserve_ordering(),
        "platform-scalar" => k2_platform_scalar_precondition_traps_before_publishing_a_value(),
        "argv" => k2_string_array_entry_executes_exact_utf16_arguments(),
        "subset" => k2_string_materialization_executes_char_arrays_and_scalar_templates(),
        "named-calls" => k2_same_named_guest_calls_preserve_resolved_targets(),
        "dispatch" => k2_class_and_interface_dispatch_select_runtime_implementations(),
        "object-model" => k2_sealed_data_and_enum_objects_execute(),
        "property-accessors" => k2_property_accessors_preserve_backing_and_dispatch(),
        "abstract-properties" => k2_abstract_properties_dispatch_through_base_and_interface(),
        "interface-defaults" => k2_interface_defaults_preserve_override_precedence(),
        "interface-super" => k2_interface_super_calls_direct_default_bodies(),
        "mutable-fields" => k2_mutable_class_fields_preserve_aliases_and_evaluation_order(),
        "class-initialization" => k2_class_initialization_preserves_source_and_super_order(),
        "constructor-defaults" => k2_constructor_defaults_preserve_kotlin_argument_order(),
        "adapted-constructors" => k2_adapted_constructor_references_preserve_defaults_and_identity(),
        "function-values" => k2_function_values_preserve_distinct_captures_and_dispatch(),
        "transparent-call" => k2_ordinary_project_call_resumes_across_async_capability(),
        "tasks" => k2_tasks_keep_independent_host_requests_in_flight(),
        "channel" => k2_channel_handoff_stays_inside_the_vm(),
        "timer" => k2_timer_sleep_publishes_one_bounded_request(),
        "when" => k2_bounded_when_selects_matched_and_fallback_branches(),
        _ => panic!("unknown Kotlin-to-VM conformance scenario: {scenario}"),
    }

    println!("Kotlin-to-VM conformance scenario passed: {scenario}");
}

fn k2_abstract_properties_dispatch_through_base_and_interface() {
    let path = std::env::var("COMPUKTER_KOTLIN_ABSTRACT_PROPERTIES_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_ABSTRACT_PROPERTIES_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 abstract properties output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 abstract properties output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 abstract properties program must admit");
    session.start(&[]).expect("K2 abstract properties program must start");

    for expected in ["2\n", "3\n", "30\n", "8\n", "7\n", "6\n", "18\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "abstract properties println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the abstract properties program");
    }
    loop {
        match session.advance(512, 64).expect("K2 abstract properties program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 abstract properties outcome: {outcome:?}"),
        }
    }
}

fn k2_interface_defaults_preserve_override_precedence() {
    let path = std::env::var("COMPUKTER_KOTLIN_INTERFACE_DEFAULTS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_INTERFACE_DEFAULTS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 interface defaults output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 interface defaults output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 interface defaults program must admit");
    session.start(&[]).expect("K2 interface defaults program must start");

    for expected in ["2\n", "20\n", "3\n", "300\n", "1\n", "7\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "interface defaults println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the interface defaults program");
    }
    loop {
        match session.advance(512, 64).expect("K2 interface defaults program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 interface defaults outcome: {outcome:?}"),
        }
    }
}

fn k2_interface_super_calls_direct_default_bodies() {
    let path = std::env::var("COMPUKTER_KOTLIN_INTERFACE_SUPER_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_INTERFACE_SUPER_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 interface super output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 interface super output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 interface super program must admit");
    session.start(&[]).expect("K2 interface super program must start");

    for expected in ["12\n", "16\n", "4\n", "106\n", "21\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "interface super println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the interface super program");
    }
    loop {
        match session.advance(512, 64).expect("K2 interface super program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 interface super outcome: {outcome:?}"),
        }
    }
}

fn k2_constructor_defaults_preserve_kotlin_argument_order() {
    let path = std::env::var("COMPUKTER_KOTLIN_CONSTRUCTOR_DEFAULTS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_CONSTRUCTOR_DEFAULTS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 constructor defaults output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 constructor defaults output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 constructor defaults program must admit");
    session.start(&[]).expect("K2 constructor defaults program must start");

    for expected in ["30\n", "10\n", "2\n", "1150\n", "1\n", "2\n", "3\n", "133\n", "456\n", "12\n", "99\n", "3\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "constructor defaults println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the constructor defaults program");
    }
    loop {
        match session.advance(512, 64).expect("K2 constructor defaults program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 constructor defaults outcome: {outcome:?}"),
        }
    }
}

fn k2_adapted_constructor_references_preserve_defaults_and_identity() {
    let path = std::env::var("COMPUKTER_KOTLIN_ADAPTED_CONSTRUCTORS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_ADAPTED_CONSTRUCTORS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 adapted constructors output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 adapted constructors output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 adapted constructors program must admit");
    session.start(&[]).expect("K2 adapted constructors program must start");

    for expected in ["4\n", "5\n", "49\n", "4\n", "5\n", "49\n", "99\n", "9\n", "5\n", "82\n", "12\n", "12\n", "2\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "adapted constructor reference println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the adapted constructors program");
    }
    loop {
        match session.advance(512, 64).expect("K2 adapted constructors program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 adapted constructors outcome: {outcome:?}"),
        }
    }
}

fn k2_property_accessors_preserve_backing_and_dispatch() {
    let path = std::env::var("COMPUKTER_KOTLIN_PROPERTY_ACCESSORS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_PROPERTY_ACCESSORS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 property accessors output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 property accessors output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 property accessors program must admit");
    session.start(&[]).expect("K2 property accessors program must start");

    for expected in [
        "11\n", "12\n", "10\n", "6\n", "18\n", "2\n", "7\n", "32\n", "12\n", "9\n",
    ] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "property accessors println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the property accessors program");
    }
    loop {
        match session.advance(512, 64).expect("K2 property accessors program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 property accessors outcome: {outcome:?}"),
        }
    }
}

fn k2_sealed_data_and_enum_objects_execute() {
    let path = std::env::var("COMPUKTER_KOTLIN_OBJECT_MODEL_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_OBJECT_MODEL_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 object-model output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 object-model output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 object-model program must admit");
    session.start(&[]).expect("K2 object-model program must start");

    for expected in ["7\n", "7\n", "-1\n", "false\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "object-model println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the object-model program");
    }
    loop {
        match session.advance(512, 64).expect("K2 object-model program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 object-model outcome: {outcome:?}"),
        }
    }
}

fn k2_class_and_interface_dispatch_select_runtime_implementations() {
    let path = std::env::var("COMPUKTER_KOTLIN_DISPATCH_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_DISPATCH_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 dispatch output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 dispatch output");
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[]).expect("K2 dispatch program must admit");
    session.start(&[]).expect("K2 dispatch program must start");

    loop {
        match session.advance(4096, 64).expect("K2 dispatch program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 dispatch outcome: {outcome:?}"),
        }
    }
}

fn k2_mutable_class_fields_preserve_aliases_and_evaluation_order() {
    let path = std::env::var("COMPUKTER_KOTLIN_MUTABLE_FIELDS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_MUTABLE_FIELDS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 mutable-fields output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 mutable-fields output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio]).expect("K2 mutable-fields program must admit");
    session.start(&[]).expect("K2 mutable-fields program must start");

    for expected in ["5\n", "10\n", "7\n", "7\n", "12\n", "90\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "mutable-fields println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the mutable-fields program");
    }
    loop {
        match session.advance(512, 64).expect("K2 mutable-fields program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 mutable-fields outcome: {outcome:?}"),
        }
    }
}

fn k2_class_initialization_preserves_source_and_super_order() {
    let path = std::env::var("COMPUKTER_KOTLIN_CLASS_INITIALIZATION_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_CLASS_INITIALIZATION_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 class initialization output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 class initialization output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio])
        .expect("K2 class initialization program must admit");
    session.start(&[]).expect("K2 class initialization program must start");

    for expected in ["base\n", "child\n", "11\n", "112\n", "1\n", "base\n", "child\n", "41\n", "412\n"] {
        let value = utf16(expected);
        let (task, write) = next_host_request_identity_with_budget(
            &mut session,
            "class initialization println",
            1,
            Some(&value),
            512,
        );
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the class initialization program");
    }
    loop {
        match session.advance(512, 64).expect("K2 class initialization program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 class initialization outcome: {outcome:?}"),
        }
    }
}

fn k2_function_values_preserve_distinct_captures_and_dispatch() {
    let path = std::env::var("COMPUKTER_KOTLIN_FUNCTION_VALUES_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_FUNCTION_VALUES_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 function-values output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 function-values output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[stdio]).expect("K2 function-values program must admit");
    session
        .start(&[])
        .expect("K2 function-values program must start");

    for expected in [
        "3\n", "4\n", "9\n", "11\n", "1\n", "11\n", "2\n", "31\n", "42\n", "32\n", "7\n", "12\n",
        "15\n", "4\n", "11\n", "10\n", "12\n", "6\n", "10\n", "24\n", "42\n", "5\n", "v8\n", "13\n", "6\n", "10\n",
        "9\n", "17\n", "1\n", "2\n", "12\n", "14\n", "10\n", "18\n", "19\n", "12\n", "19\n",
        "12\n", "16\n", "23\n", "1\n", "2\n", "10\n", "11\n", "14\n", "2\n", "29\n",
        "30\n", "31\n", "32\n", "74\n",
    ] {
        let value = utf16(expected);
        let (task, write) =
            next_host_request_identity_with_budget(&mut session, "function-value println", 1, Some(&value), 512);
        session
            .resume_for(task, write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the function-values program");
    }
    loop {
        match session
            .advance(512, 64)
            .expect("K2 function-values program must finish")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 function-values outcome: {outcome:?}"),
        }
    }
}

fn k2_timer_sleep_publishes_one_bounded_request() {
    let path = std::env::var("COMPUKTER_KOTLIN_TIMER_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_TIMER_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 timer output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 timer output");
    let timer_arguments = [HostValueType::I32];
    let timer_operations = [OperationSchema::asynchronous(
        &timer_arguments,
        HostValueType::Unit,
    )];
    let timer = CapabilityBinding::new("compukter", "timer", 1, 0, &timer_operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[timer])
        .expect("K2 timer program must admit");
    session.start(&[]).expect("K2 timer program must start");

    let AdvanceOutcome::HostRequestBatch(batch) = session
        .advance(4096, 64)
        .expect("K2 timer program must advance")
    else {
        panic!("K2 timer program did not publish a host request batch")
    };
    assert_eq!(1, batch.len());
    let request = batch.get(0).expect("timer request must be present");
    assert_eq!("compukter", request.namespace());
    assert_eq!("timer", request.name());
    assert_eq!(0, request.operation());
    assert!(request.asynchronous());
    assert!(matches!(request.arguments().get(0), Some(HostValueView::I32(12))));
    let identity = (request.task_id(), request.id());

    session
        .resume_for(
            identity.0,
            identity.1,
            HostResponse::Success(HostValueInput::Unit),
        )
        .expect("timer task must resume");
    loop {
        match session.advance(4096, 64).expect("K2 timer program must finish") {
            AdvanceOutcome::Halted(None) => break,
            AdvanceOutcome::SliceExhausted => {}
            outcome => panic!("unexpected K2 timer outcome: {outcome:?}"),
        }
    }
}

fn k2_tasks_keep_independent_host_requests_in_flight() {
    let path = std::env::var("COMPUKTER_KOTLIN_TASKS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_TASKS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 tasks output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 tasks output");
    let side = [HostValueType::I32];
    let side_and_level = [HostValueType::I32, HostValueType::I32];
    let operations = [
        OperationSchema::synchronous(&side, HostValueType::I32),
        OperationSchema::asynchronous(&side, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level, HostValueType::I32),
        OperationSchema::synchronous(&[], HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level, HostValueType::Unit),
        OperationSchema::asynchronous(&side, HostValueType::Unit),
    ];
    let binding = CapabilityBinding::new("compukter", "redstone", 1, 0, &operations);
    let string_argument = [HostValueType::String];
    let stdio_operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &stdio_operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[binding, stdio])
        .expect("K2 cooperative tasks must admit");
    session.start(&[]).expect("K2 cooperative tasks must start");

    let AdvanceOutcome::HostRequestBatch(batch) = session
        .advance(4096, 64)
        .expect("K2 cooperative tasks must advance")
    else {
        panic!("K2 cooperative tasks did not publish a host request batch")
    };
    assert_eq!(2, batch.len());
    let read = batch.get(0).expect("reader request must be present");
    let write = batch.get(1).expect("writer request must be present");
    assert_eq!("stdio", read.name());
    assert_eq!(0, read.operation());
    assert_eq!("redstone", write.name());
    assert_eq!(6, write.operation());
    assert_ne!(read.task_id(), write.task_id());
    let read_identity = (read.task_id(), read.id());
    let write_identity = (write.task_id(), write.id());

    session
        .resume_for(
            write_identity.0,
            write_identity.1,
            HostResponse::Success(HostValueInput::Unit),
        )
        .expect("writer task must resume first");
    session
        .resume_for(
            read_identity.0,
            read_identity.1,
            HostResponse::Success(HostValueInput::String(&utf16("ready"))),
        )
        .expect("reader task must resume second");

    loop {
        match session
            .advance(4096, 64)
            .expect("K2 cooperative tasks must finish")
        {
            AdvanceOutcome::Halted(None) => break,
            AdvanceOutcome::SliceExhausted => {}
            outcome => panic!("unexpected K2 cooperative tasks outcome: {outcome:?}"),
        }
    }
}

fn k2_channel_handoff_stays_inside_the_vm() {
    let path = std::env::var("COMPUKTER_KOTLIN_CHANNEL_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_CHANNEL_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 channel output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 channel output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_channels: 1,
        maximum_channel_values: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio]).expect("K2 channel must admit");
    session.start(&[]).expect("K2 channel must start");

    let write = next_host_request(&mut session, "println", 1, Some(&utf16("13\n")));
    session
        .resume(write, HostResponse::Success(HostValueInput::Unit))
        .expect("println must resume the receiving task");
    loop {
        match session.advance(64, 64).expect("K2 channel must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 channel outcome: {outcome:?}"),
        }
    }
}

fn k2_long_executes_arithmetic_conversions_comparisons_and_text() {
    let path = std::env::var("COMPUKTER_KOTLIN_LONG_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_LONG_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 Long output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 Long output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio]).expect("K2 Long must admit");
    session.start(&[]).expect("K2 Long must start");

    let value = utf16("9\n");
    let write = next_host_request(&mut session, "println Long", 1, Some(&value));
    session
        .resume(write, HostResponse::Success(HostValueInput::Unit))
        .expect("println must resume the Long program");
    let concatenated = utf16("value=9\n");
    let write = next_host_request(
        &mut session,
        "println concatenated Long",
        1,
        Some(&concatenated),
    );
    session
        .resume(write, HostResponse::Success(HostValueInput::Unit))
        .expect("concatenated println must resume the Long program");
    let summary = utf16("-9:true:-9223372036854775808:9223372036854775807:9:7\n");
    let write = next_host_request(&mut session, "println summary", 1, Some(&summary));
    session
        .resume(write, HostResponse::Success(HostValueInput::Unit))
        .expect("summary println must resume the Long program");
    for expected in ["-1\n", "1\n", "0\n", "-1\n", "1\n", "0\n", "-1\n", "-1\n", "0\n"] {
        let value = utf16(expected);
        let write = next_host_request(&mut session, "println compareTo result", 1, Some(&value));
        session
            .resume(write, HostResponse::Success(HostValueInput::Unit))
            .expect("compareTo println must resume the Long program");
    }
    for expected in ["left\n", "right\n", "-1\n"] {
        let value = utf16(expected);
        let write = next_host_request(&mut session, "println compareTo operand", 1, Some(&value));
        session
            .resume(write, HostResponse::Success(HostValueInput::Unit))
            .expect("compareTo operand println must resume the Long program");
    }
    loop {
        match session.advance(64, 64).expect("K2 Long must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 Long outcome: {outcome:?}"),
        }
    }
}

fn k2_same_named_guest_calls_preserve_resolved_targets() {
    k2_expected_prints_with_budget(
        "COMPUKTER_KOTLIN_NAMED_CALLS_ARTIFACT",
        [
            "23\n", "Z\n", "false\n", "false\n", "24\n", "35\n", "5\n", "a\n", "true\n",
            "true\n",
        ],
        128,
    );
}

fn k2_string_compare_uses_utf16_code_units() {
    k2_expected_prints_with_budget(
        "COMPUKTER_KOTLIN_STRING_COMPARE_ARTIFACT",
        [
            "0\n", "-1\n", "-2\n", "2\n", "2\n", "0\n", "-1\n", "1\n", "left\n", "right\n", "-1\n",
            "true\n", "true\n", "false\n", "false\n", "true\n", "false\n", "true\n", "true\n",
            "true\n", "true\n", "left\n", "right\n", "true\n",
        ],
        128,
    );
}

fn k2_char_and_boolean_compare_preserve_ordering() {
    k2_expected_prints_with_budget(
        "COMPUKTER_KOTLIN_SCALAR_COMPARE_ARTIFACT",
        [
            "-2\n", "2\n", "0\n", "65535\n", "-65535\n", "char-left\n", "char-right\n", "-2\n", "true\n",
            "0\n", "-1\n", "1\n", "0\n", "boolean-left\n", "boolean-right\n", "-1\n", "true\n", "true\n",
            "true\n", "true\n", "false\n", "boolean-left\n", "boolean-right\n", "true\n",
        ],
        128,
    );
}

fn k2_generic_functions_preserve_primitive_and_reference_values() {
    k2_expected_prints("COMPUKTER_KOTLIN_GENERIC_FUNCTIONS_ARTIFACT", ["42\n", "hello\n"]);
}

fn k2_generic_cell_preserves_typed_fields_and_aliases() {
    k2_expected_prints("COMPUKTER_KOTLIN_GENERIC_CELL_ARTIFACT", ["42\n", "second\n"]);
}

fn k2_generic_interface_dispatches_concrete_types() {
    k2_expected_prints("COMPUKTER_KOTLIN_GENERIC_INTERFACE_ARTIFACT", ["7\n", "hello\n"]);
}

fn k2_lists_retain_typed_elements() {
    k2_expected_prints(
        "COMPUKTER_KOTLIN_LIST_ARTIFACT",
        [
            "2\n", "9\n", "true\n", "0\n", "0\n", "first\n", "blue\n", "1\n", "2\n", "2\n", "16\n", "first\n", "second\n",
        ],
    );
}

fn k2_int_list_covariance_boxes_universal_reads() {
    k2_expected_prints_with_budget(
        "COMPUKTER_KOTLIN_LIST_ANY_ARTIFACT",
        [
            "true\n", "7\n", "true\n", "7\n", "2\n", "7\n", "9\n", "true\n", "true\n", "true\n", "true\n",
            "3\n", "true\n", "7\n", "true\n", "true\n", "true\n", "true\n", "false\n", "false\n", "0\n",
        ],
        256,
    );
}

fn k2_int_list_universal_reads_survive_quota_and_gc() {
    let path = std::env::var("COMPUKTER_KOTLIN_LIST_ANY_QUOTA_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_LIST_ANY_QUOTA_ARTIFACT must be set");
    let bytes = fs::read(path).expect("K2 universal list quota artifact must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify universal list quota artifact");
    let mut profile = list_no_io_profile();
    profile.heap_bytes = 64 * 1024;
    let mut session = Session::admit(verified, profile, &[]).expect("universal list quota program must admit");
    session.start(&[]).expect("universal list quota program must start");
    let mut exhausted_slices = 0;
    loop {
        match session.advance(256, 256).expect("universal list quota program must execute") {
            AdvanceOutcome::SliceExhausted => exhausted_slices += 1,
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected universal list quota outcome: {outcome:?}"),
        }
    }
    assert!(exhausted_slices > 0, "boxed list reads must resume after exhausting a slice");
}

fn k2_list_index_outside_bounds_traps() {
    let path = std::env::var("COMPUKTER_KOTLIN_LIST_BOUNDS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_LIST_BOUNDS_ARTIFACT must be set");
    let bytes = fs::read(path).expect("K2 list bounds artifact must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify list bounds artifact");
    let mut session = Session::admit(verified, list_no_io_profile(), &[]).expect("list bounds program must admit");
    session.start(&[]).expect("list bounds program must start");
    loop {
        match session.advance(64, 64).expect("list bounds program must execute") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Crashed(GuestTrap::IndexOutOfBounds) => break,
            outcome => panic!("unexpected list bounds outcome: {outcome:?}"),
        }
    }
}

fn k2_list_iterator_resumes_across_quota_slices() {
    let path = std::env::var("COMPUKTER_KOTLIN_LIST_QUOTA_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_LIST_QUOTA_ARTIFACT must be set");
    let bytes = fs::read(path).expect("K2 list quota artifact must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify list quota artifact");
    let mut session = Session::admit(verified, list_no_io_profile(), &[]).expect("list quota program must admit");
    session.start(&[]).expect("list quota program must start");
    let mut exhausted_slices = 0;
    loop {
        match session.advance(64, 64).expect("list quota program must execute") {
            AdvanceOutcome::SliceExhausted => exhausted_slices += 1,
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected list quota outcome: {outcome:?}"),
        }
    }
    assert!(exhausted_slices > 0, "list iteration must resume after exhausting a slice");
}

fn list_no_io_profile() -> ExecutionProfile {
    ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    }
}

fn k2_generic_library_specializes_in_consumer() {
    k2_expected_prints("COMPUKTER_KOTLIN_GENERIC_LIBRARY_ARTIFACT", ["42\n", "hello\n"]);
}

fn k2_reference_arrays_retain_typed_guest_objects() {
    k2_expected_prints_with_budget("COMPUKTER_KOTLIN_REFERENCE_ARRAY_ARTIFACT", [], 128);
}

fn k2_nullable_references_preserve_branch_and_call_semantics() {
    k2_expected_prints(
        "COMPUKTER_KOTLIN_NULLABLE_REFERENCE_ARTIFACT",
        [
            "true\n", "true\n", "fallback\n", "missing\n", "read\n", "ready\n", "read\n", "fallback\n",
            "missing\n", "true\n", "global\n", "global-present\n", "true\n", "true\n", "empty\n",
            "later\n", "again\n", "none\n", "re\n", "none\n", "boxed\n", "fresh\n", "read\n",
            "fresh-value\n", "missing-node\n", "fallback\n", "missing\n",
        ],
    );
}

fn k2_expected_prints<const N: usize>(artifact_variable: &str, expected_output: [&str; N]) {
    k2_expected_prints_with_budget(artifact_variable, expected_output, 64)
}

fn k2_expected_prints_with_budget<const N: usize>(
    artifact_variable: &str,
    expected_output: [&str; N],
    slice_budget: u32,
) {
    let path = std::env::var(artifact_variable).expect("K2 artifact path must be set");
    let bytes = fs::read(path).expect("K2 artifact must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio]).expect("K2 program must admit");
    session.start(&[]).expect("K2 program must start");
    for expected in expected_output {
        let value = utf16(expected);
        let write = next_host_request_identity_with_budget(
            &mut session,
            "K2 println",
            1,
            Some(&value),
            slice_budget,
        )
        .1;
        session
            .resume(write, HostResponse::Success(HostValueInput::Unit))
            .expect("K2 println must resume");
    }
    loop {
        match session.advance(slice_budget, 64).expect("K2 program must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 outcome: {outcome:?}"),
        }
    }
}

fn k2_float_executes_arithmetic_conversions_comparisons_and_text() {
    let path = std::env::var("COMPUKTER_KOTLIN_FLOAT_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_FLOAT_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 Float output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 Float output");
    let string_argument = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
    ];
    let stdio = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[stdio]).expect("K2 Float must admit");
    session.start(&[]).expect("K2 Float must start");

    for (label, expected) in [
        ("println Float", "-4.0\n"),
        ("println concatenated Float", "value=-4.0\n"),
        ("println Float conversions", "true:3.0:4.0:3:-3\n"),
        (
            "println Float constants",
            "1.4E-45:3.4028235E38:Infinity:-Infinity:NaN:-0.0\n",
        ),
        ("println NaN versus NaN", "0\n"),
        ("println NaN versus infinity", "1\n"),
        ("println infinity versus NaN", "-1\n"),
        ("println negative zero versus zero", "-1\n"),
        ("println zero versus negative zero", "1\n"),
        ("println equal zeroes", "0\n"),
        ("println Float versus Int", "-1\n"),
        ("println Int versus Float", "1\n"),
        ("println Long versus Float", "0\n"),
        ("println NaN versus Long", "1\n"),
        ("println Long versus NaN", "-1\n"),
        ("println Int zero versus Float negative zero", "1\n"),
        ("println Float negative zero versus Long zero", "-1\n"),
        ("println Float left operand", "float-left\n"),
        ("println Int right operand", "int-right\n"),
        ("println evaluated operands compareTo", "-1\n"),
    ] {
        let value = utf16(expected);
        let write = next_host_request(&mut session, label, 1, Some(&value));
        session
            .resume(write, HostResponse::Success(HostValueInput::Unit))
            .expect("println must resume the Float program");
    }
    loop {
        match session.advance(64, 64).expect("K2 Float must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 Float outcome: {outcome:?}"),
        }
    }
}

fn pinned_vm_verifies_kotlin_executable_instruction_artifact() {
    let path = std::env::var("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("Kotlin writer output must exist");

    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must decode and verify Kotlin writer output");

    assert_eq!(verified.module_count(), 2);
}

fn entry_argument_limits() -> EntryArgumentLimits {
    EntryArgumentLimits {
        maximum_count: 64,
        maximum_code_units_per_argument: 4096,
        maximum_total_code_units: 16_384,
    }
}

fn k2_int_loops_execute_across_quota_slices_without_host_io() {
    let path = std::env::var("COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 Int loops output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 Int loops output");
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: 64,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile.clone(), &[]).expect("K2 Int loops must admit");
    let empty_arguments: [Box<[u16]>; 0] = [];
    session
        .start(&[EntryValue::StringArray(&empty_arguments)])
        .expect("K2 Int loops must start");
    let mut exhausted_slices = 0;

    loop {
        match session.advance(64, 16).expect("K2 Int loops must advance") {
            AdvanceOutcome::SliceExhausted => exhausted_slices += 1,
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 Int loops outcome: {outcome:?}"),
        }
    }

    assert!(
        exhausted_slices > 0,
        "long Int loop must cross a quota boundary"
    );

    let bytes = fs::read(std::env::var("COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT").unwrap())
        .expect("K2 Int loops output must still exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify the invalid-step program");
    let mut invalid =
        Session::admit(verified, profile, &[]).expect("invalid-step program must admit");
    let arguments = [vec![0x0078].into_boxed_slice()];
    invalid
        .start(&[EntryValue::StringArray(&arguments)])
        .expect("invalid-step program must start");
    loop {
        match invalid
            .advance(64, 16)
            .expect("invalid-step program must advance")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Crashed(GuestTrap::InvalidArgument) => break,
            outcome => panic!("unexpected invalid-step outcome: {outcome:?}"),
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
enum IntArrayOutcome {
    Halted,
    Crashed(GuestTrap),
    AllocationExhausted,
}

#[derive(Debug, Eq, PartialEq)]
struct IntArrayExecution {
    outcome: IntArrayOutcome,
    writes: Vec<Vec<u16>>,
    exhausted_slices: u32,
}

fn k2_int_array_executes_specialized_storage_and_traps() {
    let path = std::env::var("COMPUKTER_KOTLIN_INT_ARRAY_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_INT_ARRAY_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 IntArray output must exist");

    let success = execute_int_array_artifact(&bytes, 0);
    assert_eq!(IntArrayOutcome::Halted, success.outcome);
    assert_eq!(vec![utf16("7"), utf16("11"), utf16("13")], success.writes);
    assert!(
        success.exhausted_slices > 0,
        "IntArray fill must resume after exhausting a slice"
    );
    assert_eq!(
        IntArrayOutcome::Crashed(GuestTrap::NegativeArraySize),
        execute_int_array_artifact(&bytes, 1).outcome
    );
    assert_eq!(
        IntArrayOutcome::AllocationExhausted,
        execute_int_array_artifact(&bytes, 2).outcome
    );
    assert_eq!(
        IntArrayOutcome::Crashed(GuestTrap::IndexOutOfBounds),
        execute_int_array_artifact(&bytes, 3).outcome
    );
    assert_eq!(
        IntArrayOutcome::Crashed(GuestTrap::IndexOutOfBounds),
        execute_int_array_artifact(&bytes, 4).outcome
    );
}

fn execute_int_array_artifact(bytes: &[u8], mode: i32) -> IntArrayExecution {
    let verified = verify_artifact(Arc::from(bytes.to_vec()), ArtifactLimits::default())
        .expect("pinned VM must verify K2 IntArray output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::String),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: 64,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("K2 IntArray program must admit");
    session.start(&[]).expect("K2 IntArray program must start");
    let mode_request = next_host_request(&mut session, "eventKey", 5, None);
    session
        .resume(
            mode_request,
            HostResponse::Success(HostValueInput::I32(mode)),
        )
        .expect("eventKey must resume IntArray program");

    let mut writes = Vec::new();
    let mut exhausted_slices = 0;
    let outcome = loop {
        match session
            .advance(64, 64)
            .expect("K2 IntArray program must advance")
        {
            AdvanceOutcome::SliceExhausted => exhausted_slices += 1,
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 IntArray must publish one request");
                assert_eq!(0, request.operation(), "only marker writes are expected");
                let value = match request.arguments().get(0) {
                    Some(HostValueView::String(value)) => value.to_vec(),
                    argument => panic!("unexpected IntArray marker argument: {argument:?}"),
                };
                let request_id = request.id();
                writes.push(value);
                session
                    .resume(request_id, HostResponse::Success(HostValueInput::Unit))
                    .expect("IntArray marker write must resume");
            }
            AdvanceOutcome::Halted(None) => break IntArrayOutcome::Halted,
            AdvanceOutcome::Crashed(trap) => break IntArrayOutcome::Crashed(trap),
            AdvanceOutcome::AllocationExhausted(_) => break IntArrayOutcome::AllocationExhausted,
            outcome => panic!("unexpected K2 IntArray outcome: {outcome:?}"),
        }
    };
    IntArrayExecution {
        outcome,
        writes,
        exhausted_slices,
    }
}

fn k2_platform_scalar_precondition_traps_before_publishing_a_value() {
    let path = std::env::var("COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 platform-scalar output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 platform-scalar output");
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let side_arguments = [HostValueType::I32];
    let side_and_level_arguments = [HostValueType::I32, HostValueType::I32];
    let operations = [
        OperationSchema::synchronous(&side_arguments, HostValueType::I32),
        OperationSchema::asynchronous(&side_arguments, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level_arguments, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level_arguments, HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level_arguments, HostValueType::I32),
        OperationSchema::synchronous(&[], HostValueType::I32),
        OperationSchema::asynchronous(&side_and_level_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&side_arguments, HostValueType::Unit),
    ];
    let binding = CapabilityBinding::new("compukter", "redstone", 1, 0, &operations);
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("platform-scalar artifact must admit");
    session
        .start(&[])
        .expect("platform-scalar artifact must start");

    loop {
        match session
            .advance(64, 64)
            .expect("platform-scalar artifact must advance")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Crashed(GuestTrap::InvalidArgument) => break,
            AdvanceOutcome::HostRequestBatch(_) => {
                panic!("invalid platform scalar construction must trap before a host request")
            }
            outcome => panic!("unexpected platform-scalar precondition outcome: {outcome:?}"),
        }
    }
}

fn k2_string_array_entry_executes_exact_utf16_arguments() {
    let path = std::env::var("COMPUKTER_KOTLIN_ARGV_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_ARGV_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 argv output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 argv output");
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
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[binding]).expect("K2 argv must admit");
    let arguments = [
        Vec::<u16>::new().into_boxed_slice(),
        vec![0x0041, 0x0000, 0xd800, 0x0042].into_boxed_slice(),
    ];
    session
        .start(&[EntryValue::StringArray(&arguments)])
        .expect("K2 argv must start");

    let request_id = loop {
        match session.advance(64, 64).expect("K2 argv must advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 argv must publish one request");
                assert_eq!(0, request.operation());
                assert_eq!(
                    Some(HostValueView::String(&[
                        0x003a, 0x0041, 0x0000, 0xd800, 0x0042
                    ])),
                    request.arguments().get(0),
                );
                break request.id();
            }
            outcome => panic!("unexpected K2 argv outcome before terminal write: {outcome:?}"),
        }
    };
    session
        .resume(request_id, HostResponse::Success(HostValueInput::Unit))
        .expect("terminal write must resume");
    loop {
        match session.advance(64, 64).expect("K2 argv must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 argv outcome after terminal write: {outcome:?}"),
        }
    }
}

fn k2_string_materialization_executes_char_arrays_and_scalar_templates() {
    let path = std::env::var("COMPUKTER_KOTLIN_SUBSET_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_SUBSET_ARTIFACT must be set for this conformance test");
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
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[binding]).expect("K2 subset must admit");
    session.start(&[]).expect("K2 subset must start");

    let request_id = loop {
        match session.advance(512, 64).expect("K2 subset must advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 subset must publish one request");
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
    let template_request = next_host_request_identity_with_budget(
        &mut session,
        "scalar template write",
        0,
        Some(&utf16("2/true/x/2")),
        512,
    ).1;
    session
        .resume(
            template_request,
            HostResponse::Success(HostValueInput::Unit),
        )
        .expect("scalar template write must resume");
    loop {
        match session.advance(512, 64).expect("K2 subset must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 subset outcome after terminal write: {outcome:?}"),
        }
    }
}

fn k2_ordinary_project_call_resumes_across_async_capability() {
    let path = std::env::var("COMPUKTER_KOTLIN_TRANSPARENT_CALL_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_TRANSPARENT_CALL_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 transparent-call output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 transparent-call output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::String),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("K2 transparent-call program must admit");
    session
        .start(&[])
        .expect("K2 transparent-call program must start");

    let await_request = next_host_request(&mut session, "awaitEvent", 3, None);
    session
        .resume(await_request, HostResponse::Success(HostValueInput::I32(1)))
        .expect("awaitEvent must resume");

    let key_request = next_host_request(&mut session, "eventKey", 5, None);
    session
        .resume(key_request, HostResponse::Success(HostValueInput::I32(13)))
        .expect("eventKey must resume");

    let write_request = next_host_request(
        &mut session,
        "write",
        0,
        Some(&[0x65, 0x6e, 0x74, 0x65, 0x72]),
    );
    session
        .resume(write_request, HostResponse::Success(HostValueInput::Unit))
        .expect("write must resume");

    loop {
        match session
            .advance(64, 64)
            .expect("K2 transparent-call program must finish")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 transparent-call outcome after write: {outcome:?}"),
        }
    }
}

fn k2_bounded_when_selects_matched_and_fallback_branches() {
    let path = std::env::var("COMPUKTER_KOTLIN_WHEN_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_WHEN_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 when output must exist");

    assert_eq!(utf16("enter"), execute_when_artifact(&bytes, 13));
    assert_eq!(utf16("other"), execute_when_artifact(&bytes, 99));
}

fn execute_when_artifact(bytes: &[u8], key: i32) -> Vec<u16> {
    let verified = verify_artifact(Arc::from(bytes.to_vec()), ArtifactLimits::default())
        .expect("pinned VM must verify K2 when output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::String),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 64,
        maximum_channel_values: 4096,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("K2 when program must admit");
    session.start(&[]).expect("K2 when program must start");

    let await_request = next_host_request(&mut session, "awaitEvent", 3, None);
    session
        .resume(await_request, HostResponse::Success(HostValueInput::I32(1)))
        .expect("awaitEvent must resume");
    let key_request = next_host_request(&mut session, "eventKey", 5, None);
    session
        .resume(key_request, HostResponse::Success(HostValueInput::I32(key)))
        .expect("eventKey must resume");
    let (write_request, output) = next_string_host_request(&mut session, "write", 0);
    session
        .resume(write_request, HostResponse::Success(HostValueInput::Unit))
        .expect("write must resume");
    loop {
        match session
            .advance(64, 64)
            .expect("K2 when program must finish")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 when outcome after write: {outcome:?}"),
        }
    }
    output
}

fn next_string_host_request(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
) -> (RequestId, Vec<u16>) {
    loop {
        match session
            .advance(64, 64)
            .unwrap_or_else(|error| panic!("K2 program failed before {operation_name}: {error:?}"))
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 program must publish one request");
                assert_eq!(expected_operation, request.operation(), "{operation_name}");
                let value = match request.arguments().get(0) {
                    Some(HostValueView::String(value)) => value.to_vec(),
                    argument => panic!("unexpected {operation_name} argument: {argument:?}"),
                };
                return (request.id(), value);
            }
            outcome => panic!("unexpected K2 program outcome before {operation_name}: {outcome:?}"),
        }
    }
}

fn utf16(value: &str) -> Vec<u16> {
    value.encode_utf16().collect()
}

fn next_host_request(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
    expected_string: Option<&[u16]>,
) -> RequestId {
    next_host_request_identity(session, operation_name, expected_operation, expected_string).1
}

fn next_host_request_identity(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
    expected_string: Option<&[u16]>,
) -> (TaskId, RequestId) {
    next_host_request_identity_with_budget(session, operation_name, expected_operation, expected_string, 64)
}

fn next_host_request_identity_with_budget(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
    expected_string: Option<&[u16]>,
    slice_budget: u32,
) -> (TaskId, RequestId) {
    loop {
        match session
            .advance(slice_budget, 64)
            .unwrap_or_else(|error| panic!("K2 program failed before {operation_name}: {error:?}"))
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 program must publish one request");
                assert_eq!(expected_operation, request.operation(), "{operation_name}");
                if let Some(expected) = expected_string {
                    assert_eq!(
                        Some(HostValueView::String(expected)),
                        request.arguments().get(0),
                        "{operation_name}",
                    );
                }
                return (request.task_id(), request.id());
            }
            outcome => panic!("unexpected K2 program outcome before {operation_name}: {outcome:?}"),
        }
    }
}
