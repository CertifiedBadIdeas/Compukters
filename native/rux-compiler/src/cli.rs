use crate::artifact::Rux16ArtifactTarget;
use crate::{
    advice, compile_rux16_artifact, inspect, object_link, rux16_disasm, rux16_runtime, ruxfs,
    volume,
};
use k16_vm::k16_computer::K16ComputerHandle;
use k16_vm::rux16::Rux16Signal;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CliSurface {
    Rux,
    K16,
}

impl CliSurface {
    fn command_name(self) -> &'static str {
        match self {
            CliSurface::Rux => "rux",
            CliSurface::K16 => "k16",
        }
    }
}

pub fn run_rux_cli(args: Vec<String>) -> Result<(), String> {
    run_cli(CliSurface::Rux, args)
}

pub fn run_k16_cli(args: Vec<String>) -> Result<(), String> {
    run_cli(CliSurface::K16, args)
}

fn run_cli(surface: CliSurface, args: Vec<String>) -> Result<(), String> {
    let Some(command) = args.first() else {
        return usage_error(surface);
    };
    match (surface, command.as_str()) {
        (CliSurface::Rux, "compile") => run_compile(&args[1..]),
        (CliSurface::Rux, "check") => run_check(&args[1..]),
        (CliSurface::K16, "link") => run_link(surface, &args[1..]),
        (CliSurface::K16, "runtime") => run_runtime(surface, &args[1..]),
        (CliSurface::K16, "run") => run_program(surface, &args[1..]),
        (CliSurface::K16, "disasm" | "disassemble") => run_disasm(surface, &args[1..]),
        (CliSurface::K16, "inspect") => run_inspect(surface, &args[1..]),
        (CliSurface::K16, "fs") => run_fs(surface, &args[1..]),
        (CliSurface::K16, "volume") => run_volume(surface, &args[1..]),
        _ => usage_error(surface),
    }
}

fn usage_error(surface: CliSurface) -> Result<(), String> {
    match surface {
        CliSurface::Rux => Err("usage: rux check <input.rx>\n       rux compile --target bios <input.rx> -o <bios.kflash>\n       rux compile --target boot <input.rx> -o <boot.kb>\n       rux compile [--target <kernel|program>] <input.rx> -o <program.kx>".to_string()),
        CliSurface::K16 => Err("usage: k16 link [--target <boot|kernel|program>] <input.ko>... -o <output.kx>\n       k16 runtime <rux16-startup|rux16-memory-helpers> -o <output.ko>\n       k16 run <program.kx>\n       k16 disasm --target <bios|boot|kernel|program> <input>\n       k16 inspect <blob>\n       k16 volume <create|init|put-boot|put-kernel> ...\n       k16 fs <filesystem> ...".to_string()),
    }
}

fn run_program(surface: CliSurface, args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return run_usage_error(surface);
    }
    let program =
        fs::read(&args[0]).map_err(|error| format!("failed to read {}: {error}", args[0]))?;
    let mut handle =
        K16ComputerHandle::create_rux16_bios_flash(&[0x01, 0x00], 64 * 1024, 1_000_000)
            .map_err(|error| format!("failed to create Rux16 computer: {error}"))?;
    handle
        .exec_ruxe_program_from_bytes(&program, 1_000_000)
        .map_err(|error| format!("failed to install RUXE program: {error}"))?;
    let signal = handle
        .run_rux16_until_signal()
        .map_err(|error| format!("failed to run Rux16 program: {error}"))?;
    println!(
        "signal={} debug_bytes={}",
        signal_name(signal),
        hex_bytes(handle.debug_output_bytes())
    );
    Ok(())
}

fn run_inspect(surface: CliSurface, args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return inspect_usage_error(surface);
    }
    let bytes =
        fs::read(&args[0]).map_err(|error| format!("failed to read {}: {error}", args[0]))?;
    print!("{}", inspect::inspect_blob(&bytes)?);
    Ok(())
}

