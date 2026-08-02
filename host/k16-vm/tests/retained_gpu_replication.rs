#[path = "support/retained_gpu_packet.rs"]
mod wire;

use k16_vm::retained_gpu::{
    encode_ack, encode_resync_request, ResyncReason, RetainedDisplayHost, ServerboundOutcome,
    ServerboundRejection, SubmissionOutcome,
};
use wire::*;

fn committed(outcome: SubmissionOutcome) {
    assert!(matches!(outcome, SubmissionOutcome::Committed { .. }));
}

#[test]
fn late_attach_gets_one_current_snapshot_and_no_retry_copy() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    committed(
        host.submit(&packet(0, &[create_image(1, 1, 1, &[0x1234])]))
            .expect("submit"),
    );

    let epoch = host.attach_viewer(11, 42).expect("attach");
    let snapshot = host.drain_payload(11).expect("snapshot");

    assert_eq!(epoch, 1);
    assert_eq!(u16_at(&snapshot, 6), 1);
    assert_eq!(u32_at(&snapshot, 12), 42);
    assert_eq!(u64_at(&snapshot, 16), 1);
    assert_eq!(u64_at(&snapshot, 24), 1);
    assert!(host.drain_payload(11).is_none());
    assert_eq!(host.viewer_count(), 1);
}

#[test]
fn ack_gates_one_coalesced_delta_to_the_newest_target() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    committed(
        host.submit(&packet(0, &[create_instances(1, &vec![instance(0); 8])]))
            .expect("initial"),
    );
    let epoch = host.attach_viewer(11, 42).expect("attach");
    host.drain_payload(11).expect("snapshot");

    committed(
        host.submit(&packet(1, &[patch_instances(1, 1, &[instance(0)])]))
            .expect("patch 1"),
    );
    committed(
        host.submit(&packet(2, &[patch_instances(1, 2, &[instance(0)])]))
            .expect("patch 2"),
    );
    assert!(host.drain_payload(11).is_none());

    assert_eq!(
        host.accept_serverbound(11, &encode_ack(42, epoch, 1).expect("ack"))
            .expect("accept"),
        ServerboundOutcome::Acknowledged,
    );
    host.advance_tick().expect("publish tick");
    let delta = host.drain_payload(11).expect("delta");

    assert_eq!(u16_at(&delta, 6), 2);
    assert_eq!(u64_at(&delta, 24), 1);
    assert_eq!(u64_at(&delta, 32), 3);
    assert_eq!(u16_at(&delta, 64), 1);
    assert_eq!(u16_at(&delta, 66), 2);
}

#[test]
fn viewers_ack_and_advance_independently() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    committed(
        host.submit(&packet(0, &[create_image(1, 2, 1, &[0, 0])]))
            .expect("initial"),
    );
    let epoch_a = host.attach_viewer(11, 42).expect("attach a");
    let epoch_b = host.attach_viewer(12, 42).expect("attach b");
    host.drain_payload(11).expect("snapshot a");
    host.drain_payload(12).expect("snapshot b");
    committed(
        host.submit(&packet(1, &[patch_image(1, 0, 0, 1, 1, &[1])]))
            .expect("patch"),
    );

    assert_eq!(
        host.accept_serverbound(11, &encode_ack(42, epoch_a, 1).expect("ack a"))
            .expect("accept a"),
        ServerboundOutcome::Acknowledged,
    );
    host.advance_tick().expect("tick a");
    assert!(host.drain_payload(11).is_some());
    assert!(host.drain_payload(12).is_none());

    assert_eq!(
        host.accept_serverbound(12, &encode_ack(42, epoch_b, 1).expect("ack b"))
            .expect("accept b"),
        ServerboundOutcome::Acknowledged,
    );
    host.advance_tick().expect("tick b");
    assert!(host.drain_payload(12).is_some());
}

