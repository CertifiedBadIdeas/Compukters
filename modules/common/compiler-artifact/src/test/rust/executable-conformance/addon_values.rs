/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
use super::*;
use compukter_vm::{HostRecordField, HostRecordMember, HostRecordScalar, HostRecordSchema, HostRecordValue};

fn schema(name: &str, fields: Vec<(&str, HostValueType, Option<HostRecordSchema>)>) -> HostRecordSchema {
    HostRecordSchema { type_name: name.into(), fields: fields.into_iter().map(|(name, value_type, record)| HostRecordField {
        name: name.into(), value_type, record: record.map(Box::new),
    }).collect() }
}
fn vector() -> HostRecordSchema {
    schema("kotlin.math.Vector3d", ["x", "y", "z"].map(|name| (name, HostValueType::F64, None)).to_vec())
}
fn point() -> HostRecordSchema {
    schema("fixturevalues.Point", ["x", "y"].map(|name| (name, HostValueType::F64, None)).to_vec())
}
fn data() -> HostRecordSchema {
    schema("fixturevalues.Data", vec![("point", HostValueType::Record, Some(point())), ("count", HostValueType::I32, None)])
}
fn reply() -> HostRecordSchema {
    schema("fixturevalues.Reply", vec![("point", HostValueType::Record, Some(vector())), ("data", HostValueType::Record, Some(data())), ("label", HostValueType::String, None)])
}
fn value(name: &str, fields: Vec<(&str, HostRecordScalar)>) -> HostRecordValue {
    HostRecordValue { type_name: name.into(), fields: fields.into_iter().map(|(name, value)| HostRecordMember { name: name.into(), value }).collect() }
}
fn nested(record: HostRecordValue) -> HostRecordScalar { HostRecordScalar::Record(Box::new(record)) }
fn vector_value() -> HostRecordScalar {
    nested(value("kotlin.math.Vector3d", vec![("x", HostRecordScalar::F64(1f64.to_bits())), ("y", HostRecordScalar::F64(2f64.to_bits())), ("z", HostRecordScalar::F64(3f64.to_bits()))]))
}
fn reply_value() -> HostRecordValue {
    value("fixturevalues.Reply", vec![("point", vector_value()), ("data", nested(value("fixturevalues.Data", vec![("point", nested(value("fixturevalues.Point", vec![("x", HostRecordScalar::F64(4f64.to_bits())), ("y", HostRecordScalar::F64(5f64.to_bits()))]))), ("count", HostRecordScalar::I32(7))]))), ("label", HostRecordScalar::String("owned".encode_utf16().collect()))])
}
pub(super) fn execute() {
    let path = std::env::var("COMPUKTER_ADDON_VALUES_ARTIFACT").unwrap();
    let verified = verify_artifact(Arc::from(fs::read(path).unwrap()), ArtifactLimits::default()).unwrap();
    let data_schema = schema("fixturevalues.DataReply", vec![("reply", HostValueType::Record, Some(reply()))]);
    let mixed_schema = reply();
    let scalar_schema = schema("fixturevalues.ScalarReply", vec![("point", HostValueType::Record, Some(vector())), ("tick", HostValueType::I64, None)]);
    let tiny_schema = schema("fixturevalues.Tiny", vec![("number", HostValueType::I32, None)]);
    let operations = [OperationSchema::asynchronous_record(&[], &data_schema), OperationSchema::asynchronous_record(&[], &mixed_schema), OperationSchema::asynchronous_record(&[], &scalar_schema), OperationSchema::asynchronous_record(&[], &tiny_schema)];
    for budget in [64, 512] {
        let mut session = Session::admit(verified.clone(), list_no_io_profile(), &[CapabilityBinding::new("fixturevalues", "fixturevalues", 1, 0, &operations)]).unwrap();
        session.start(&[]).unwrap();
        let mut counts = [0; 4];
        let mut finished = false;
        for _ in 0..10000 {
            match session.advance(budget, 1).unwrap() {
                AdvanceOutcome::SliceExhausted => (),
                AdvanceOutcome::HostRequestBatch(batch) => {
                    assert_eq!(1, batch.len());
                    let request = batch.get(0).unwrap();
                    let operation = request.operation() as usize;
                    let id = request.id();
                    let task = request.task_id();
                    counts[operation] += 1;
                    let record = match operation {
                        0 => value("fixturevalues.DataReply", vec![("reply", nested(reply_value()))]),
                        1 => reply_value(),
                        2 => value("fixturevalues.ScalarReply", vec![("point", vector_value()), ("tick", HostRecordScalar::I64(i64::MIN))]),
                        3 => value("fixturevalues.Tiny", vec![("number", HostRecordScalar::I32(42))]),
                        _ => unreachable!(),
                    };
                    session.resume_for(task, id, HostResponse::Success(HostValueInput::Record(&record))).unwrap();
                },
                AdvanceOutcome::Halted(None) => { finished = true; break; },
                other => panic!("value response failed: {other:?}"),
            }
        }
        assert!(finished);
        assert_eq!([1, 1, 100, 1], counts);
    }
}
