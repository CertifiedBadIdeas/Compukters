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
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use k16_vm::compiled_c::artifact::{
    load_compiled_c_artifact, validate_artifact_pair, CompiledCArtifact, CompiledCCandidate,
};
use k16_vm::compiled_c::runner::run_compiled_c;
use k16_vm::isa_benchmarks::{native_checksum, IsaBenchmarkWorkload};
use k16_vm::k16_f32::encoding::{addi, halt, jump, load32, materialize, ret, store32};
use k16_vm::k16_f32r32::encoding as f32r32;
use k16_vm::rv32im::encoding::{ebreak, ecall, jalr};

struct FixtureDirectory(PathBuf);

impl FixtureDirectory {
    fn new() -> Self {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "k16-vm-compiled-c-runner-{}-{unique}",
            std::process::id()
        ));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}

impl Drop for FixtureDirectory {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}

fn repository_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf()
}

fn compile_corpus(iterations: u32) -> FixtureDirectory {
    let root = repository_root();
    let artifacts = FixtureDirectory::new();
    let output = Command::new("bash")
        .arg(root.join("scripts/compile-isa-gate2-corpus.sh"))
        .arg(&artifacts.0)
        .arg(iterations.to_string())
        .env(
            "K16_LLVM_BIN_DIR",
            root.join(".toolchain/build/llvm/k16-min/bin"),
        )
        .env(
            "ISA_GATE2_CHECKSUM_BIN",
            env!("CARGO_BIN_EXE_isa_gate2_checksum"),
        )
        .env(
            "ISA_GATE2_K16_ASSEMBLER_BIN",
            env!("CARGO_BIN_EXE_k16_f32_assemble"),
        )
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "compiler failed:\nstdout:\n{}\nstderr:\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    artifacts
}

#[test]
fn every_compiled_workload_matches_manifest_and_native_oracle() {
    let iterations = 17;
    let artifacts = compile_corpus(iterations);
    for workload in IsaBenchmarkWorkload::all().iter().copied().take(6) {
        let directory = artifacts.0.join(workload.name());
        let k16 = load_compiled_c_artifact(&directory.join("k16-f32.manifest")).unwrap();
        let rv = load_compiled_c_artifact(&directory.join("rv32im.manifest")).unwrap();
        validate_artifact_pair(&k16, &rv).unwrap();
        let expected = native_checksum(workload, iterations);
        assert_eq!(k16.expected_checksum, expected);
        assert_eq!(rv.expected_checksum, expected);
        for artifact in [&k16, &rv] {
            let observation = run_compiled_c(artifact, iterations, 10_000_000).unwrap();
            assert_eq!(observation.workload, workload);
            assert_eq!(observation.candidate, artifact.candidate);
            assert_eq!(observation.checksum, expected);
            assert!(observation.retired_instructions > 0);
            assert_eq!(observation.code_bytes, artifact.code_bytes);
            assert_eq!(observation.instruction_count, artifact.instruction_count);
            assert_eq!(
                observation.data_read_bytes % 4,
                0,
                "{}",
                artifact.candidate.name()
            );
            assert_eq!(
                observation.data_written_bytes % 4,
                0,
                "{}",
                artifact.candidate.name()
            );
        }
    }
}

fn artifact(candidate: CompiledCCandidate, words: &[u32], code_words: usize) -> CompiledCArtifact {
    CompiledCArtifact {
        workload: IsaBenchmarkWorkload::BranchMix,
        candidate,
        source_sha256: "a".repeat(64),
        canonical_ir_sha256: "b".repeat(64),
        image_sha256: "c".repeat(64),
        image_base: 0x1000,
        entry_offset: 0,
        stop_offset: (code_words * 4) as u32,
        code_bytes: code_words * 4,
        instruction_count: code_words as u32,
        validation_iterations: 1,
        expected_checksum: 1,
        image: words.iter().copied().flat_map(u32::to_le_bytes).collect(),
    }
}

#[test]
fn harness_installs_architecture_specific_return_addresses() {
    let k16 = artifact(
        CompiledCCandidate::K16F32,
        &[addi(0, 1, 0), ret(), halt()],
        2,
    );
    let rv = artifact(CompiledCCandidate::Rv32im, &[jalr(0, 1, 0), ebreak()], 1);
    let rv64 = artifact(CompiledCCandidate::Rv64im, &[jalr(0, 1, 0), ebreak()], 1);
    let f32r32 = artifact(
        CompiledCCandidate::K16F32R32LR,
        &[f32r32::addi(0, 0, 0), f32r32::ret(), f32r32::halt()],
        2,
    );

    let k16_observation = run_compiled_c(&k16, 1, 4).unwrap();
    let rv_observation = run_compiled_c(&rv, 1, 4).unwrap();
    let rv64_observation = run_compiled_c(&rv64, 1, 4).unwrap();
    let f32r32_observation = run_compiled_c(&f32r32, 1, 4).unwrap();
    assert_eq!(k16_observation.checksum, 1);
    assert_eq!(rv_observation.checksum, 1);
    assert_eq!(rv64_observation.checksum, 1);
    assert_eq!(f32r32_observation.checksum, 1);
    assert_eq!(k16_observation.data_read_bytes, 4);
    assert_eq!(k16_observation.data_written_bytes, 0);
    assert_eq!(rv_observation.data_read_bytes, 0);
    assert_eq!(rv_observation.data_written_bytes, 0);
    assert_eq!(rv64_observation.data_read_bytes, 0);
    assert_eq!(rv64_observation.data_written_bytes, 0);
    assert_eq!(
        rv64_observation.cpu_state_bytes,
        k16_vm::rv64im::Rv64imCpu::cpu_state_bytes()
    );
    assert_eq!(f32r32_observation.data_read_bytes, 0);
    assert_eq!(f32r32_observation.data_written_bytes, 0);
    assert_eq!(
        f32r32_observation.cpu_state_bytes,
        k16_vm::k16_f32r32::K16F32R32Cpu::cpu_state_bytes()
    );
}

#[test]
fn harness_rejects_wrong_stop_pc_and_instruction_limit() {
    let early_stop = artifact(CompiledCCandidate::K16F32, &[halt(), halt()], 1);
    assert!(run_compiled_c(&early_stop, 1, 4)
        .unwrap_err()
        .contains("stop PC"));

    let looped = artifact(CompiledCCandidate::K16F32, &[jump(-1), halt()], 1);
    assert!(run_compiled_c(&looped, 1, 3)
        .unwrap_err()
        .contains("instruction limit"));
}

#[test]
fn harness_rejects_code_mutation_and_has_no_mmio_mapping() {
    let [upper, lower] = materialize(2, 0x1000);
    let code_write = artifact(
        CompiledCCandidate::K16F32,
        &[upper, lower, store32(2, 1, 0), ret(), halt()],
        4,
    );
    assert!(run_compiled_c(&code_write, 1, 16)
        .unwrap_err()
        .contains("immutable code"));

    let [upper, lower] = materialize(2, 0xffff_0000);
    let mmio_read = artifact(
        CompiledCCandidate::K16F32,
        &[upper, lower, load32(0, 2, 0), ret(), halt()],
        4,
    );
    assert!(run_compiled_c(&mmio_read, 1, 16)
        .unwrap_err()
        .contains("outside 131072 bytes"));
}

#[test]
fn harness_rejects_noncanonical_stop_reason() {
    let ecall_program = artifact(CompiledCCandidate::Rv32im, &[ecall(), ebreak()], 1);
    assert!(run_compiled_c(&ecall_program, 1, 4)
        .unwrap_err()
        .contains("stop reason"));
}
