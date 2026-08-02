#[path = "support/retained_gpu_packet.rs"]
mod wire;

use k16_vm::retained_gpu::{
    encode_delta, encode_snapshot, DamageSubmissionOutcome, NetworkEncodeError, ResourceManifest,
    RetainedGpu, SubmissionOutcome,
};
use wire::*;

fn committed(outcome: SubmissionOutcome) {
    assert!(matches!(outcome, SubmissionOutcome::Committed { .. }));
}

#[test]
fn empty_snapshot_has_the_exact_kdsp_header_and_draw_list() {
    let gpu = RetainedGpu::try_new().expect("gpu");

    let bytes = encode_snapshot(42, 7, &gpu).expect("snapshot");

    assert_eq!(bytes.len(), 48);
    assert_eq!(u32_at(&bytes, 0), 0x5053_444b);
    assert_eq!(u16_at(&bytes, 4), 1);
    assert_eq!(u16_at(&bytes, 6), 1);
    assert_eq!(u32_at(&bytes, 8), 48);
    assert_eq!(u32_at(&bytes, 12), 42);
    assert_eq!(u64_at(&bytes, 16), 7);
    assert_eq!(u64_at(&bytes, 24), 0);
    assert_eq!(u32_at(&bytes, 32), 0);
    assert_eq!(u32_at(&bytes, 36), 8);
    assert_eq!(&bytes[40..], &[0; 8]);
}

#[test]
fn snapshot_resources_are_sorted_complete_and_draw_commands_use_only_ids() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(
            0,
            &[
                create_instances(5, &[instance(0)]),
                create_image(9, 1, 1, &[0x1234]),
                create_mask(2, 8, 8, &[0x80; 8]),
                replace_draw_list(0x001f, &[draw_mask_instances(2, 5, 1)]),
            ],
        ))
        .expect("submit"),
    );

    let bytes = encode_snapshot(42, 8, &gpu).expect("snapshot");

    assert_eq!(u64_at(&bytes, 24), 1);
    assert_eq!(u32_at(&bytes, 32), 3);
    assert_eq!(u32_at(&bytes, 36), 32);

    let first = 40;
    assert_eq!(u16_at(&bytes, first), 2);
    assert_eq!(u32_at(&bytes, first + 8), 2);
    assert_eq!(u16_at(&bytes, first + 12), 8);
    assert_eq!(u16_at(&bytes, first + 14), 8);
    assert_eq!(bytes[first + 16], 0x80);

    let second = first + 24;
    assert_eq!(u16_at(&bytes, second), 3);
    assert_eq!(u32_at(&bytes, second + 8), 5);
    assert_eq!(u16_at(&bytes, second + 12), 1);

    let third = second + 40;
    assert_eq!(u16_at(&bytes, third), 1);
    assert_eq!(u32_at(&bytes, third + 8), 9);
    assert_eq!(u16_at(&bytes, third + 12), 1);
    assert_eq!(u16_at(&bytes, third + 14), 1);
    assert_eq!(u16_at(&bytes, third + 16), 0x1234);

    let draw = third + 18;
    assert_eq!(u16_at(&bytes, draw), 0x001f);
    assert_eq!(u32_at(&bytes, draw + 4), 1);
    assert_eq!(u16_at(&bytes, draw + 8), 0x0022);
    assert_eq!(u32_at(&bytes, draw + 16), 2);
    assert_eq!(u32_at(&bytes, draw + 20), 5);
    assert_eq!(bytes.len(), draw + 32);
}

#[test]
fn representative_terminal_snapshot_is_exactly_40_576_bytes() {
    let mut gpu = RetainedGpu::try_new().expect("gpu");
    committed(
        gpu.submit(&packet(
            0,
            &[
                create_mask(1, 128, 128, &vec![0; 2_048]),
                create_instances(2, &vec![instance(0); 1_600]),
                replace_draw_list(
                    0,
                    &[
                        draw_mask_instances_range(1, 2, 0, 800),
                        draw_mask_instances_range(1, 2, 800, 800),
                    ],
                ),
            ],
        ))
        .expect("submit"),
    );

    let bytes = encode_snapshot(1, 1, &gpu).expect("snapshot");

    assert_eq!(bytes.len(), 40_576);
    assert_eq!(u32_at(&bytes, 8), 40_576);
    assert_eq!(u32_at(&bytes, 32), 2);
    assert_eq!(u32_at(&bytes, 36), 56);
}

#[test]
fn snapshot_rejects_zero_transport_identity() {
    let gpu = RetainedGpu::try_new().expect("gpu");

    assert!(matches!(
        encode_snapshot(0, 1, &gpu),
        Err(NetworkEncodeError::InvalidIdentity)
    ));
    assert!(matches!(
        encode_snapshot(1, 0, &gpu),
        Err(NetworkEncodeError::InvalidIdentity)
    ));
}

#[test]
fn one_instance_delta_is_92_bytes_and_row_delta_is_1_604_bytes() {
    for (count, expected_length) in [(1usize, 92usize), (64, 1_604)] {
        let mut gpu = RetainedGpu::try_new().expect("gpu");
        committed(
            gpu.submit(&packet(
                0,
                &[
                    create_mask(1, 8, 8, &[0xff; 8]),
                    create_instances(2, &vec![instance(0); 64]),
                    replace_draw_list(0, &[draw_mask_instances(1, 2, 64)]),
                ],
            ))
            .expect("initial submit"),
        );
        let base = ResourceManifest::try_from_gpu(&gpu).expect("manifest");
        let DamageSubmissionOutcome::Committed { damage, .. } = gpu
            .submit_with_damage(&packet(
                1,
                &[patch_instances(2, 0, &vec![instance(0); count])],
            ))
            .expect("patch")
        else {
            panic!("expected commit");
        };

        let bytes = encode_delta(42, 9, &base, &damage, &gpu).expect("delta");

        assert_eq!(bytes.len(), expected_length);
        assert_eq!(u16_at(&bytes, 6), 2);
        assert_eq!(u64_at(&bytes, 24), 1);
        assert_eq!(u64_at(&bytes, 32), 2);
        assert_eq!(u32_at(&bytes, 40), 1);
        assert_eq!(u32_at(&bytes, 44), 0);
        assert_eq!(u16_at(&bytes, 48), 0x0012);
        assert_eq!(u32_at(&bytes, 56), 2);
        assert_eq!(u32_at(&bytes, 60), 1);
        assert_eq!(u16_at(&bytes, 64), 0);
        assert_eq!(u16_at(&bytes, 66), count as u16);
    }
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(bytes[offset..offset + 2].try_into().expect("u16"))
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("u32"))
}

fn u64_at(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("u64"))
}