fn run_check(args: &[String]) -> Result<(), String> {
    if args.len() != 1 {
        return check_usage_error();
    }
    let source_path = &args[0];
    let source = fs::read_to_string(source_path)
        .map_err(|error| format!("failed to read {source_path}: {error}"))?;
    let diagnostics =
        advice::check_source(&source).map_err(|error| format!("check error: {}", error.message))?;
    for diagnostic in diagnostics {
        println!(
            "{source_path}:{}:{}: suggestion: {}",
            diagnostic.line, diagnostic.column, diagnostic.message
        );
        println!("  help: {}", diagnostic.help);
    }
    Ok(())
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

fn run_link(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let config = parse_link_args(surface, args)?;
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
        .map(|(path, bytes)| object_link::Rux16LinkInput { name: path, bytes })
        .collect::<Vec<_>>();
    let bytes = object_link::link_rux16_objects_to_ruxe(&inputs, config.target)?;
    fs::write(&config.output_path, bytes)
        .map_err(|error| format!("failed to write {}: {error}", config.output_path))
}

fn run_runtime(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return runtime_usage_error(surface);
    };
    match command.as_str() {
        "rux16-startup" => {
            if args.len() != 3 || args[1] != "-o" {
                return runtime_usage_error(surface);
            }
            let bytes = rux16_runtime::rux16_startup_object();
            fs::write(&args[2], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[2]))
        }
        "rux16-memory-helpers" => {
            if args.len() != 3 || args[1] != "-o" {
                return runtime_usage_error(surface);
            }
            build_rux16_memory_helpers(Path::new(&args[2]))
        }
        _ => runtime_usage_error(surface),
    }
}

