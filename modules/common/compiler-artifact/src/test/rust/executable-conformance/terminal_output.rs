/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

use super::*;

fn computer(variable: &str, arguments: &[EntryValue<'_>]) -> ComputerMachine {
    let path = std::env::var(variable).expect("terminal conformance artifact must be set");
    let verified = verify_artifact(Arc::from(fs::read(path).unwrap()), ArtifactLimits::default()).unwrap();
    let mut profile = list_no_io_profile();
    profile.heap_bytes = 1024 * 1024;
    ComputerMachine::start(verified, profile, &[], arguments).unwrap()
}

pub(super) fn execute() {
    for budget in [128, 512] {
        let mut terminal = computer("COMPUKTER_TERMINAL_OUTPUT_ARTIFACT", &[]);
        let mut completed = false;
        for _ in 0..10000 {
            match terminal.advance(budget, 64, u32::MAX).unwrap() {
                ComputerAdvanceOutcome::SliceExhausted => (),
                ComputerAdvanceOutcome::Halted(None) => { completed = true; break; },
                other => panic!("terminal output failed: {other:?}"),
            }
        }
        assert!(completed);
        for (x, y, scalar) in [(2, 1, 'A'), (3, 1, '😀'), (4, 1, 'Z'), (5, 1, 'B'), (6, 1, 'C'), (50, 3, '😀'), (0, 4, '�'), (1, 4, 'D'), (2, 4, 'E'), (4, 4, 'C')] {
            let cell = terminal.terminal().cell(x, y).unwrap();
            assert_eq!(scalar as u32, cell.code_point());
            assert_eq!(10, cell.foreground());
            assert_eq!(4, cell.background());
        }
        assert_eq!(' ' as u32, terminal.terminal().cell(0, 5).unwrap().code_point());
    }
    let mut chars = computer("COMPUKTER_CMATRIX_ARTIFACT", &[EntryValue::StringArray(&[])]);
    let mut strings = computer("COMPUKTER_CMATRIX_STRINGS_ARTIFACT", &[EntryValue::StringArray(&[])]);
    for _ in 0..100 {
        advance_frame(&mut chars);
        advance_frame(&mut strings);
        assert_eq!(chars.terminal().logical_cells().collect::<Vec<_>>(), strings.terminal().logical_cells().collect::<Vec<_>>());
        assert_eq!(chars.terminal().cursor_position(), strings.terminal().cursor_position());
    }
    let after = chars.resource_snapshot();
    let before = strings.resource_snapshot();
    println!("cmatrix 100 frames: strings heap={} objects={} dynamic={}; chars heap={} objects={} dynamic={}", before.heap_used_bytes, before.live_objects, before.dynamic_guest_units, after.heap_used_bytes, after.live_objects, after.dynamic_guest_units);
    assert!(after.heap_used_bytes < before.heap_used_bytes);
    assert!(after.live_objects < before.live_objects);
    assert!(after.dynamic_guest_units < before.dynamic_guest_units);
}

fn advance_frame(computer: &mut ComputerMachine) {
    for _ in 0..100000 {
        match computer.advance(512, 64, u32::MAX).unwrap() {
            ComputerAdvanceOutcome::SliceExhausted => (),
            ComputerAdvanceOutcome::HostRequestBatch(batch) => {
                assert_eq!(1, batch.requests.len());
                let request = &batch.requests[0];
                assert_eq!("timer", request.name.as_ref());
                assert_eq!(0, request.operation);
                computer.resume_host_request(request.id, HostResponse::Success(HostValueInput::Unit)).unwrap();
                return;
            },
            other => panic!("cmatrix failed: {other:?}"),
        }
    }
    panic!("cmatrix did not finish frame");
}
