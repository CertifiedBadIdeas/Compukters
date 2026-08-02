#[path = "support/retained_gpu_packet.rs"]
mod wire;

use k16_vm::retained_gpu::{Resource, ResultCode, RetainedGpu, SubmissionOutcome};
use wire::*;

fn committed(outcome: SubmissionOutcome) -> u64 {
    match outcome {
        SubmissionOutcome::Committed { sequence } => sequence,
        SubmissionOutcome::Rejected(rejection) => panic!("unexpected rejection: {rejection:?}"),
    }
}

#[test]
fn create_patch_drop_and_recreate_advance_distinct_state_versions() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    assert_eq!(
        committed(
            gpu.submit(&packet(0, &[create_image(7, 2, 1, &[1, 2])]))
                .expect("submit"),
        ),
        1,
    );
    let first_incarnation = gpu.resources()[0].incarnation;
    let Resource::ImageRgb565(image) = &gpu.resources()[0].value else {
        panic!("image");
    };
    let allocation = image.pixels().as_ptr();
    assert_eq!(gpu.resources()[0].revision, 1);

    assert_eq!(
        committed(
            gpu.submit(&packet(1, &[patch_image(7, 1, 0, 1, 1, &[9])]))
                .expect("submit"),
        ),
        2,
    );
    let Resource::ImageRgb565(image) = &gpu.resources()[0].value else {
        panic!("image");
    };
    assert_eq!(image.pixels(), &[1, 9]);
    assert_eq!(image.pixels().as_ptr(), allocation);
    assert_eq!(gpu.resources()[0].incarnation, first_incarnation);
    assert_eq!(gpu.resources()[0].revision, 2);

    assert_eq!(
        committed(gpu.submit(&packet(2, &[drop_resource(7)])).expect("submit"),),
        3,
    );
    assert!(gpu.resources().is_empty());
    committed(
        gpu.submit(&packet(3, &[create_image(7, 1, 1, &[3])]))
            .expect("submit"),
    );
    assert_ne!(gpu.resources()[0].incarnation, first_incarnation);
    assert_eq!(gpu.resources()[0].revision, 1);
}

#[test]
fn rejection_rolls_back_every_counter_and_resource_change() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    let outcome = gpu
        .submit(&packet(
            0,
            &[
                create_image(1, 1, 1, &[1]),
                patch_image(1, 1, 0, 1, 1, &[2]),
            ],
        ))
        .expect("guest rejection");
    let SubmissionOutcome::Rejected(rejection) = outcome else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::OutOfBounds);
    assert_eq!(rejection.operation_index, 1);
    assert_eq!(gpu.commit_sequence(), 0);
    assert!(gpu.resources().is_empty());

    committed(
        gpu.submit(&packet(0, &[create_image(1, 1, 1, &[4])]))
            .expect("submit"),
    );
    assert_eq!(gpu.resources()[0].incarnation, 1);

    let SubmissionOutcome::Rejected(stale) = gpu
        .submit(&packet(0, &[drop_resource(1)]))
        .expect("stale rejection")
    else {
        panic!("expected stale rejection");
    };
    assert_eq!(stale.code, ResultCode::StaleBase);
    assert_eq!(stale.operation_index, u32::MAX);
    assert_eq!(stale.byte_offset, 16);
    assert_eq!(gpu.commit_sequence(), 1);
}

#[test]
fn referenced_resource_requires_same_transaction_draw_list_replacement() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(
            0,
            &[
                create_mask(1, 8, 8, &[0xff; 8]),
                create_instances(2, &[instance(0)]),
                replace_draw_list(0, &[draw_mask_instances(1, 2, 1)]),
            ],
        ))
        .expect("submit"),
    );

    let SubmissionOutcome::Rejected(rejection) = gpu
        .submit(&packet(1, &[drop_resource(1)]))
        .expect("guest rejection")
    else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::ResourceInUse);
    assert_eq!(gpu.resources().len(), 2);

    committed(
        gpu.submit(&packet(
            1,
            &[drop_resource(1), replace_draw_list(0x1234, &[])],
        ))
        .expect("submit"),
    );
    assert_eq!(gpu.resources().len(), 1);
    assert_eq!(gpu.draw_list().background_rgb565(), 0x1234);
}

