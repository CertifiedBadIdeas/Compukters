#[path = "support/retained_gpu_packet.rs"]
mod wire;

use k16_vm::retained_gpu::{
    DamageSubmissionOutcome, ResourceDamage, RetainedGpu, SubmissionOutcome,
};
use wire::*;

fn committed(outcome: SubmissionOutcome) {
    assert!(matches!(outcome, SubmissionOutcome::Committed { .. }));
}

#[test]
fn committed_damage_contains_descriptors_without_resource_payloads() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(
            0,
            &[
                create_image(1, 4, 1, &[0; 4]),
                create_mask(2, 8, 2, &[0; 2]),
                create_instances(3, &vec![instance(0); 4]),
            ],
        ))
        .expect("initial"),
    );

    let DamageSubmissionOutcome::Committed { damage, .. } = gpu
        .submit_with_damage(&packet(
            1,
            &[
                patch_image(1, 1, 0, 2, 1, &[1, 2]),
                patch_mask(2, 0, 1, 8, 1, &[0xff]),
                patch_instances(3, 1, &[instance(0), instance(0)]),
            ],
        ))
        .expect("patches")
    else {
        panic!("expected commit");
    };

    assert_eq!(damage.base_sequence(), 1);
    assert_eq!(damage.target_sequence(), 2);
    assert!(!damage.draw_list_replaced());
    assert_eq!(damage.changes().len(), 3);
    assert!(matches!(
        &damage.changes()[0],
        ResourceDamage::ImagePatches { resource_id: 1, rectangles, .. } if rectangles.len() == 1
    ));
    assert!(matches!(
        &damage.changes()[1],
        ResourceDamage::MaskPatches { resource_id: 2, rectangles, .. } if rectangles.len() == 1
    ));
    assert!(matches!(
        &damage.changes()[2],
        ResourceDamage::InstancePatches { resource_id: 3, ranges, .. } if ranges.len() == 1
    ));
    assert_eq!(damage.descriptor_payload_bytes(), 0);
}

#[test]
fn overlapping_and_adjacent_instance_patches_coalesce_to_one_range() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(0, &[create_instances(1, &vec![instance(0); 8])]))
            .expect("initial"),
    );

    let DamageSubmissionOutcome::Committed { damage, .. } = gpu
        .submit_with_damage(&packet(
            1,
            &[
                patch_instances(1, 1, &vec![instance(0); 3]),
                patch_instances(1, 3, &vec![instance(0); 2]),
                patch_instances(1, 5, &[instance(0)]),
            ],
        ))
        .expect("patches")
    else {
        panic!("expected commit");
    };

    let ResourceDamage::InstancePatches { ranges, .. } = &damage.changes()[0] else {
        panic!("instance damage");
    };
    assert_eq!(ranges.len(), 1);
    assert_eq!(ranges[0].start_index, 1);
    assert_eq!(ranges[0].count, 5);
}

#[test]
fn lifecycle_and_draw_list_changes_are_preserved() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(0, &[create_image(1, 1, 1, &[0])]))
            .expect("initial"),
    );

    let DamageSubmissionOutcome::Committed { damage, .. } = gpu
        .submit_with_damage(&packet(
            1,
            &[
                drop_resource(1),
                create_mask(2, 8, 8, &[0xff; 8]),
                replace_draw_list(0x1234, &[]),
            ],
        ))
        .expect("lifecycle")
    else {
        panic!("expected commit");
    };

    assert!(matches!(
        damage.changes()[0],
        ResourceDamage::Dropped { resource_id: 1, .. }
    ));
    assert!(matches!(
        damage.changes()[1],
        ResourceDamage::Created { resource_id: 2, .. }
    ));
    assert!(damage.draw_list_replaced());
}
