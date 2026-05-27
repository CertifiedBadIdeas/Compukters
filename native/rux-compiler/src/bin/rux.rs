use rux_compiler::artifact::Rux16ArtifactTarget;
use rux_compiler::{compile_rux16_artifact, rux16_disasm, volume};
use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    match run(env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::from(1)
        }
    }
}

fn run(args: Vec<String>) -> Result<(), String> {
    let Some(command) = args.first() else {
        return usage_error();
    };
    match command.as_str() {
        "compile" => run_compile(&args[1..]),
        "disasm" | "disassemble" => run_disasm(&args[1..]),
        "volume" => run_volume(&args[1..]),
        _ => usage_error(),
    }
}

fn run_compile(args: &[String]) -> Result<(), String> {
    let config = parse_compile_args(args)?;
    let source = fs::read_to_string(&config.source_path)
        .map_err(|error| format!("failed to read {}: {error}", config.source_path))?;
    let artifact = compile_rux16_artifact(&source, config.target)
        .map_err(|error| format!("compile error: {}", error.message))?;
    fs::write(&config.output_path, artifact.bytes)
        .map_err(|error| format!("failed to write {}: {error}", config.output_path))
}

fn run_disasm(args: &[String]) -> Result<(), String> {
    let config = parse_disasm_args(args)?;
    let bytes = fs::read(&config.input_path)
        .map_err(|error| format!("failed to read {}: {error}", config.input_path))?;
    let disassembly = rux16_disasm::disassemble_artifact(&bytes, config.target)?;
    print!("{disassembly}");
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DisasmConfig {
    target: Rux16ArtifactTarget,
    input_path: String,
}

fn parse_disasm_args(args: &[String]) -> Result<DisasmConfig, String> {
    let mut target = None;
    let mut input_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return disasm_usage_error();
                };
                target = Some(Rux16ArtifactTarget::parse(value)?);
                index += 2;
            }
            value if value.starts_with('-') => return disasm_usage_error(),
            value => {
                if input_path.is_some() {
                    return disasm_usage_error();
                }
                input_path = Some(value.to_string());
                index += 1;
            }
        }
    }
    Ok(DisasmConfig {
        target: target.ok_or_else(disasm_usage_message)?,
        input_path: input_path.ok_or_else(disasm_usage_message)?,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CompileConfig {
    target: Rux16ArtifactTarget,
    source_path: String,
    output_path: String,
}

fn parse_compile_args(args: &[String]) -> Result<CompileConfig, String> {
    let mut target = Rux16ArtifactTarget::Program;
    let mut source_path = None;
    let mut output_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return compile_usage_error();
                };
                target = Rux16ArtifactTarget::parse(value)?;
                index += 2;
            }
            "-o" => {
                let Some(value) = args.get(index + 1) else {
                    return compile_usage_error();
                };
                output_path = Some(value.clone());
                index += 2;
            }
            value if value.starts_with('-') => return compile_usage_error(),
            value => {
                if source_path.is_some() {
                    return compile_usage_error();
                }
                source_path = Some(value.to_string());
                index += 1;
            }
        }
    }
    Ok(CompileConfig {
        target,
        source_path: source_path.ok_or_else(compile_usage_message)?,
        output_path: output_path.ok_or_else(compile_usage_message)?,
    })
}

fn run_volume(args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return volume_usage_error();
    };
    match command.as_str() {
        "create" => {
            if args.len() != 4 || args[2] != "--size" {
                return volume_usage_error();
            }
            let size = parse_size(&args[3])?;
            let bytes = volume::create_empty_volume(size)?;
            fs::write(&args[1], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "put-boot" => {
            if args.len() != 3 {
                return volume_usage_error();
            }
            let mut volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let boot_bytes = fs::read(&args[2])
                .map_err(|error| format!("failed to read {}: {error}", args[2]))?;
            volume::put_boot(&mut volume_bytes, &boot_bytes)?;
            fs::write(&args[1], volume_bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        _ => volume_usage_error(),
    }
}

fn parse_size(value: &str) -> Result<usize, String> {
    let size = value
        .parse::<usize>()
        .map_err(|_| format!("invalid size `{value}`; expected byte count"))?;
    if size == 0 {
        return Err("size must be positive".to_string());
    }
    Ok(size)
}

fn usage_error() -> Result<(), String> {
    Err("usage: rux compile [--target <bios|boot|kernel|program>] <input.rx> -o <output>\n       rux disasm --target <bios|boot|kernel|program> <input>\n       rux volume <create|put-boot> ...".to_string())
}

fn compile_usage_error() -> Result<CompileConfig, String> {
    Err(compile_usage_message())
}

fn compile_usage_message() -> String {
    "usage: rux compile [--target <bios|boot|kernel|program>] <input.rx> -o <output>".to_string()
}

fn disasm_usage_error() -> Result<DisasmConfig, String> {
    Err(disasm_usage_message())
}

fn disasm_usage_message() -> String {
    "usage: rux disasm --target <bios|boot|kernel|program> <input>".to_string()
}

fn volume_usage_error() -> Result<(), String> {
    Err(
        "usage: rux volume create <volume.ruxvol> --size <bytes>\n       rux volume put-boot <volume.ruxvol> <boot.bin>"
            .to_string(),
    )
}
