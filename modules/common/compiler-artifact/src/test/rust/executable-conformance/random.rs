/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

use super::*;
use compukter_vm::{
    ComputerCheckpointLimits, ComputerFileSystem, ComputerId, ComputerRestoreEnvironment,
    FileCapability, FileRights, FileSystemLimits, ProcessLimits, VirtualPath,
};

pub(super) fn execute() {
    let path =
        std::env::var("COMPUKTER_KOTLIN_RANDOM_ARTIFACT").expect("Random artifact must be set");
    let verified = verify_artifact(
        Arc::from(fs::read(path).expect("Random artifact must exist")),
        ArtifactLimits::default(),
    )
    .expect("Random artifact must verify");
    for budget in [128, 512] {
        let mut profile = list_no_io_profile();
        profile.heap_bytes = 256 * 1024;
        let mut original = ComputerMachine::start(verified.clone(), profile.clone(), &[], &[])
            .expect("Random computer must start");
        // Pause after initialization inside the long-lived seeded/Default sequence loop.
        for _ in 0..128 {
            assert_eq!(
                ComputerAdvanceOutcome::SliceExhausted,
                original
                    .advance(budget, 16, 16)
                    .expect("Random must cross slices")
            );
        }
        let id = ComputerId::from_bytes([7; 16]);
        let limits = ComputerCheckpointLimits::default();
        let bytes = original
            .checkpoint(id, b"random", limits)
            .expect("Random checkpoint must encode mutable generator objects");
        let filesystem_limits = FileSystemLimits::default();
        let environment = ComputerRestoreEnvironment {
            id,
            profile,
            process_limits: ProcessLimits::default(),
            addon_bindings: &[],
            filesystem: ComputerFileSystem::with_limits(filesystem_limits),
            initial_file_capability: FileCapability::new(
                VirtualPath::parse_utf8("/home", &filesystem_limits).unwrap(),
                FileRights::OWNER,
            ),
        };
        let (mut restored, host) = ComputerMachine::restore_checkpoint(environment, &bytes, limits)
            .expect("Random checkpoint must restore");
        assert_eq!(host.as_slice(), b"random");
        assert_eq!(
            original.resource_snapshot().retired_instructions,
            restored.resource_snapshot().retired_instructions
        );
        let mut halted = false;
        for _ in 0..200_000 {
            let expected = original
                .advance(budget, 16, 16)
                .expect("original must advance");
            let actual = restored
                .advance(budget, 16, 16)
                .expect("restored must advance");
            assert_eq!(
                expected, actual,
                "checkpoint must preserve deterministic execution"
            );
            assert_eq!(
                original.resource_snapshot().retired_instructions,
                restored.resource_snapshot().retired_instructions
            );
            match actual {
                ComputerAdvanceOutcome::SliceExhausted => {}
                ComputerAdvanceOutcome::Halted(None) => {
                    halted = true;
                    break;
                }
                outcome => panic!("Random checkpoint assertions failed: {outcome:?}"),
            }
        }
        assert!(
            halted,
            "restored Random program must finish and verify independent golden hashes"
        );
    }
    println!("Random conformance: slices 128/512 and two checkpoint round trips passed");
}