#[test]
fn coalesced_recreation_emits_adjacent_drop_create_and_rebinds_equal_id_draw_list() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    committed(
        host.submit(&packet(
            0,
            &[
                create_image(1, 1, 1, &[1]),
                replace_draw_list(0, &[draw_image(1, 1, 1)]),
            ],
        ))
        .expect("initial"),
    );
    let epoch = host.attach_viewer(11, 42).expect("attach");
    host.drain_payload(11).expect("snapshot");

    committed(
        host.submit(&packet(1, &[drop_resource(1), replace_draw_list(0, &[])]))
            .expect("drop"),
    );
    committed(
        host.submit(&packet(
            2,
            &[
                create_image(1, 1, 1, &[2]),
                replace_draw_list(0, &[draw_image(1, 1, 1)]),
            ],
        ))
        .expect("recreate"),
    );
    assert_eq!(
        host.accept_serverbound(11, &encode_ack(42, epoch, 1).expect("ack"))
            .expect("accept"),
        ServerboundOutcome::Acknowledged,
    );
    host.advance_tick().expect("publish");

    let delta = host.drain_payload(11).expect("delta");
    assert_eq!(u64_at(&delta, 24), 1);
    assert_eq!(u64_at(&delta, 32), 3);
    assert_eq!(u32_at(&delta, 40), 2);
    assert_eq!(u32_at(&delta, 44), 36);
    assert_eq!(u16_at(&delta, 48), 0x0020);
    assert_eq!(u32_at(&delta, 56), 1);
    assert_eq!(u16_at(&delta, 60), 0x0001);
    assert_eq!(u32_at(&delta, 68), 1);
}

#[test]
fn valid_resync_rotates_only_that_viewer_epoch_and_snapshots_current_state() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    committed(
        host.submit(&packet(0, &[create_image(1, 1, 1, &[0])]))
            .expect("initial"),
    );
    let epoch = host.attach_viewer(11, 42).expect("attach");
    host.drain_payload(11).expect("snapshot");
    let request =
        encode_resync_request(42, epoch, Some(1), ResyncReason::ReplicaStateLost).expect("request");

    let ServerboundOutcome::Resynchronized { viewer_epoch } =
        host.accept_serverbound(11, &request).expect("resync")
    else {
        panic!("expected resync");
    };
    let snapshot = host.drain_payload(11).expect("new snapshot");
    assert_ne!(viewer_epoch, epoch);
    assert_eq!(u64_at(&snapshot, 16), viewer_epoch);
    assert_eq!(u64_at(&snapshot, 24), 1);
}

#[test]
fn malformed_and_undelivered_ack_do_not_advance_viewer() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    let epoch = host.attach_viewer(11, 42).expect("attach");

    assert_eq!(
        host.accept_serverbound(11, &encode_ack(42, epoch, 0).expect("ack"))
            .expect("accept"),
        ServerboundOutcome::Rejected(ServerboundRejection::AckMismatch),
    );
    host.drain_payload(11).expect("snapshot");
    let mut malformed =
        encode_resync_request(42, epoch, None, ResyncReason::MessageValidationFailed)
            .expect("resync");
    malformed[36] = 1;
    assert_eq!(
        host.accept_serverbound(11, &malformed).expect("accept"),
        ServerboundOutcome::Rejected(ServerboundRejection::Malformed),
    );
    assert!(host.drain_payload(11).is_none());
}

#[test]
fn unacknowledged_viewer_times_out_after_100_ticks() {
    let mut host = RetainedDisplayHost::try_new().expect("host");
    host.attach_viewer(11, 42).expect("attach");
    host.drain_payload(11).expect("snapshot");

    for _ in 0..99 {
        host.advance_tick().expect("tick");
    }
    assert_eq!(host.viewer_count(), 1);
    host.advance_tick().expect("timeout tick");
    assert_eq!(host.viewer_count(), 0);
    assert_eq!(
        host.accept_serverbound(11, &encode_ack(42, 1, 0).expect("ack"))
            .expect("unknown"),
        ServerboundOutcome::Rejected(ServerboundRejection::UnknownViewer),
    );
}

#[test]
fn unobserved_commits_create_no_publication_metadata() {
    let mut host = RetainedDisplayHost::try_new().expect("host");

    committed(
        host.submit(&packet(0, &[create_image(1, 1, 1, &[0])]))
            .expect("submit"),
    );

    assert_eq!(host.viewer_count(), 0);
    assert_eq!(host.pending_descriptor_count(), 0);
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
