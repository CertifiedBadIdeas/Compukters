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
                    50
                );
                return;
            }
            outcome => panic!("unexpected UI outcome: {outcome:?}"),
        }
    }
    panic!("UI scenario did not finish");
}
