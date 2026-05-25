use rux_compiler::volume;
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
        "volume" => run_volume(&args[1..]),
        _ => usage_error(),
    }
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
    Err("usage: rux volume <create|put-boot> ...".to_string())
}

fn volume_usage_error() -> Result<(), String> {
    Err(
        "usage: rux volume create <volume.ruxvol> --size <bytes>\n       rux volume put-boot <volume.ruxvol> <boot.bin>"
            .to_string(),
    )
}
