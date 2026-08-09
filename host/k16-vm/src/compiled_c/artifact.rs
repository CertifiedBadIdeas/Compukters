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

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use crate::isa_benchmarks::IsaBenchmarkWorkload;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CompiledCCandidate {
    K16F32,
    K16F32R32LR,
    Rv32im,
    Rv64im,
}

impl CompiledCCandidate {
    pub const fn name(self) -> &'static str {
        match self {
            Self::K16F32 => "k16-f32",
            Self::K16F32R32LR => "k16-f32r32-lr",
            Self::Rv32im => "rv32im",
            Self::Rv64im => "rv64im",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompiledCArtifact {
    pub workload: IsaBenchmarkWorkload,
    pub candidate: CompiledCCandidate,
    pub source_sha256: String,
    pub canonical_ir_sha256: String,
    pub image_sha256: String,
    pub image_base: u32,
    pub entry_offset: u32,
    pub stop_offset: u32,
    pub code_bytes: usize,
    pub instruction_count: u32,
    pub validation_iterations: u32,
    pub expected_checksum: u32,
    pub image: Vec<u8>,
}

pub fn load_compiled_c_artifact(manifest_path: &Path) -> Result<CompiledCArtifact, String> {
    let manifest = std::fs::read_to_string(manifest_path)
        .map_err(|error| format!("cannot read manifest {}: {error}", manifest_path.display()))?;
    let mut fields = parse_manifest(&manifest)?;
    let schema = take(&mut fields, "schema")?;
    if schema != "1" {
        return Err(format!("unsupported compiled-C manifest schema {schema:?}"));
    }
    let workload = parse_workload(&take(&mut fields, "workload")?)?;
    let candidate = match take(&mut fields, "candidate")?.as_str() {
        "k16-f32" => CompiledCCandidate::K16F32,
        "k16-f32r32-lr" => CompiledCCandidate::K16F32R32LR,
        "rv32im" => CompiledCCandidate::Rv32im,
        "rv64im" => CompiledCCandidate::Rv64im,
        candidate => return Err(format!("unknown compiled-C candidate {candidate:?}")),
    };
    let source_sha256 = take_hash(&mut fields, "source_sha256")?;
    let canonical_ir_sha256 = take_hash(&mut fields, "canonical_ir_sha256")?;
    let image_sha256 = take_hash(&mut fields, "image_sha256")?;
    let image_path = take(&mut fields, "image_path")?;
    let image_base = take_number(&mut fields, "image_base")?;
    let entry_offset = take_number(&mut fields, "entry_offset")?;
    let stop_offset = take_number(&mut fields, "stop_offset")?;
    let code_bytes = take_number(&mut fields, "code_bytes")?;
    let image_bytes: usize = take_number(&mut fields, "image_bytes")?;
    let instruction_count = take_number(&mut fields, "instruction_count")?;
    let validation_iterations = take_number(&mut fields, "validation_iterations")?;
    let expected_checksum = take_number(&mut fields, "expected_checksum")?;
    for optional in [
        "clang_version",
        "opt_version",
        "llc_version",
        "k16_llc_version",
    ] {
        fields.remove(optional);
    }
    if let Some(key) = fields.keys().next() {
        return Err(format!("unknown key {key:?} in compiled-C manifest"));
    }

    let image = load_image(manifest_path, &image_path)?;
    validate_image_shape(
        candidate,
        &image,
        image_base,
        entry_offset,
        stop_offset,
        code_bytes,
        image_bytes,
        instruction_count,
    )?;
    if validation_iterations == 0 {
        return Err("validation_iterations must be positive".to_string());
    }
    let actual_image_sha256 = sha256_hex(&image);
    if actual_image_sha256 != image_sha256 {
        return Err(format!(
            "image SHA-256 mismatch: manifest {image_sha256}, actual {actual_image_sha256}"
        ));
    }

    Ok(CompiledCArtifact {
        workload,
        candidate,
        source_sha256,
        canonical_ir_sha256,
        image_sha256,
        image_base,
        entry_offset,
        stop_offset,
        code_bytes,
        instruction_count,
        validation_iterations,
        expected_checksum,
        image,
    })
}

pub fn validate_artifact_pair(
    k16: &CompiledCArtifact,
    rv: &CompiledCArtifact,
) -> Result<(), String> {
    if k16.candidate != CompiledCCandidate::K16F32 || rv.candidate != CompiledCCandidate::Rv32im {
        return Err(format!(
            "artifact pair candidate order must be k16-f32 then rv32im, got {} then {}",
            k16.candidate.name(),
            rv.candidate.name()
        ));
    }
    validate_artifact_pair_metadata(k16, rv)
}

pub fn validate_f32r32_artifact_pair(
    candidate: &CompiledCArtifact,
    rv: &CompiledCArtifact,
) -> Result<(), String> {
    if candidate.candidate != CompiledCCandidate::K16F32R32LR
        || rv.candidate != CompiledCCandidate::Rv32im
    {
        return Err(format!(
            "artifact pair candidate order must be k16-f32r32-lr then rv32im, got {} then {}",
            candidate.candidate.name(),
            rv.candidate.name()
        ));
    }
    validate_artifact_pair_metadata(candidate, rv)
}

pub fn validate_riscv_xlen_artifact_pair(
    rv32: &CompiledCArtifact,
    rv64: &CompiledCArtifact,
) -> Result<(), String> {
    if rv32.candidate != CompiledCCandidate::Rv32im || rv64.candidate != CompiledCCandidate::Rv64im
    {
        return Err(format!(
            "artifact pair candidate order must be rv32im then rv64im, got {} then {}",
            rv32.candidate.name(),
            rv64.candidate.name()
        ));
    }
    validate_riscv_xlen_pair_metadata(rv32, rv64)
}

fn validate_riscv_xlen_pair_metadata(
    rv32: &CompiledCArtifact,
    rv64: &CompiledCArtifact,
) -> Result<(), String> {
    if rv32.workload != rv64.workload {
        return Err("artifact pair workload mismatch".to_string());
    }
    if rv32.source_sha256 != rv64.source_sha256 {
        return Err("artifact pair source SHA-256 mismatch".to_string());
    }
    if rv32.validation_iterations != rv64.validation_iterations {
        return Err("artifact pair validation iteration mismatch".to_string());
    }
    if rv32.expected_checksum != rv64.expected_checksum {
        return Err("artifact pair expected checksum mismatch".to_string());
    }
    Ok(())
}

fn validate_artifact_pair_metadata(
    k16: &CompiledCArtifact,
    rv: &CompiledCArtifact,
) -> Result<(), String> {
    if k16.workload != rv.workload {
        return Err("artifact pair workload mismatch".to_string());
    }
    if k16.source_sha256 != rv.source_sha256 {
        return Err("artifact pair source SHA-256 mismatch".to_string());
    }
    if k16.canonical_ir_sha256 != rv.canonical_ir_sha256 {
        return Err("artifact pair canonical IR SHA-256 mismatch".to_string());
    }
    if k16.validation_iterations != rv.validation_iterations {
        return Err("artifact pair validation iteration mismatch".to_string());
    }
    if k16.expected_checksum != rv.expected_checksum {
        return Err("artifact pair expected checksum mismatch".to_string());
    }
    Ok(())
}

fn parse_manifest(manifest: &str) -> Result<BTreeMap<String, String>, String> {
    let mut fields = BTreeMap::new();
    for (index, line) in manifest.lines().enumerate() {
        if line.is_empty() {
            continue;
        }
        let (key, value) = line
            .split_once('=')
            .ok_or_else(|| format!("manifest line {} is not key=value", index + 1))?;
        if key.is_empty() {
            return Err(format!("manifest line {} has an empty key", index + 1));
        }
        if fields.insert(key.to_string(), value.to_string()).is_some() {
            return Err(format!("duplicate key {key:?} in compiled-C manifest"));
        }
    }
    Ok(fields)
}

fn take(fields: &mut BTreeMap<String, String>, key: &str) -> Result<String, String> {
    fields
        .remove(key)
        .ok_or_else(|| format!("missing key {key:?} in compiled-C manifest"))
}

fn take_hash(fields: &mut BTreeMap<String, String>, key: &str) -> Result<String, String> {
    let value = take(fields, key)?;
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(format!("{key} must be 64 lowercase hexadecimal characters"));
    }
    Ok(value)
}

fn take_number<T>(fields: &mut BTreeMap<String, String>, key: &str) -> Result<T, String>
where
    T: std::str::FromStr,
    T::Err: std::fmt::Display,
{
    let value = take(fields, key)?;
    value
        .parse::<T>()
        .map_err(|error| format!("invalid {key} {value:?}: {error}"))
}

fn parse_workload(name: &str) -> Result<IsaBenchmarkWorkload, String> {
    IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .take(6)
        .find(|workload| workload.name() == name)
        .ok_or_else(|| format!("unknown compiled-C workload {name:?}"))
}

fn load_image(manifest_path: &Path, image_path: &str) -> Result<Vec<u8>, String> {
    let relative = PathBuf::from(image_path);
    if relative.is_absolute() {
        return Err(format!("image_path {image_path:?} is not relative"));
    }
    let parent = manifest_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .canonicalize()
        .map_err(|error| format!("cannot canonicalize manifest directory: {error}"))?;
    let resolved = parent.join(relative).canonicalize().map_err(|error| {
        format!("cannot canonicalize image_path {image_path:?} relative to manifest: {error}")
    })?;
    if !resolved.starts_with(&parent) {
        return Err(format!(
            "image_path {image_path:?} escapes manifest directory {}",
            parent.display()
        ));
    }
    std::fs::read(&resolved).map_err(|error| {
        format!(
            "cannot read compiled-C image {}: {error}",
            resolved.display()
        )
    })
}

#[allow(clippy::too_many_arguments)]
fn validate_image_shape(
    candidate: CompiledCCandidate,
    image: &[u8],
    image_base: u32,
    entry_offset: u32,
    stop_offset: u32,
    code_bytes: usize,
    image_bytes: usize,
    instruction_count: u32,
) -> Result<(), String> {
    if !image_base.is_multiple_of(4) {
        return Err("image_base must be four-byte aligned".to_string());
    }
    if image_bytes != image.len() {
        return Err(format!(
            "image_bytes {image_bytes} does not match image length {}",
            image.len()
        ));
    }
    if code_bytes == 0 || !code_bytes.is_multiple_of(4) {
        return Err("code_bytes must be a positive four-byte multiple".to_string());
    }
    if !entry_offset.is_multiple_of(4) || entry_offset as usize >= code_bytes {
        return Err(format!(
            "entry_offset {entry_offset} is not an aligned instruction inside {code_bytes} code bytes"
        ));
    }
    if stop_offset as usize != code_bytes {
        return Err(format!(
            "stop_offset {stop_offset} does not equal code_bytes {code_bytes}"
        ));
    }
    if image.len() != code_bytes + 4 {
        return Err(format!(
            "image length {} must contain code_bytes {code_bytes} plus one stop instruction",
            image.len()
        ));
    }
    if instruction_count as usize != code_bytes / 4 {
        return Err(format!(
            "instruction_count {instruction_count} does not match code_bytes {code_bytes}"
        ));
    }
    let last_offset = u32::try_from(image.len() - 4)
        .map_err(|_| "compiled-C image exceeds u32 address space".to_string())?;
    image_base
        .checked_add(last_offset)
        .ok_or_else(|| "compiled-C image address range overflows u32".to_string())?;
    let expected_stop = match candidate {
        CompiledCCandidate::K16F32 => crate::k16_f32::encoding::halt(),
        CompiledCCandidate::K16F32R32LR => crate::k16_f32r32::encoding::halt(),
        CompiledCCandidate::Rv32im => crate::rv32im::encoding::ebreak(),
        CompiledCCandidate::Rv64im => 0x0010_0073,
    };
    let actual_stop = u32::from_le_bytes(image[code_bytes..].try_into().unwrap());
    if actual_stop != expected_stop {
        return Err(format!(
            "{} image has wrong stop instruction {actual_stop:#010x}",
            candidate.name()
        ));
    }
    Ok(())
}

fn sha256_hex(input: &[u8]) -> String {
    const INITIAL: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    const ROUND: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];

