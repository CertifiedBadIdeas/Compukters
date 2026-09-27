/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

use compukter_vm::{
    verify_artifact, AccountingSnapshot, AdvanceOutcome, ArtifactLimits, CapabilityBinding,
    EntryArgumentLimits, ExecutionProfile, HostResponse, HostValueInput, HostValueType,
    HostValueView, OperationSchema, RequestId, Session, TaskId, VerifiedArtifact,
};
use std::{fs, path::Path, sync::Arc, time::Instant};

const LARGE_HEAP: u32 = 16 * 1024 * 1024;
const PRESSURE_HEAP: u32 = 256 * 1024;
const SLICE: u32 = 4096;

struct Case {
    fields: Vec<String>,
    artifact: VerifiedArtifact,
    expected: String,
    minimum_heap: u32,
    samples: Vec<Measurement>,
}

#[derive(Clone, Copy)]
struct Measurement {
    create_ns: u128,
    hot_ns: u128,
    create: AccountingSnapshot,
    hot: AccountingSnapshot,
}

fn profile(heap_bytes: u32) -> ExecutionProfile {
    ExecutionProfile {
        heap_bytes,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_channels: 0,
        maximum_channel_values: 0,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: EntryArgumentLimits {
            maximum_count: 16,
            maximum_code_units_per_argument: 4096,
            maximum_total_code_units: 4096,
        },
    }
}

fn request(session: &mut Session) -> Option<(RequestId, TaskId, String)> {
    for _ in 0..100_000 {
        match session.advance(SLICE, SLICE).expect("benchmark advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                assert_eq!(batch.len(), 1);
                let request = batch.get(0).unwrap();
                assert_eq!(request.namespace(), "compukter");
                assert_eq!(request.name(), "stdio");
                assert_eq!(request.operation(), 1);
                let text = match request.arguments().get(0) {
                    Some(HostValueView::String(text)) => String::from_utf16(text).unwrap(),
                    other => panic!("unexpected output: {other:?}"),
                };
                return Some((request.id(), request.task_id(), text));
            }
            AdvanceOutcome::AllocationExhausted(_) => return None,
            outcome => panic!("unexpected benchmark outcome: {outcome:?}"),
        }
    }
    panic!("benchmark exceeded bounded slice count");
}

fn delta(after: AccountingSnapshot, before: AccountingSnapshot) -> AccountingSnapshot {
    AccountingSnapshot {
        fixed_guest_units: after.fixed_guest_units - before.fixed_guest_units,
        dynamic_guest_units: after.dynamic_guest_units - before.dynamic_guest_units,
        maintenance_units: after.maintenance_units - before.maintenance_units,
        entered_blocks: after.entered_blocks - before.entered_blocks,
        executed_instructions: after.executed_instructions - before.executed_instructions,
        retired_instructions: after.retired_instructions - before.retired_instructions,
        published_requests: after.published_requests - before.published_requests,
        accepted_responses: after.accepted_responses - before.accepted_responses,
        trace_digest: after.trace_digest,
    }
}

fn run(case: &Case, heap: u32, ready_only: bool) -> Option<Measurement> {
    let arguments = [HostValueType::String];
    let operations = [
        OperationSchema::asynchronous(&[], HostValueType::String),
        OperationSchema::synchronous(&arguments, HostValueType::Unit),
        OperationSchema::synchronous(&arguments, HostValueType::Unit),
    ];
    let binding = CapabilityBinding::new("compukter", "stdio", 1, 0, &operations);
    let mut session = Session::admit(case.artifact.clone(), profile(heap), &[binding])
        .expect("benchmark admission");
    session.start(&[]).expect("benchmark start");
    let before = session.accounting();
    let start = Instant::now();
    let (ready, task, text) = request(&mut session)?;
    let create_ns = start.elapsed().as_nanos();
    assert_eq!(text, "ready\n");
    let create = delta(session.accounting(), before);
    let before = session.accounting();
    if ready_only {
        return Some(Measurement {
            create_ns,
            hot_ns: 0,
            create,
            hot: delta(before, before),
        });
    }
    session
        .resume_for(task, ready, HostResponse::Success(HostValueInput::Unit))
        .unwrap();
    let start = Instant::now();
    let (done, task, text) = request(&mut session)?;
    let hot_ns = start.elapsed().as_nanos();
    assert_eq!(text, case.expected, "checksum of {}", case.fields[0]);
    let hot = delta(session.accounting(), before);
    session
        .resume_for(task, done, HostResponse::Success(HostValueInput::Unit))
        .unwrap();
    for _ in 0..100_000 {
        match session.advance(SLICE, SLICE).unwrap() {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => {
                return Some(Measurement {
                    create_ns,
                    hot_ns,
                    create,
                    hot,
                })
            }
            outcome => panic!("unexpected benchmark termination: {outcome:?}"),
        }
    }
    panic!("benchmark termination exceeded slice bound");
}

