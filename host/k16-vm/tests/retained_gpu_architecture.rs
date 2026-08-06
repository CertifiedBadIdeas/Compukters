use std::fs;

#[test]
fn gpu0_v2_is_the_only_active_display_abi() {
    let library = fs::read_to_string("src/lib.rs").expect("library source");
    let active_gpu = fs::read_to_string("src/computer/devices/gpu.rs").expect("gpu source");
    let active_abi = fs::read_to_string("src/computer_abi.rs").expect("host ABI source");
    let jni = fs::read_to_string("src/jni.rs").expect("JNI source");

    assert!(library.contains("pub mod retained_gpu;"));
    for declaration in [
        "pub const GPU0_DEVICE_ABI_VERSION: u32 = GPU0_BASE;",
        "pub const GPU0_SUBMISSION_ADDRESS: u32 = GPU0_BASE + 48;",
        "pub const GPU0_SUBMIT: u32 = GPU0_BASE + 56;",
        "pub const GPU0_RESULT_CODE: u32 = GPU0_BASE + 60;",
        "pub const GPU0_COMMITTED_SEQUENCE_HIGH: u32 = GPU0_BASE + 76;",
    ] {
        assert!(
            active_abi.contains(declaration),
            "active gpu0 ABI must declare `{declaration}`"
        );
    }
    assert!(active_gpu.contains("RetainedDisplayHost"));
    for (name, source) in [("active gpu0 device", active_gpu), ("JNI bridge", jni)] {
        for forbidden in ["DisplayFrameDelta", "drain_gpu0_frames"] {
            assert!(
                !source.contains(forbidden),
                "{name} must not expose legacy display concept `{forbidden}`"
            );
        }
    }
    for forbidden in [
        "GPU0_COMMAND_PRESENT",
        "GPU0_COMMAND_BLIT_BUFFER",
        "GPU0_COMMAND_BLIT_MONO_BUFFER",
        "GPU0_PIXEL_FORMAT_RGB565",
    ] {
        assert!(
            !active_abi.contains(forbidden),
            "active gpu0 ABI must not retain `{forbidden}`"
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

#[test]
fn retained_replication_has_no_framebuffer_translation_or_per_viewer_resource_copy() {
    let active_gpu = fs::read_to_string("src/computer/devices/gpu.rs").expect("gpu source");
    let handle = fs::read_to_string("src/computer/handle.rs").expect("computer handle source");
    let replication =
        fs::read_to_string("src/retained_gpu/replication.rs").expect("replication source");
    let damage = fs::read_to_string("src/retained_gpu/damage.rs").expect("damage source");
    let resource_damage = damage
        .split("pub enum ResourceDamage")
        .nth(1)
        .and_then(|source| source.split("impl ResourceDamage").next())
        .expect("resource damage declaration");

    assert!(active_gpu.contains("retained: RetainedDisplayHost"));
    assert!(
        !handle.contains("retained_display: RetainedDisplayHost"),
        "gpu0 must be the only owner of retained display state"
    );
    for forbidden in ["DisplayFrame", "PixelFormat", "framebuffer", "gpu0_frames"] {
        assert!(
            !replication.contains(forbidden),
            "replication must not depend on legacy display concept `{forbidden}`"
        );
    }
    for forbidden in [
        "Vec<u8>",
        "Vec<u16>",
        "ResourceEntry",
        "ImageRgb565",
        "Mask1Bpp",
    ] {
        assert!(
            !resource_damage.contains(forbidden),
            "per-viewer damage metadata must not own resource payload type `{forbidden}`"
        );
    }
    assert!(damage.contains("pub fn descriptor_payload_bytes(&self) -> usize {\n        0\n    }"));
}
