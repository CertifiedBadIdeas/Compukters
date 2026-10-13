/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
use super::*;

pub(super) fn execute() {
    let path = std::env::var("COMPUKTER_KOTLIN_DISPLAY_UI_ARTIFACT").unwrap();
    let verified = verify_artifact(
        Arc::from(fs::read(path).unwrap()),
        ArtifactLimits::default(),
    )
    .unwrap();
    let mut profile = list_no_io_profile();
    profile.heap_bytes = 256 * 1024;
    let stdio_operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&[HostValueType::String], HostValueType::Unit),
        OperationSchema::synchronous(&[HostValueType::String], HostValueType::Unit),
    ];
    // Only raster operations used by this fixture are admitted; unexpected operations fail schema validation.
    let mut display_operations = vec![OperationSchema::asynchronous(&[], HostValueType::Unit); 22];
    for index in [14, 15, 16] {
        display_operations[index] = OperationSchema::asynchronous(&[HostValueType::I32], HostValueType::Unit);
    }
    display_operations[9] =
        OperationSchema::asynchronous(&[HostValueType::I32; 4], HostValueType::Unit);
    display_operations[11] =
        OperationSchema::asynchronous(&[HostValueType::I32; 6], HostValueType::Unit);
    display_operations[19] =
        OperationSchema::asynchronous(&[HostValueType::I32; 10], HostValueType::Unit);
    display_operations[20] = OperationSchema::asynchronous(
        &[
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::String,
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::I32,
            HostValueType::I32,
        ],
        HostValueType::Unit,
    );
    display_operations[21] = OperationSchema::asynchronous(&[HostValueType::I32, HostValueType::ByteArray], HostValueType::Unit);
    let mut peripheral_operations = vec![OperationSchema::asynchronous(&[], HostValueType::I32); 6];
    peripheral_operations[5] = OperationSchema::asynchronous(
        &[HostValueType::String, HostValueType::String],
        HostValueType::I32,
    );
    let bindings = [
        CapabilityBinding::new("compukter", "stdio", 1, 0, &stdio_operations),
        CapabilityBinding::new("compukters", "display", 1, 4, &display_operations),
        CapabilityBinding::new("compukters", "peripheral", 1, 0, &peripheral_operations),
    ];
    let mut session = Session::admit(verified.clone(), profile, &bindings).unwrap();
    session.start(&[]).unwrap();
    let mut fills = Vec::new();
    let mut printed = false;
    let mut frame_start = None;
    let mut frames = Vec::new();
    let mut open_frame = false;
    for _ in 0..10000 {
        match session.advance(1024, 64).unwrap() {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                assert_eq!(batch.len(), 1);
                let request = batch.get(0).unwrap();
                let args = request.arguments();
                let mut response = HostValueInput::Unit;
                let mut nested_frame = false;
                if request.name() == "peripheral" {
                    assert_eq!(request.operation(), 5);
                    response = HostValueInput::I32(7);
                } else if request.name() == "stdio" {
                    assert_eq!(request.operation(), 1);
                    assert_eq!(
                        args.get(0),
                        Some(HostValueView::String(&utf16("display ui ok\n")))
                    );
                    assert!(!printed);
                    printed = true;
                } else {
                    assert_eq!(request.name(), "display");
                    assert_eq!(args.get(0), Some(HostValueView::I32(7)));
                    match request.operation() {
                        14 => { nested_frame = open_frame; if !nested_frame { open_frame = true; } }
                        15 | 16 => { assert!(open_frame); open_frame = false; }
                        21 => {
                            let bytes = match args.get(1) { Some(HostValueView::ByteArray(bytes)) => bytes, value => panic!("invalid raster bytes {value:?}") };
                            decode_packet(bytes, &mut fills);
                        }
                        11 => fills.push(
                            (0..6)
                                .map(|i| match args.get(i) {
                                    Some(HostValueView::I32(value)) => value,
                                    value => panic!("invalid fill argument: {value:?}"),
                                })
                                .collect::<Vec<_>>(),
                        ),
                        9 | 19 | 20 => {}
                        value => panic!("unexpected display operation {value}"),
                    }
                }
                let id = request.id();
                let operation = request.operation();
                let is_display = request.name() == "display";
                if is_display && operation == 14 && !nested_frame { frame_start = Some(session.accounting()); }
                if is_display && operation == 15 {
                    let before = frame_start.take().unwrap();
                    let after = session.accounting();
                    frames.push((after.published_requests - before.published_requests + 1,
                        after.fixed_guest_units - before.fixed_guest_units,
                        after.dynamic_guest_units - before.dynamic_guest_units));
                }
                if nested_frame {
                    session.resume(id, HostResponse::Failure(HostFailure::new(HostFailureKind::Unavailable, "frame is open"))).unwrap();
                } else { session.resume(id, HostResponse::Success(response)).unwrap(); }
            }
            AdvanceOutcome::UncaughtException => panic!(
                "UI drawing failed: {}",
                session.uncaught_exception_diagnostic(&verified).unwrap()
            ),
            AdvanceOutcome::Halted(None) => {
                assert!(printed);
                assert_eq!(fills.first(), Some(&vec![7, 100, 50, 10, 10, 0x284C68]));
                assert!(fills.contains(&vec![7, 100, 90, 10, 10, 0x30363C]));
                assert_eq!(
                    fills
                        .iter()
                        .filter(|fill| **fill == vec![7, 12, 308, 84, 26, 0x30363C])
                        .count(),
                    52
                );
                assert_eq!(frames.len(), 53);
                assert_eq!(frames[1].0, frames[50].0);
                assert!(frames[50].0 <= 3 && frames[52].0 <= 4, "drawing regressed to scalar round trips");
                println!("SCADA frame (requests, fixed Guest units, dynamic Guest units): empty={:?}, full={:?}", frames[50], frames[52]);
                return;
            }
            outcome => panic!("unexpected UI outcome: {outcome:?}"),
        }
    }
    panic!("UI scenario did not finish");
}

