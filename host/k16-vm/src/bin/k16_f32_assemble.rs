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

use std::path::PathBuf;

use k16_vm::compiled_c::k16_f32_assembler::assemble_k16_f32;

#[derive(Debug)]
struct Arguments {
    base: u32,
    entry: String,
    input: PathBuf,
    output: PathBuf,
    manifest: PathBuf,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("k16_f32_assemble: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = parse_arguments()?;
    let source = std::fs::read_to_string(&arguments.input)
        .map_err(|error| format!("cannot read {}: {error}", arguments.input.display()))?;
    let assembled = assemble_k16_f32(&source, arguments.base, &arguments.entry)?;
    std::fs::write(&arguments.output, &assembled.image)
        .map_err(|error| format!("cannot write {}: {error}", arguments.output.display()))?;
    let image_path = arguments
        .output
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| {
            format!(
                "output path is not a UTF-8 file name: {}",
                arguments.output.display()
            )
        })?;
    let manifest = format!(
        "schema=1\ncandidate=k16-f32\nimage_path={image_path}\nimage_base={}\nentry_offset={}\nstop_offset={}\ncode_bytes={}\nimage_bytes={}\ninstruction_count={}\n",
        arguments.base,
        assembled.entry_offset,
        assembled.stop_offset,
        assembled.stop_offset,
        assembled.image.len(),
        assembled.instruction_count,
    );
    std::fs::write(&arguments.manifest, manifest)
        .map_err(|error| format!("cannot write {}: {error}", arguments.manifest.display()))?;
    Ok(())
}

fn parse_arguments() -> Result<Arguments, String> {
    let mut base = None;
    let mut entry = None;
    let mut input = None;
    let mut output = None;
    let mut manifest = None;
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        let value = args
            .next()
            .ok_or_else(|| format!("missing value for {flag}"))?;
        match flag.as_str() {
            "--base" if base.is_none() => {
                base = Some(
                    value
                        .parse::<u32>()
                        .map_err(|error| format!("invalid --base {value:?}: {error}"))?,
                )
            }
            "--entry" if entry.is_none() => entry = Some(value),
            "--input" if input.is_none() => input = Some(PathBuf::from(value)),
            "--output" if output.is_none() => output = Some(PathBuf::from(value)),
            "--manifest" if manifest.is_none() => manifest = Some(PathBuf::from(value)),
            "--base" | "--entry" | "--input" | "--output" | "--manifest" => {
                return Err(format!("duplicate argument {flag}"));
            }
            _ => return Err(format!("unknown argument {flag}")),
        }
    }
    Ok(Arguments {
        base: base.ok_or("missing --base")?,
        entry: entry.ok_or("missing --entry")?,
        input: input.ok_or("missing --input")?,
        output: output.ok_or("missing --output")?,
        manifest: manifest.ok_or("missing --manifest")?,
    })
}
