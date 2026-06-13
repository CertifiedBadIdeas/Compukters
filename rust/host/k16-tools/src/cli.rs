use crate::artifact::K16ArtifactTarget;
use crate::{inspect, k16_disasm, k16_runtime, k16fs, object_link, volume};
use k16_vm::k16::K16Signal;
use k16_vm::k16_computer::K16ComputerHandle;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn run_k16_cli(args: Vec<String>) -> Result<(), String> {
    let Some(command) = args.first() else {
        return usage_error();
    };
    match command.as_str() {
        "link" => run_link(&args[1..]),
        "runtime" => run_runtime(&args[1..]),
        "run" => run_program(&args[1..]),
        "run-bios" => run_bios(&args[1..]),
        "disasm" | "disassemble" => run_disasm(&args[1..]),
        "inspect" => run_inspect(&args[1..]),
        "fs" => run_fs(&args[1..]),
        "volume" => run_volume(&args[1..]),
        _ => usage_error(),
    }
}

fn usage_error() -> Result<(), String> {
    Err("usage: k16 link [--target <boot|kernel|program>] <input.ko>... -o <output.kx>\n       k16 runtime <k16-startup|k16-memory-helpers|k16-cpu-helpers> -o <output.ko>\n       k16 run <program.kx>\n       k16 run-bios <bios.kflash>\n       k16 disasm --target <bios|boot|kernel|program> [--start <pc>] [--count <instructions>] <input>\n       k16 inspect <blob>\n       k16 volume <create|init|put-boot|put-kernel> ...\n       k16 fs <filesystem> ...".to_string())
}

fn run_program(args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return run_usage_error();
    }
    let program =
        fs::read(&args[0]).map_err(|error| format!("failed to read {}: {error}", args[0]))?;
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&[0x01, 0x00], 64 * 1024, 1_000_000)
        .map_err(|error| format!("failed to create K16 computer: {error}"))?;
    handle
        .exec_k16e_program_from_bytes(&program, 1_000_000)
        .map_err(|error| format!("failed to install K16E program: {error}"))?;
    let signal = handle
        .run_k16_until_signal()
        .map_err(|error| format!("failed to run K16 program: {error}"))?;
    println!(
        "signal={} debug_bytes={}",
        signal_name(signal),
        hex_bytes(handle.debug_output_bytes())
    );
    Ok(())
}

fn run_bios(args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return run_bios_usage_error();
    }
    let bios_flash =
        fs::read(&args[0]).map_err(|error| format!("failed to read {}: {error}", args[0]))?;
    let mut handle = K16ComputerHandle::create_k16_bios_flash(&bios_flash, 64 * 1024, 1_000_000)
        .map_err(|error| format!("failed to create K16 BIOS computer: {error}"))?;
    let signal = handle
        .run_k16_until_signal()
        .map_err(|error| format!("failed to run K16 BIOS: {error}"))?;
    let control = handle.control();
    println!(
        "signal={} status={} panic_code={} debug_text={}",
        signal_name(signal),
        control.status,
        control.panic_code,
        String::from_utf8_lossy(handle.debug_output_bytes()).escape_debug()
    );
    Ok(())
}

fn run_inspect(args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return inspect_usage_error();
    }
    let bytes =
        fs::read(&args[0]).map_err(|error| format!("failed to read {}: {error}", args[0]))?;
    print!("{}", inspect::inspect_blob(&bytes)?);
    Ok(())
}

fn run_link(args: &[String]) -> Result<(), String> {
    let config = parse_link_args(args)?;
    let input_bytes = config
        .input_paths
        .iter()
        .map(|path| {
            fs::read(path)
                .map(|bytes| (path.as_str(), bytes))
                .map_err(|error| format!("failed to read {path}: {error}"))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let inputs = input_bytes
        .iter()
        .map(|(path, bytes)| object_link::K16LinkInput { name: path, bytes })
        .collect::<Vec<_>>();
    let bytes = object_link::link_k16_objects_to_k16e(&inputs, config.target)?;
    fs::write(&config.output_path, bytes)
        .map_err(|error| format!("failed to write {}: {error}", config.output_path))
}

fn run_runtime(args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return runtime_usage_error();
    };
    match command.as_str() {
        "k16-startup" => {
            if args.len() != 3 || args[1] != "-o" {
                return runtime_usage_error();
            }
            let bytes = k16_runtime::k16_startup_object();
            fs::write(&args[2], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[2]))
        }
        "k16-memory-helpers" => {
            if args.len() != 3 || args[1] != "-o" {
                return runtime_usage_error();
            }
            build_k16_memory_helpers(Path::new(&args[2]))
        }
        "k16-cpu-helpers" => {
            if args.len() != 3 || args[1] != "-o" {
                return runtime_usage_error();
            }
            let bytes = k16_runtime::k16_cpu_helpers_object();
            fs::write(&args[2], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[2]))
        }
        _ => runtime_usage_error(),
    }
}