    let bit_len = (input.len() as u64).wrapping_mul(8);
    let mut padded = input.to_vec();
    padded.push(0x80);
    while padded.len() % 64 != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());
    let mut hash = INITIAL;
    for chunk in padded.chunks_exact(64) {
        let mut words = [0_u32; 64];
        for (index, bytes) in chunk.chunks_exact(4).enumerate() {
            words[index] = u32::from_be_bytes(bytes.try_into().unwrap());
        }
        for index in 16..64 {
            let s0 = words[index - 15].rotate_right(7)
                ^ words[index - 15].rotate_right(18)
                ^ (words[index - 15] >> 3);
            let s1 = words[index - 2].rotate_right(17)
                ^ words[index - 2].rotate_right(19)
                ^ (words[index - 2] >> 10);
            words[index] = words[index - 16]
                .wrapping_add(s0)
                .wrapping_add(words[index - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = hash;
        for index in 0..64 {
            let sigma1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let choose = (e & f) ^ (!e & g);
            let temporary1 = h
                .wrapping_add(sigma1)
                .wrapping_add(choose)
                .wrapping_add(ROUND[index])
                .wrapping_add(words[index]);
            let sigma0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let majority = (a & b) ^ (a & c) ^ (b & c);
            let temporary2 = sigma0.wrapping_add(majority);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temporary1);
            d = c;
            c = b;
            b = a;
            a = temporary1.wrapping_add(temporary2);
        }
        for (state, value) in hash.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *state = state.wrapping_add(value);
        }
    }
    hash.iter().map(|word| format!("{word:08x}")).collect()
}