fn build_rux16_memory_helpers(output_path: &Path) -> Result<(), String> {
    let rustc = env::var("RUX16_RUSTC")
        .map_err(|_| "RUX16_RUSTC must point to a custom Rux16 rustc".to_string())?;
    let rustc_path = PathBuf::from(&rustc);
    if !rustc_path.is_file() {
        return Err(format!(
            "RUX16_RUSTC must point to a custom Rux16 rustc: {}",
            rustc_path.display()
        ));
    }

    let llvm_bin_dir = env::var("RUX16_LLVM_BIN_DIR")
        .map_err(|_| "RUX16_LLVM_BIN_DIR must point to Rux16 LLVM tools".to_string())?;
    let llc_path = PathBuf::from(llvm_bin_dir).join("llc");
    if !llc_path.is_file() {
        return Err(format!(
            "RUX16_LLVM_BIN_DIR must contain Rux16 llc: {}",
            llc_path.display()
        ));
    }

    let target_spec = env::var("RUX16_RUST_TARGET_JSON")
        .map(PathBuf::from)
        .unwrap_or_else(|_| repo_root().join("tools/rux16-unknown-ruxos.json"));
    if !target_spec.is_file() {
        return Err(format!(
            "Rux16 Rust target spec is missing: {}",
            target_spec.display()
        ));
    }

    let source = runtime_source_path("rux16_memory_helpers.rs");
    if !source.is_file() {
        return Err(format!(
            "Rux16 runtime helper source is missing: {}",
            source.display()
        ));
    }

    let _ = fs::remove_file(output_path);
    let ir_path = temp_runtime_path("rux16-memory-helpers", "ll");
    let rustc_output = Command::new(&rustc_path)
        .args([
            "-Z",
            "unstable-options",
            "--edition=2021",
            "--target",
            target_spec.to_str().ok_or_else(|| {
                format!(
                    "Rux16 Rust target spec path is not UTF-8: {}",
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
                    "Rux16 runtime helper source path is not UTF-8: {}",
                    source.display()
                )
            })?,
            "-o",
            ir_path.to_str().ok_or_else(|| {
                format!(
                    "Rux16 runtime helper IR path is not UTF-8: {}",
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
            "failed to compile Rux16 memory helpers to LLVM IR with {}:\n{}{}",
            rustc_path.display(),
            String::from_utf8_lossy(&rustc_output.stdout),
            String::from_utf8_lossy(&rustc_output.stderr)
        ));
    }

    let llc_output = Command::new(&llc_path)
        .args([
            "-mtriple=rux16",
            "-filetype=obj",
            ir_path.to_str().ok_or_else(|| {
                format!(
                    "Rux16 runtime helper IR path is not UTF-8: {}",
                    ir_path.display()
                )
            })?,
            "-o",
            output_path.to_str().ok_or_else(|| {
                format!(
                    "Rux16 runtime helper output path is not UTF-8: {}",
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
            "failed to lower Rux16 memory helpers with {}:\n{}{}",
            llc_path.display(),
            String::from_utf8_lossy(&llc_output.stdout),
            String::from_utf8_lossy(&llc_output.stderr)
        ));
    }
    Ok(())
}

fn runtime_source_path(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("runtime")
        .join(name)
}

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("native/rux-compiler has repo root grandparent")
        .to_path_buf()
}

fn temp_runtime_path(stem: &str, extension: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time is after Unix epoch")
        .as_nanos();
    env::temp_dir().join(format!("{stem}-{}-{nanos}.{extension}", std::process::id()))
}

fn run_disasm(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let config = parse_disasm_args(surface, args)?;
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

fn parse_disasm_args(surface: CliSurface, args: &[String]) -> Result<DisasmConfig, String> {
    let mut target = None;
    let mut input_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return disasm_usage_error(surface);
                };
                target = Some(Rux16ArtifactTarget::parse(value)?);
                index += 2;
            }
            value if value.starts_with('-') => return disasm_usage_error(surface),
            value => {
                if input_path.is_some() {
                    return disasm_usage_error(surface);
                }
                input_path = Some(value.to_string());
                index += 1;
            }
        }
    }
    Ok(DisasmConfig {
        target: target.ok_or_else(|| disasm_usage_message(surface))?,
        input_path: input_path.ok_or_else(|| disasm_usage_message(surface))?,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CompileConfig {
    target: Rux16ArtifactTarget,
    source_path: String,
    output_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LinkConfig {
    target: Rux16ArtifactTarget,
    input_paths: Vec<String>,
    output_path: String,
}

fn parse_link_args(surface: CliSurface, args: &[String]) -> Result<LinkConfig, String> {
    let mut target = Rux16ArtifactTarget::Program;
    let mut input_paths = Vec::new();
    let mut output_path = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--target" => {
                let Some(value) = args.get(index + 1) else {
                    return link_usage_error(surface);
                };
                target = Rux16ArtifactTarget::parse(value)?;
                index += 2;
            }
            "-o" => {
                let Some(value) = args.get(index + 1) else {
                    return link_usage_error(surface);
                };
                output_path = Some(value.clone());
                index += 2;
            }
            value if value.starts_with('-') => return link_usage_error(surface),
            value => {
                input_paths.push(value.to_string());
                index += 1;
            }
        }
    }
    if input_paths.is_empty() {
        return link_usage_error(surface);
    }
    Ok(LinkConfig {
        target,
        input_paths,
        output_path: output_path.ok_or_else(|| link_usage_message(surface))?,
    })
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

fn run_volume(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return volume_usage_error(surface);
    };
    match command.as_str() {
        "create" => {
            if args.len() != 4 || args[2] != "--size" {
                return volume_usage_error(surface);
            }
            let size = parse_size(&args[3])?;
            let bytes = volume::create_empty_volume(size)?;
            fs::write(&args[1], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "init" => {
            if args.len() != 4 || args[2] != "--size" {
                return volume_usage_error(surface);
            }
            let size = parse_size(&args[3])?;
            let bytes = volume::create_initialized_volume(size)?;
            fs::write(&args[1], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "put-boot" => {
            if args.len() != 3 {
                return volume_usage_error(surface);
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
                return volume_usage_error(surface);
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
                return volume_usage_error(surface);
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let partition_bytes = volume::extract_partition(&volume_bytes, &args[2])?;
            fs::write(&args[3], partition_bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[3]))
        }
        "replace-partition" => {
            if args.len() != 4 {
                return volume_usage_error(surface);
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
                return volume_usage_error(surface);
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            print!("{}", volume::inspect(&volume_bytes)?);
            Ok(())
        }
        "inspect-boot" => {
            if args.len() != 2 {
                return volume_usage_error(surface);
            }
            let volume_bytes = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            print!("{}", volume::inspect_boot_chain(&volume_bytes)?);
            Ok(())
        }
        _ => volume_usage_error(surface),
    }
}

fn run_fs(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let Some(filesystem) = args.first() else {
        return fs_usage_error(surface);
    };
    match filesystem.as_str() {
        "kfs" => run_kfs(surface, &args[1..]),
        other => Err(format!("unsupported filesystem `{other}`")),
    }
}

fn run_kfs(surface: CliSurface, args: &[String]) -> Result<(), String> {
    let Some(command) = args.first() else {
        return fs_usage_error(surface);
    };
    match command.as_str() {
        "format" => {
            if args.len() != 4 || args[2] != "--blocks" {
                return fs_usage_error(surface);
            }
            let total_blocks = parse_blocks(&args[3])?;
            let bytes = ruxfs::format_empty_filesystem(total_blocks)?;
            fs::write(&args[1], bytes)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "mkdir" => {
            if args.len() != 3 {
                return fs_usage_error(surface);
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            ruxfs::create_directory(&mut image, &args[2])?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "put" => {
            if args.len() != 4 {
                return fs_usage_error(surface);
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let contents = fs::read(&args[3])
                .map_err(|error| format!("failed to read {}: {error}", args[3]))?;
            ruxfs::write_file(&mut image, &args[2], &contents)?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "get" => {
            if args.len() != 4 {
                return fs_usage_error(surface);
            }
            let image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            let contents = ruxfs::read_file(&image, &args[2])?;
            fs::write(&args[3], contents)
                .map_err(|error| format!("failed to write {}: {error}", args[3]))
        }
        "rm" => {
            if args.len() != 3 {
                return fs_usage_error(surface);
            }
            let mut image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            ruxfs::delete_file(&mut image, &args[2])?;
            fs::write(&args[1], image)
                .map_err(|error| format!("failed to write {}: {error}", args[1]))
        }
        "ls" => {
            if args.len() != 3 {
                return fs_usage_error(surface);
            }
            let image = fs::read(&args[1])
                .map_err(|error| format!("failed to read {}: {error}", args[1]))?;
            for name in ruxfs::list_directory(&image, &args[2])? {
                println!("{name}");
            }
            Ok(())
        }
        _ => fs_usage_error(surface),
    }
}

fn signal_name(signal: Rux16Signal) -> &'static str {
    match signal {
        Rux16Signal::Halt => "halt",
        Rux16Signal::StepLimitExceeded => "step-limit-exceeded",
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

fn check_usage_error() -> Result<(), String> {
    Err("usage: rux check <input.rx>".to_string())
}

fn compile_usage_error() -> Result<CompileConfig, String> {
    Err(compile_usage_message())
}

fn compile_usage_message() -> String {
    "usage: rux compile --target bios <input.rx> -o <bios.kflash>\n       rux compile --target boot <input.rx> -o <boot.kb>\n       rux compile [--target <kernel|program>] <input.rx> -o <program.kx>".to_string()
}

fn link_usage_error(surface: CliSurface) -> Result<LinkConfig, String> {
    Err(link_usage_message(surface))
}

fn link_usage_message(surface: CliSurface) -> String {
    debug_assert_eq!(surface, CliSurface::K16);
    "usage: k16 link [--target <boot|kernel|program>] <input.ko>... -o <output.kx>".to_string()
}

fn runtime_usage_error(surface: CliSurface) -> Result<(), String> {
    debug_assert_eq!(surface, CliSurface::K16);
    Err(format!(
        "usage: {} runtime <rux16-startup|rux16-memory-helpers> -o <output.ko>",
        surface.command_name()
    ))
}

fn run_usage_error(surface: CliSurface) -> Result<(), String> {
    debug_assert_eq!(surface, CliSurface::K16);
    Err("usage: k16 run <program.kx>".to_string())
}

fn disasm_usage_error(surface: CliSurface) -> Result<DisasmConfig, String> {
    Err(disasm_usage_message(surface))
}

fn disasm_usage_message(surface: CliSurface) -> String {
    format!(
        "usage: {} disasm --target <bios|boot|kernel|program> <input>",
        surface.command_name()
    )
}

fn inspect_usage_error(surface: CliSurface) -> Result<(), String> {
    Err(format!("usage: {} inspect <blob>", surface.command_name()))
}

fn volume_usage_error(surface: CliSurface) -> Result<(), String> {
    debug_assert_eq!(surface, CliSurface::K16);
    let command = surface.command_name();
    Err(format!(
        "usage: {command} volume create <volume.kv> --size <bytes>\n       {command} volume init <volume.kv> --size <bytes>\n       {command} volume put-boot <volume.kv> <boot.kb>\n       {command} volume put-kernel <volume.kv> <kernel.kx>\n       {command} volume extract-partition <volume.kv> <partition> <output>\n       {command} volume replace-partition <volume.kv> <partition> <input>\n       {command} volume inspect <volume.kv>\n       {command} volume inspect-boot <volume.kv>"
    ))
}

fn fs_usage_error(surface: CliSurface) -> Result<(), String> {
    debug_assert_eq!(surface, CliSurface::K16);
    let command = surface.command_name();
    Err(format!(
        "usage: {command} fs kfs format <image.kfs> --blocks <blocks>\n       {command} fs kfs mkdir <image.kfs> <path>\n       {command} fs kfs put <image.kfs> <path> <host-input>\n       {command} fs kfs get <image.kfs> <path> <host-output>\n       {command} fs kfs rm <image.kfs> <path>\n       {command} fs kfs ls <image.kfs> <path>"
    ))
}