fn build_k16_memory_helpers(output_path: &Path) -> Result<(), String> {
    let rustc = env::var("K16_RUSTC")
        .map_err(|_| "K16_RUSTC must point to a custom K16 rustc".to_string())?;
    let rustc_path = PathBuf::from(&rustc);
    if !rustc_path.is_file() {
        return Err(format!(
            "K16_RUSTC must point to a custom K16 rustc: {}",
            rustc_path.display()
        ));
    }

    let llvm_bin_dir = env::var("K16_LLVM_BIN_DIR")
        .map_err(|_| "K16_LLVM_BIN_DIR must point to K16 LLVM tools".to_string())?;
    let llc_path = PathBuf::from(llvm_bin_dir).join("llc");
    if !llc_path.is_file() {
        return Err(format!(
            "K16_LLVM_BIN_DIR must contain K16 llc: {}",
            llc_path.display()
        ));
    }

    let target_spec = env::var("K16_RUST_TARGET_JSON")
        .map(PathBuf::from)
        .unwrap_or_else(|_| repo_root().join("tools/k16-unknown-kraftos.json"));
    if !target_spec.is_file() {
        return Err(format!(
            "K16 Rust target spec is missing: {}",
            target_spec.display()
        ));
    }

    let source = k16_rt_no_core_helpers_path();
    if !source.is_file() {
        return Err(format!(
            "K16 runtime helper source is missing: {}",
            source.display()
        ));
    }

    let _ = fs::remove_file(output_path);
    let ir_path = temp_runtime_path("k16-memory-helpers", "ll");
    let rustc_output = Command::new(&rustc_path)
        .args([
            "-Z",
            "unstable-options",
            "--edition=2021",
            "--target",
            target_spec.to_str().ok_or_else(|| {
                format!(
                    "K16 Rust target spec path is not UTF-8: {}",
                    target_spec.display()
                )
            })?,
            "-C",
            "panic=abort",
            "-C",
            "relocation-model=static",
            "-C",
            "overflow-checks=off",
            "--emit=llvm-ir",
            source.to_str().ok_or_else(|| {
                format!(
                    "K16 runtime helper source path is not UTF-8: {}",
                    source.display()
                )
            })?,
            "-o",
            ir_path.to_str().ok_or_else(|| {
                format!(
                    "K16 runtime helper IR path is not UTF-8: {}",
                    ir_path.display()
                )
            })?,
        ])
        .output()
        .map_err(|error| format!("failed to run {}: {error}", rustc_path.display()))?;

    if !rustc_output.status.success() {
        let _ = fs::remove_file(output_path);
        let _ = fs::remove_file(&ir_path);
        return Err(format!(
            "failed to compile K16 memory helpers to LLVM IR with {}:\n{}{}",
            rustc_path.display(),
            String::from_utf8_lossy(&rustc_output.stdout),
            String::from_utf8_lossy(&rustc_output.stderr)
        ));
    }

    let llc_output = Command::new(&llc_path)
        .args([
            "-mtriple=k16",
            "-filetype=obj",
            ir_path.to_str().ok_or_else(|| {
                format!(
                    "K16 runtime helper IR path is not UTF-8: {}",
                    ir_path.display()
                )
            })?,
            "-o",
            output_path.to_str().ok_or_else(|| {
                format!(
                    "K16 runtime helper output path is not UTF-8: {}",
                    output_path.display()
                )
            })?,
        ])
        .output()
        .map_err(|error| format!("failed to run {}: {error}", llc_path.display()))?;
    let _ = fs::remove_file(&ir_path);
    if !llc_output.status.success() {
        let _ = fs::remove_file(output_path);
        return Err(format!(
            "failed to lower K16 memory helpers with {}:\n{}{}",
            llc_path.display(),
            String::from_utf8_lossy(&llc_output.stdout),
            String::from_utf8_lossy(&llc_output.stderr)
        ));
    }
    Ok(())
}

fn k16_rt_no_core_helpers_path() -> PathBuf {
    repo_root().join("rust/guest/k16-rt/src/no_core_helpers.rs")
}

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .and_then(Path::parent)
        .expect("rust/host/k16-tools has repo root great-grandparent")
        .to_path_buf()
}

fn temp_runtime_path(stem: &str, extension: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time is after Unix epoch")
        .as_nanos();
    env::temp_dir().join(format!("{stem}-{}-{nanos}.{extension}", std::process::id()))
}