fn decode_packet(bytes: &[u8], fills: &mut Vec<Vec<i32>>) {
    assert!(bytes.len() <= 4096);
    let mut offset = 0;
    fn int(bytes: &[u8], offset: &mut usize) -> i32 {
        let value = i32::from_le_bytes(bytes[*offset..*offset + 4].try_into().unwrap());
        *offset += 4;
        value
    }
    assert_eq!(int(bytes, &mut offset), 1);
    let count = int(bytes, &mut offset);
    assert!((1..=128).contains(&count));
    for _ in 0..count {
        let op = bytes[offset]; offset += 1;
        match op {
            8 => { int(bytes, &mut offset); }
            9 => { for _ in 0..3 { int(bytes, &mut offset); } }
            10 | 19 => { for _ in 0..if op == 10 { 5 } else { 9 } { int(bytes, &mut offset); } }
            11 => { let mut fields = vec![7]; for _ in 0..5 { fields.push(int(bytes, &mut offset)); } fills.push(fields); }
            12 | 20 => {
                int(bytes, &mut offset); int(bytes, &mut offset);
                let length = int(bytes, &mut offset) as usize;
                let text = (0..length).map(|_| { let value = u16::from_le_bytes(bytes[offset..offset+2].try_into().unwrap()); offset += 2; value }).collect::<Vec<_>>();
                assert!(String::from_utf16(&text).is_ok());
                for _ in 0..if op == 12 { 2 } else { 6 } { int(bytes, &mut offset); }
            }
            13 => {
                int(bytes, &mut offset); int(bytes, &mut offset);
                let count = int(bytes, &mut offset);
                assert!((1..=256).contains(&count));
                for _ in 0..count { int(bytes, &mut offset); }
            }
            _ => panic!("invalid raster operation {op}"),
        }
    }
    assert_eq!(offset, bytes.len());
}
