/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use k16_vm::compiled_c::artifact::{
    load_compiled_c_artifact, validate_artifact_pair, validate_f32r32_artifact_pair,
    CompiledCArtifact, CompiledCCandidate,
};
use k16_vm::isa_benchmarks::IsaBenchmarkWorkload;

struct FixtureDirectory(PathBuf);

impl FixtureDirectory {
    fn new() -> Self {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "k16-vm-compiled-c-artifact-{}-{unique}",
            std::process::id()
        ));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for FixtureDirectory {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}

fn valid_manifest(extra: &str) -> String {
    format!(
        "schema=1\nworkload=branch-mix\ncandidate=k16-f32\nsource_sha256={}\ncanonical_ir_sha256={}\nimage_sha256=8bf968098993c8f1b1db946e44a1b0230d26759b4245726bc955351871cd4ab0\nimage_path=image.bin\nimage_base=4096\nentry_offset=0\nstop_offset=4\ncode_bytes=4\nimage_bytes=8\ninstruction_count=1\nvalidation_iterations=17\nexpected_checksum=35\n{extra}",
        "a".repeat(64),
        "b".repeat(64),
    )
}

fn write_valid_fixture(directory: &Path) -> PathBuf {
    std::fs::write(directory.join("image.bin"), [0, 0, 0, 0x44, 0, 0, 0, 1]).unwrap();
    let manifest = directory.join("artifact.manifest");
    std::fs::write(&manifest, valid_manifest("")).unwrap();
    manifest
}

#[test]
fn loads_owned_artifact_from_strict_manifest() {
    let directory = FixtureDirectory::new();
    let manifest = write_valid_fixture(directory.path());

    let artifact = load_compiled_c_artifact(&manifest).unwrap();

    assert_eq!(artifact.workload, IsaBenchmarkWorkload::BranchMix);
    assert_eq!(artifact.candidate, CompiledCCandidate::K16F32);
    assert_eq!(artifact.image_base, 4096);
    assert_eq!(artifact.entry_offset, 0);
    assert_eq!(artifact.stop_offset, 4);
    assert_eq!(artifact.code_bytes, 4);
    assert_eq!(artifact.instruction_count, 1);
    assert_eq!(artifact.validation_iterations, 17);
    assert_eq!(artifact.expected_checksum, 35);
    assert_eq!(artifact.image.len(), 8);
}

#[test]
fn loads_k16_f32r32_candidate_identity() {
    let directory = FixtureDirectory::new();
    let manifest = write_valid_fixture(directory.path());
    std::fs::write(
        &manifest,
        valid_manifest("").replace("candidate=k16-f32", "candidate=k16-f32r32-lr"),
    )
    .unwrap();

    let artifact = load_compiled_c_artifact(&manifest).unwrap();

    assert_eq!(artifact.candidate, CompiledCCandidate::K16F32R32LR);
    assert_eq!(artifact.candidate.name(), "k16-f32r32-lr");
}

#[test]
fn rejects_missing_duplicate_unknown_and_noncanonical_fields() {
    let cases = [
        (valid_manifest("").replace("schema=1\n", ""), "missing key"),
        (format!("{}schema=1\n", valid_manifest("")), "duplicate key"),
        (valid_manifest("surprise=value\n"), "unknown key"),
        (
            valid_manifest("").replace(&"a".repeat(64), "not-hex"),
            "source_sha256",
        ),
        (
            valid_manifest("").replace("image_base=4096", "image_base= 4096"),
            "image_base",
        ),
    ];
    for (manifest_text, expected) in cases {
        let directory = FixtureDirectory::new();
        let manifest = write_valid_fixture(directory.path());
        std::fs::write(&manifest, manifest_text).unwrap();
        let error = load_compiled_c_artifact(&manifest).unwrap_err();
        assert!(
            error.contains(expected),
            "{error:?} did not contain {expected:?}"
        );
    }
}

#[test]
fn rejects_path_escape_and_image_digest_mismatch() {
    let root = FixtureDirectory::new();
    let manifest_directory = root.path().join("manifests");
    std::fs::create_dir(&manifest_directory).unwrap();
    std::fs::write(root.path().join("outside.bin"), [0_u8; 8]).unwrap();
    let escaped = manifest_directory.join("escaped.manifest");
    std::fs::write(
        &escaped,
        valid_manifest("").replace("image_path=image.bin", "image_path=../outside.bin"),
    )
    .unwrap();
    assert!(load_compiled_c_artifact(&escaped)
        .unwrap_err()
        .contains("escapes manifest directory"));

    let manifest = write_valid_fixture(root.path());
    std::fs::write(root.path().join("image.bin"), [1, 0, 0, 0x44, 0, 0, 0, 1]).unwrap();
    assert!(load_compiled_c_artifact(&manifest)
        .unwrap_err()
        .contains("SHA-256"));
}

#[test]
fn rejects_invalid_image_boundaries_and_sizes() {
    let cases = [
        ("entry_offset=0", "entry_offset=4", "entry_offset"),
        ("stop_offset=4", "stop_offset=8", "stop_offset"),
        ("code_bytes=4", "code_bytes=3", "four-byte"),
        ("image_bytes=8", "image_bytes=7", "image_bytes"),
    ];
    for (from, to, expected) in cases {
        let directory = FixtureDirectory::new();
        let manifest = write_valid_fixture(directory.path());
        std::fs::write(&manifest, valid_manifest("").replace(from, to)).unwrap();
        let error = load_compiled_c_artifact(&manifest).unwrap_err();
        assert!(
            error.contains(expected),
            "{error:?} did not contain {expected:?}"
        );
    }
}

fn artifact(candidate: CompiledCCandidate, canonical_ir_sha256: &str) -> CompiledCArtifact {
    CompiledCArtifact {
        workload: IsaBenchmarkWorkload::BranchMix,
        candidate,
        source_sha256: "a".repeat(64),
        canonical_ir_sha256: canonical_ir_sha256.to_string(),
        image_sha256: "c".repeat(64),
        image_base: 4096,
        entry_offset: 0,
        stop_offset: 4,
        code_bytes: 4,
        instruction_count: 1,
        validation_iterations: 17,
        expected_checksum: 35,
        image: vec![0; 8],
    }
}

#[test]
fn artifact_pair_requires_distinct_candidates_and_identical_canonical_ir() {
    let k16 = artifact(CompiledCCandidate::K16F32, &"b".repeat(64));
    let rv = artifact(CompiledCCandidate::Rv32im, &"b".repeat(64));
    validate_artifact_pair(&k16, &rv).unwrap();

    let mismatched = artifact(CompiledCCandidate::Rv32im, &"d".repeat(64));
    assert!(validate_artifact_pair(&k16, &mismatched)
        .unwrap_err()
        .contains("canonical IR"));
    assert!(validate_artifact_pair(&k16, &k16)
        .unwrap_err()
        .contains("candidate"));
}

#[test]
fn f32r32_artifact_pair_accepts_only_the_new_candidate_then_rv32im() {
    let candidate = artifact(CompiledCCandidate::K16F32R32LR, &"b".repeat(64));
    let old_k16 = artifact(CompiledCCandidate::K16F32, &"b".repeat(64));
    let rv = artifact(CompiledCCandidate::Rv32im, &"b".repeat(64));

    validate_f32r32_artifact_pair(&candidate, &rv).unwrap();
    assert!(validate_f32r32_artifact_pair(&old_k16, &rv)
        .unwrap_err()
        .contains("k16-f32r32-lr then rv32im"));
    assert!(validate_f32r32_artifact_pair(&candidate, &candidate)
        .unwrap_err()
        .contains("candidate"));
}