fn minimum_ready_heap(case: &Case) -> u32 {
    // Heap arena blocks are aligned to 16 bytes. This measures admission-to-ready capacity, not RSS.
    let mut low = 2u32;
    let mut high = LARGE_HEAP / 16;
    assert!(run(case, high * 16, true).is_some());
    while low < high {
        let middle = low + (high - low) / 2;
        if run(case, middle * 16, true).is_some() {
            high = middle;
        } else {
            low = middle + 1;
        }
    }
    let minimum = low * 16;
    assert!(run(case, minimum, true).is_some());
    if minimum > 32 {
        assert!(run(case, minimum - 16, true).is_none());
    }
    minimum
}

fn percentile(mut samples: Vec<u128>, percent: usize) -> u128 {
    samples.sort_unstable();
    samples[(samples.len() - 1) * percent / 100]
}

fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    assert_eq!(
        args.len(),
        3,
        "usage: collections_bench ARTIFACT_DIR REPORT_DIR SAMPLES"
    );
    let inputs = Path::new(&args[0]);
    let outputs = Path::new(&args[1]);
    let repetitions: usize = args[2].parse().unwrap();
    assert!(repetitions >= 3);
    fs::create_dir_all(outputs).unwrap();
    let manifest = fs::read_to_string(inputs.join("manifest.tsv")).unwrap();
    let mut cases: Vec<Case> = manifest
        .lines()
        .skip(1)
        .map(|line| {
            let fields: Vec<String> = line.split('\t').map(str::to_owned).collect();
            assert_eq!(fields.len(), 9);
            let bytes = fs::read(inputs.join(format!("{}.cpkt", fields[0]))).unwrap();
            let artifact = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
                .expect("benchmark verification");
            let expected = format!("{}\n", fields[5]);
            Case {
                fields,
                artifact,
                expected,
                minimum_heap: 0,
                samples: vec![],
            }
        })
        .collect();
    assert_eq!(cases.len(), 30);
    // Pre-verify every image. Verification, admission, start and teardown are outside elapsed execution times.
    for case in &mut cases {
        assert!(
            run(case, LARGE_HEAP, false).is_some(),
            "warmup {}",
            case.fields[0]
        );
        case.minimum_heap = minimum_ready_heap(case);
        eprintln!(
            "prepared {}: minimum ready heap {} bytes",
            case.fields[0], case.minimum_heap
        );
    }
    let mut raw = String::from("id\tsample\tcreate_ns\thot_ns\n");
    for sample in 0..repetitions {
        let order: Vec<usize> = if sample % 2 == 0 {
            (0..cases.len()).collect()
        } else {
            (0..cases.len()).rev().collect()
        };
        for index in order {
            let measurement = run(&cases[index], LARGE_HEAP, false).expect("large-heap execution");
            if let Some(previous) = cases[index].samples.first() {
                assert_eq!(
                    previous.create.fixed_guest_units,
                    measurement.create.fixed_guest_units
                );
                assert_eq!(
                    previous.hot.fixed_guest_units,
                    measurement.hot.fixed_guest_units
                );
                assert_eq!(
                    previous.hot.dynamic_guest_units,
                    measurement.hot.dynamic_guest_units
                );
                assert_eq!(
                    previous.hot.maintenance_units,
                    measurement.hot.maintenance_units
                );
            }
            raw.push_str(&format!(
                "{}\t{}\t{}\t{}\n",
                cases[index].fields[0], sample, measurement.create_ns, measurement.hot_ns
            ));
            cases[index].samples.push(measurement);
        }
        eprintln!("measurement round {}/{} complete", sample + 1, repetitions);
    }
    let mut summary = String::from("id\trepresentation\tworkload\tcount\trounds\tchecksum\tartifact_bytes\ttypes\tfunctions\tminimum_ready_heap_bytes\tcreate_median_ns\thot_p10_ns\thot_median_ns\thot_p90_ns\tcreate_instructions\tcreate_fixed_units\tcreate_dynamic_units\tcreate_maintenance_units\thot_instructions\thot_fixed_units\thot_dynamic_units\thot_maintenance_units\tpressure_status\tpressure_hot_ns\tpressure_hot_maintenance_units\n");
    for case in &cases {
        let measurement = case.samples[0];
        let pressured = run(case, PRESSURE_HEAP, false);
        let (status, elapsed, maintenance) = match pressured {
            Some(sample) => ("ok", sample.hot_ns, sample.hot.maintenance_units),
            None => ("allocation-exhausted", 0, 0),
        };
        summary.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            case.fields.join("\t"),
            case.minimum_heap,
            percentile(case.samples.iter().map(|s| s.create_ns).collect(), 50),
            percentile(case.samples.iter().map(|s| s.hot_ns).collect(), 10),
            percentile(case.samples.iter().map(|s| s.hot_ns).collect(), 50),
            percentile(case.samples.iter().map(|s| s.hot_ns).collect(), 90),
            measurement.create.retired_instructions,
            measurement.create.fixed_guest_units,
            measurement.create.dynamic_guest_units,
            measurement.create.maintenance_units,
            measurement.hot.retired_instructions,
            measurement.hot.fixed_guest_units,
            measurement.hot.dynamic_guest_units,
            measurement.hot.maintenance_units,
            status,
            elapsed,
            maintenance
        ));
    }
    fs::write(outputs.join("samples.tsv"), raw).unwrap();
    fs::write(outputs.join("measurements.tsv"), summary).unwrap();
    eprintln!("reports saved in {}", outputs.display());
}
