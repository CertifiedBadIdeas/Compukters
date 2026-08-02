use std::fs;

#[test]
fn retained_gpu_core_is_inactive_until_the_hard_cutover() {
    let library = fs::read_to_string("src/lib.rs").expect("library source");
    let active_gpu = fs::read_to_string("src/computer/devices/gpu.rs").expect("gpu source");
    let active_abi = fs::read_to_string("src/computer_abi.rs").expect("host ABI source");
    let jni = fs::read_to_string("src/jni.rs").expect("JNI source");
    let guest_abi =
        fs::read_to_string("../../guest/kraftos/abi/src/lib.rs").expect("guest ABI source");

    assert!(library.contains("pub mod retained_gpu;"));
    for (name, source) in [
        ("active gpu0 device", active_gpu),
        ("active host ABI", active_abi),
        ("JNI bridge", jni),
        ("guest ABI", guest_abi),
    ] {
        assert!(
            !source.contains("RetainedGpu") && !source.contains("retained_gpu"),
            "{name} must not activate gpu0 v2 before the hard-cut issue"
        );
    }
}

#[test]
fn transaction_atomicity_does_not_clone_canonical_resources() {
    let source = fs::read_to_string("src/retained_gpu/transaction.rs").expect("transaction source");

    for forbidden in [
        "self.resources.clone()",
        "gpu.resources.clone()",
        "RetainedGpu: Clone",
        "derive(Clone)]\npub struct RetainedGpu",
        ".sort_by_key(PreparedAction::operation_index)",
    ] {
        assert!(
            !source.contains(forbidden),
            "transaction engine must not contain `{forbidden}`"
        );
    }
    assert!(source.contains("try_reserve_exact(MAX_RESOURCES * 2)"));
    assert!(source.contains("partition_point"));
    assert!(!source.contains("actions.iter().rev().find"));
    assert!(
        source.matches(".patch_rect").count() >= 4 && source.matches(".patch(").count() >= 2,
        "validated existing-resource patches must commit through the resource's in-place API"
    );
}