fn run_disasm(args: &[String]) -> Result<(), String> {
    let config = parse_disasm_args(args)?;
    let bytes = fs::read(&config.input_path)
        .map_err(|error| format!("failed to read {}: {error}", config.input_path))?;
    let disassembly = k16_disasm::disassemble_artifact(&bytes, config.target, config.options)?;
    print!("{disassembly}");
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DisasmConfig {
    target: K16ArtifactTarget,
    options: k16_disasm::DisassembleOptions,
    input_path: String,
}

fn parse_disasm_args(args: &[String]) -> Result<DisasmConfig, String> {
    let mut target = None;
    let mut start = None;
    let mut count = None;
    let mut input_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return disasm_usage_error();
                };
                target = Some(K16ArtifactTarget::parse(value)?);
                index += 2;
            }
            "--start" => {
                let Some(value) = args.get(index + 1) else {
                    return disasm_usage_error();
                };
                start = Some(parse_u32_number(value, "disassembly start")?);
                index += 2;
            }
            "--count" => {
                let Some(value) = args.get(index + 1) else {
                    return disasm_usage_error();
                };
                count = Some(parse_positive_usize(
                    value,
                    "disassembly instruction count",
                )?);
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
        options: k16_disasm::DisassembleOptions { start, count },
        input_path: input_path.ok_or_else(disasm_usage_message)?,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LinkConfig {
    target: K16ArtifactTarget,
    input_paths: Vec<String>,
    output_path: String,
}

fn parse_link_args(args: &[String]) -> Result<LinkConfig, String> {
    let mut target = K16ArtifactTarget::Program;
    let mut input_paths = Vec::new();
    let mut output_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return link_usage_error();
                };
                target = K16ArtifactTarget::parse(value)?;
                index += 2;
            }
            "-o" => {
                let Some(value) = args.get(index + 1) else {
                    return link_usage_error();
                };
                output_path = Some(value.clone());
                index += 2;
            }
            value if value.starts_with('-') => return link_usage_error(),
            value => {
                input_paths.push(value.to_string());
                index += 1;
            }
        }
    }
    if input_paths.is_empty() {
        return link_usage_error();
    }
    Ok(LinkConfig {
        target,
        input_paths,
        output_path: output_path.ok_or_else(link_usage_message)?,
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
        "init" => {
            if args.len() != 4 || args[2] != "--size" {
                return volume_usage_error();
            }
            let size = parse_size(&args[3])?;
            let bytes = volume::create_initialized_volume(size)?;
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
        "put-kernel" => {
            if args.len() != 3 {
                return volume_usage_error();
            }
            let mut volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let kernel_bytes = fs::read(&args[2])
                .map_err(|error| format!("failed to read {}: {error}", args[2]))?;
            volume::put_kernel(&mut volume_bytes, &kernel_bytes)?;
            fs::write(&args[1], volume_bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "extract-partition" => {
            if args.len() != 4 {
                return volume_usage_error();
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let partition_bytes = volume::extract_partition(&volume_bytes, &args[2])?;
            fs::write(&args[3], partition_bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[3]))
        }
        "replace-partition" => {
            if args.len() != 4 {
                return volume_usage_error();
            }
            let mut volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let partition_bytes = fs::read(&args[3])
                .map_err(|error| format!("failed to read {}: {error}", args[3]))?;
            volume::replace_partition(&mut volume_bytes, &args[2], &partition_bytes)?;
            fs::write(&args[1], volume_bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "inspect" => {
            if args.len() != 2 {
                return volume_usage_error();
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            print!("{}", volume::inspect(&volume_bytes)?);
            Ok(())
        }
        "inspect-boot" => {
            if args.len() != 2 {
                return volume_usage_error();
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            print!("{}", volume::inspect_boot_chain(&volume_bytes)?);
            Ok(())
        }
        _ => volume_usage_error(),
    }
}

fn run_fs(args: &[String]) -> Result<(), String> {
    let Some(filesystem) = args.first() else {
        return fs_usage_error();
    };
    match filesystem.as_str() {
        "kfs" => run_kfs(&args[1..]),
        other => Err(format!("unsupported filesystem `{other}`")),
    }
}

fn run_kfs(args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return fs_usage_error();
    };
    match command.as_str() {
        "format" => {
            if args.len() != 4 || args[2] != "--blocks" {
                return fs_usage_error();
            }
            let total_blocks = parse_blocks(&args[3])?;
            let bytes = k16fs::format_empty_filesystem(total_blocks)?;
            fs::write(&args[1], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "mkdir" => {
            if args.len() != 3 {
                return fs_usage_error();
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            k16fs::create_directory(&mut image, &args[2])?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "put" => {
            if args.len() != 4 {
                return fs_usage_error();
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let contents = fs::read(&args[3])
                .map_err(|error| format!("failed to read {}: {error}", args[3]))?;
            k16fs::write_file(&mut image, &args[2], &contents)?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "get" => {
            if args.len() != 4 {
                return fs_usage_error();
            }
            let image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let contents = k16fs::read_file(&image, &args[2])?;
            fs::write(&args[3], contents)
                .map_err(|error| format!("failed to write {}: {error}", args[3]))
        }
        "rm" => {
            if args.len() != 3 {
                return fs_usage_error();
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            k16fs::delete_file(&mut image, &args[2])?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "ls" => {
            if args.len() != 3 {
                return fs_usage_error();
            }
            let image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            for name in k16fs::list_directory(&image, &args[2])? {
                println!("{name}");
            }
            Ok(())
        }
        _ => fs_usage_error(),
    }
}

fn signal_name(signal: K16Signal) -> &'static str {
    match signal {
        K16Signal::Halt => "halt",
        K16Signal::Wait => "wait",
        K16Signal::Yield => "yield",
        K16Signal::StepLimitExceeded => "step-limit-exceeded",
    }
}

fn hex_bytes(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn parse_blocks(value: &str) -> Result<u32, String> {
    let blocks = value
        .parse::<u32>()
        .map_err(|_| format!("invalid block count `{value}`; expected u32"))?;
    if blocks == 0 {
        return Err("block count must be positive".to_string());
    }
    Ok(blocks)
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

fn parse_u32_number(value: &str, name: &str) -> Result<u32, String> {
    let digits = value
        .strip_prefix("0x")
        .or_else(|| value.strip_prefix("0X"));
    match digits {
        Some(hex) => u32::from_str_radix(hex, 16)
            .map_err(|_| format!("invalid {name} `{value}`; expected u32")),
        None => value
            .parse::<u32>()
            .map_err(|_| format!("invalid {name} `{value}`; expected u32")),
    }
}

fn parse_positive_usize(value: &str, name: &str) -> Result<usize, String> {
    let parsed = value
        .parse::<usize>()
        .map_err(|_| format!("invalid {name} `{value}`; expected positive integer"))?;
    if parsed == 0 {
        return Err(format!("{name} must be positive"));
    }
    Ok(parsed)
}

fn link_usage_error() -> Result<LinkConfig, String> {
    Err(link_usage_message())
}

fn link_usage_message() -> String {
    "usage: k16 link [--target <boot|kernel|program>] <input.ko>... -o <output.kx>".to_string()
}

fn runtime_usage_error() -> Result<(), String> {
    Err(
        "usage: k16 runtime <k16-startup|k16-memory-helpers|k16-cpu-helpers> -o <output.ko>"
            .to_string(),
    )
}

fn run_usage_error() -> Result<(), String> {
    Err("usage: k16 run <program.kx>".to_string())
}

fn run_bios_usage_error() -> Result<(), String> {
    Err("usage: k16 run-bios <bios.kflash>".to_string())
}

fn disasm_usage_error() -> Result<DisasmConfig, String> {
    Err(disasm_usage_message())
}

fn disasm_usage_message() -> String {
    "usage: k16 disasm --target <bios|boot|kernel|program> [--start <pc>] [--count <instructions>] <input>".to_string()
}

fn inspect_usage_error() -> Result<(), String> {
    Err("usage: k16 inspect <blob>".to_string())
}

fn volume_usage_error() -> Result<(), String> {
    Err("usage: k16 volume create <volume.kv> --size <bytes>\n       k16 volume init <volume.kv> --size <bytes>\n       k16 volume put-boot <volume.kv> <boot.kb>\n       k16 volume put-kernel <volume.kv> <kernel.kx>\n       k16 volume extract-partition <volume.kv> <partition> <output>\n       k16 volume replace-partition <volume.kv> <partition> <input>\n       k16 volume inspect <volume.kv>\n       k16 volume inspect-boot <volume.kv>".to_string())
}

fn fs_usage_error() -> Result<(), String> {
    Err("usage: k16 fs kfs format <image.kfs> --blocks <blocks>\n       k16 fs kfs mkdir <image.kfs> <path>\n       k16 fs kfs put <image.kfs> <path> <host-input>\n       k16 fs kfs get <image.kfs> <path> <host-output>\n       k16 fs kfs rm <image.kfs> <path>\n       k16 fs kfs ls <image.kfs> <path>".to_string())
}
