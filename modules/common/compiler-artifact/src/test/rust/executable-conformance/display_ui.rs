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
    let mut display_operations = vec![OperationSchema::asynchronous(&[], HostValueType::Unit); 21];
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
    let mut peripheral_operations = vec![OperationSchema::asynchronous(&[], HostValueType::I32); 6];
    peripheral_operations[5] = OperationSchema::asynchronous(
        &[HostValueType::String, HostValueType::String],
        HostValueType::I32,
    );
    let bindings = [
        CapabilityBinding::new("compukter", "stdio", 1, 0, &stdio_operations),
        CapabilityBinding::new("compukters", "display", 1, 3, &display_operations),
        CapabilityBinding::new("compukters", "peripheral", 1, 0, &peripheral_operations),
    ];
    let mut session = Session::admit(verified.clone(), profile, &bindings).unwrap();
    session.start(&[]).unwrap();
    let mut fills = Vec::new();
    let mut printed = false;
    let mut frame_start = None;
    let mut frames = Vec::new();
    for _ in 0..10000 {
        match session.advance(1024, 64).unwrap() {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                assert_eq!(batch.len(), 1);
                let request = batch.get(0).unwrap();
                let args = request.arguments();
                let mut response = HostValueInput::Unit;
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
                        14 | 15 => {}
                        11 => fills.push(
                            (0..6)
                                .map(|i| match args.get(i) {
                                    Some(HostValueView::I32(value)) => value,
                                    value => panic!("invalid fill argument: {value:?}"),
                                })
                                .collect::<Vec<_>>(),
                        ),
                        9 | 16 | 19 | 20 => {}
                        value => panic!("unexpected display operation {value}"),
                    }
                }
                let id = request.id();
                let operation = request.operation();
                let is_display = request.name() == "display";
                if is_display && operation == 14 { frame_start = Some(session.accounting()); }
                if is_display && operation == 15 {
                    let before = frame_start.take().unwrap();
                    let after = session.accounting();
                    frames.push((after.published_requests - before.published_requests + 1,
                        after.fixed_guest_units - before.fixed_guest_units,
                        after.dynamic_guest_units - before.dynamic_guest_units));
                }
                session.resume(id, HostResponse::Success(response)).unwrap();
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
                assert_eq!(frames.len(), 52);
                assert_eq!(frames[0].0, frames[49].0);
                println!("SCADA frame (requests, fixed Guest units, dynamic Guest units): empty={:?}, full={:?}", frames[49], frames[51]);
                return;
            }
            outcome => panic!("unexpected UI outcome: {outcome:?}"),
        }
    }
    panic!("UI scenario did not finish");
}