#[test]
fn all_resource_patch_kinds_apply_in_operation_order() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(
            0,
            &[
                create_image(1, 2, 1, &[1, 2]),
                create_mask(2, 8, 1, &[0]),
                create_instances(3, &[instance(0)]),
                patch_image(1, 0, 0, 1, 1, &[9]),
                patch_mask(2, 0, 0, 8, 1, &[0xff]),
                patch_instances(3, 0, &[instance(8)]),
            ],
        ))
        .expect("submit"),
    );

    assert_eq!(gpu.resources().len(), 3);
    assert!(gpu.resources().iter().all(|entry| entry.revision == 2));
}

#[test]
fn per_resource_payload_quota_rejects_before_allocation() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    let pixels = vec![0u16; 257 * 256];
    let outcome = gpu
        .submit(&packet(0, &[create_image(1, 257, 256, &pixels)]))
        .expect("guest rejection");

    let SubmissionOutcome::Rejected(rejection) = outcome else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::QuotaExceeded);
    assert_eq!(gpu.commit_sequence(), 0);
}

#[test]
fn overlapping_patches_commit_in_order_without_reallocating_the_resource() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(0, &[create_image(1, 3, 1, &[0, 0, 0])]))
            .expect("submit"),
    );
    let Resource::ImageRgb565(image) = &gpu.resources()[0].value else {
        panic!("image");
    };
    let allocation = image.pixels().as_ptr();

    committed(
        gpu.submit(&packet(
            1,
            &[
                patch_image(1, 0, 0, 2, 1, &[1, 2]),
                patch_image(1, 1, 0, 2, 1, &[8, 9]),
            ],
        ))
        .expect("submit"),
    );

    let Resource::ImageRgb565(image) = &gpu.resources()[0].value else {
        panic!("image");
    };
    assert_eq!(image.pixels(), &[1, 8, 9]);
    assert_eq!(image.pixels().as_ptr(), allocation);
    assert_eq!(gpu.resources()[0].revision, 3);
}

#[test]
fn invalid_final_draw_list_rolls_back_preceding_resource_creation() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    let outcome = gpu
        .submit(&packet(
            0,
            &[
                create_image(1, 1, 1, &[0]),
                replace_draw_list(0, &[draw_image(99, 1, 1)]),
            ],
        ))
        .expect("guest rejection");

    let SubmissionOutcome::Rejected(rejection) = outcome else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::InvalidDrawList);
    assert_eq!(rejection.operation_index, 1);
    assert!(gpu.resources().is_empty());
    assert_eq!(gpu.commit_sequence(), 0);
}

#[test]
fn aggregate_resource_quotas_roll_back_the_whole_transaction() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    let full = vec![0u16; 256 * 256];
    let outcome = gpu
        .submit(&packet(
            0,
            &[
                create_image(1, 256, 256, &full),
                create_image(2, 256, 256, &full),
                create_image(3, 1, 1, &[0]),
            ],
        ))
        .expect("guest rejection");
    let SubmissionOutcome::Rejected(rejection) = outcome else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::QuotaExceeded);
    assert_eq!(rejection.operation_index, 2);
    assert!(gpu.resources().is_empty());

    let operations: Vec<Vec<u8>> = (1..=129).map(|id| create_image(id, 1, 1, &[0])).collect();
    let outcome = gpu
        .submit(&packet(0, &operations))
        .expect("guest rejection");
    let SubmissionOutcome::Rejected(rejection) = outcome else {
        panic!("expected rejection");
    };
    assert_eq!(rejection.code, ResultCode::QuotaExceeded);
    assert_eq!(rejection.operation_index, 128);
    assert!(gpu.resources().is_empty());
}
