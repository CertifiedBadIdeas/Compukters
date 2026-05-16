use rux_compiler::{compile, render_terminal_ui, RuxRunReport};
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::low_image::{decode_image, Image};
use rux_vm::low_image_runner::LowImageSignal;
use std::env;
use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;
use std::process::ExitCode;
use std::sync::mpsc::{self, TryRecvError};
use std::thread;
use std::time::Duration;

const DEFAULT_MEMORY_SIZE: usize = 64 * 1024;
const DEFAULT_SLICE_BUDGET_NANOS: u64 = 1_000_000;

fn main() -> ExitCode {
    let config = match parse_args(env::args()) {
        Ok(config) => config,
        Err(error) => {
            eprintln!("{error}");
            eprintln!(
                "usage: rux-run <path.rx|path.ruxi> [--memory <bytes>] [--serial <text>|--serial-live]"
            );
            return ExitCode::from(2);
        }
    };

    let image = match load_image(&config.path) {
        Ok(image) => image,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::from(1);
        }
    };

    match config.mode {
        RunMode::Default => run_framed(image, config.memory_size),
        RunMode::ScriptedSerial(input) => run_scripted_serial(image, &input, config.memory_size),
        RunMode::LiveSerial => run_live_serial(image, config.memory_size),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct RunConfig {
    path: String,
    mode: RunMode,
    memory_size: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum RunMode {
    Default,
    ScriptedSerial(Vec<u8>),
    LiveSerial,
}

fn parse_args<I, S>(args: I) -> Result<RunConfig, String>
where
    I: IntoIterator<Item = S>,
    S: Into<String>,
{
    let mut args = args.into_iter().map(Into::into);
    let _program = args.next();
    let Some(path) = args.next() else {
        return Err("missing input file".to_string());
    };
    let mut mode = RunMode::Default;
    let mut memory_size = DEFAULT_MEMORY_SIZE;

    while let Some(flag) = args.next() {
        match flag.as_str() {
            "--serial" => {
                let Some(input) = args.next() else {
                    return Err("--serial expects input text".to_string());
                };
                if !matches!(mode, RunMode::Default) {
                    return Err("serial mode is already set".to_string());
                }
                mode = RunMode::ScriptedSerial(input.into_bytes());
            }
            "--serial-live" => {
                if !matches!(mode, RunMode::Default) {
                    return Err("serial mode is already set".to_string());
                }
                mode = RunMode::LiveSerial;
            }
            "--memory" => {
                let Some(value) = args.next() else {
                    return Err("--memory expects byte count".to_string());
                };
                memory_size = parse_memory_size(&value)?;
            }
            _ => return Err(format!("unknown argument `{flag}`")),
        }
    }

    Ok(RunConfig {
        path,
        mode,
        memory_size,
    })
}

fn parse_memory_size(value: &str) -> Result<usize, String> {
    let memory_size = value
        .parse::<usize>()
        .map_err(|_| format!("invalid memory size `{value}`"))?;
    if memory_size == 0 {
        return Err("memory size must be positive".to_string());
    }
    Ok(memory_size)
}

fn load_image(path: &str) -> Result<Image, String> {
    match Path::new(path)
        .extension()
        .and_then(|extension| extension.to_str())
    {
        Some("rx") => {
            let source = fs::read_to_string(path)
                .map_err(|error| format!("failed to read {path}: {error}"))?;
            compile(&source).map_err(|error| format!("compile error: {}", error.message))
        }
        Some("ruxi") => {
            let bytes =
                fs::read(path).map_err(|error| format!("failed to read {path}: {error}"))?;
            decode_image(&bytes).map_err(|error| format!("decode error: {error}"))
        }
        Some(extension) => Err(format!(
            "unsupported input extension `.{extension}`; expected .rx or .ruxi"
        )),
        None => Err("unsupported input without extension; expected .rx or .ruxi".to_string()),
    }
}

fn run_framed(image: Image, memory_size: usize) -> ExitCode {
    match run_image_with_serial_input(image, &[], memory_size) {
        Ok(report) => {
            print!("{}", render_terminal_ui(&report));
            if report.panic_code == 0 {
                ExitCode::SUCCESS
            } else {
                ExitCode::from(1)
            }
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::from(1)
        }
    }
}

fn run_scripted_serial(image: Image, input: &[u8], memory_size: usize) -> ExitCode {
    match run_image_with_serial_input(image, input, memory_size) {
        Ok(report) => {
            print!("{}", render_terminal_ui(&report));
            if report.panic_code == 0 {
                ExitCode::SUCCESS
            } else {
                ExitCode::from(1)
            }
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::from(1)
        }
    }
}

fn run_image_with_serial_input(
    image: Image,
    input: &[u8],
    memory_size: usize,
) -> Result<RuxRunReport, String> {
    let mut machine = ComputerMachine::new(memory_size).map_err(|error| error.to_string())?;
    machine.push_serial_input(input);
    let cpu_id = machine.spawn_boot_cpu(image, DEFAULT_SLICE_BUDGET_NANOS)?;
    let signal = machine.run_boot_cpu_until_signal(cpu_id)?;

    Ok(RuxRunReport {
        signal,
        debug_output: machine.debug_output_string(),
        control_status: machine.control_status(),
        exit_code: machine.exit_code(),
        panic_code: machine.panic_code(),
    })
}

fn run_live_serial(image: Image, memory_size: usize) -> ExitCode {
    let mut machine = match ComputerMachine::new(memory_size) {
        Ok(machine) => machine,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::from(1);
        }
    };
    let cpu_id = match machine.spawn_boot_cpu(image, DEFAULT_SLICE_BUDGET_NANOS) {
        Ok(cpu_id) => cpu_id,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::from(1);
        }
    };

    let (tx, rx) = mpsc::channel::<Vec<u8>>();
    thread::spawn(move || {
        let mut stdin = io::stdin().lock();
        let mut buffer = [0_u8; 64];
        loop {
            match stdin.read(&mut buffer) {
                Ok(0) => break,
                Ok(count) => {
                    if tx.send(buffer[..count].to_vec()).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    let mut input_open = true;
    loop {
        let mut received_input = false;
        loop {
            match rx.try_recv() {
                Ok(bytes) => {
                    received_input = true;
                    machine.push_serial_input(&bytes);
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    input_open = false;
                    break;
                }
            }
        }

        let signal = match machine.run_boot_cpu_until_signal(cpu_id) {
            Ok(signal) => signal,
            Err(error) => {
                eprintln!("{error}");
                return ExitCode::from(1);
            }
        };
        let output = machine.drain_debug_output_bytes();
        if !output.is_empty() {
            if io::stdout().write_all(&output).is_err() {
                return ExitCode::from(1);
            }
            if io::stdout().flush().is_err() {
                return ExitCode::from(1);
            }
        }

        match signal {
            LowImageSignal::Pause => {
                if !input_open && machine.serial_input_len() == 0 {
                    return ExitCode::SUCCESS;
                }
                if !received_input {
                    thread::sleep(Duration::from_millis(1));
                }
            }
            LowImageSignal::HaltUnit => return ExitCode::SUCCESS,
            LowImageSignal::HaltI32(code) => return exit_code_from_i32(code),
            LowImageSignal::HaltI64(code) => return exit_code_from_i32(code as i32),
            LowImageSignal::HaltAddr(code) => {
                return exit_code_from_i32(i32::from_ne_bytes(code.to_ne_bytes()))
            }
            LowImageSignal::HaltBool(success) => {
                return if success {
                    ExitCode::SUCCESS
                } else {
                    ExitCode::from(1)
                }
            }
        }
    }
}

fn exit_code_from_i32(code: i32) -> ExitCode {
    if code == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_args, RunConfig, RunMode, DEFAULT_MEMORY_SIZE};

    #[test]
    fn parse_args_accepts_default_run_mode() {
        let args = ["rux-run", "firmware.rx"];

        assert_eq!(
            parse_args(args).unwrap(),
            RunConfig {
                path: "firmware.rx".to_string(),
                mode: RunMode::Default,
                memory_size: DEFAULT_MEMORY_SIZE,
            }
        );
    }

    #[test]
    fn parse_args_accepts_scripted_serial_input() {
        let args = ["rux-run", "firmware.rx", "--serial", "Rux!"];

        assert_eq!(
            parse_args(args).unwrap(),
            RunConfig {
                path: "firmware.rx".to_string(),
                mode: RunMode::ScriptedSerial("Rux!".as_bytes().to_vec()),
                memory_size: DEFAULT_MEMORY_SIZE,
            }
        );
    }

    #[test]
    fn parse_args_accepts_live_serial_mode() {
        let args = ["rux-run", "firmware.rx", "--serial-live"];

        assert_eq!(
            parse_args(args).unwrap(),
            RunConfig {
                path: "firmware.rx".to_string(),
                mode: RunMode::LiveSerial,
                memory_size: DEFAULT_MEMORY_SIZE,
            }
        );
    }

    #[test]
    fn parse_args_accepts_memory_size() {
        let args = ["rux-run", "firmware.ruxi", "--memory", "1024"];

        assert_eq!(
            parse_args(args).unwrap(),
            RunConfig {
                path: "firmware.ruxi".to_string(),
                mode: RunMode::Default,
                memory_size: 1024,
            }
        );
    }

    #[test]
    fn parse_args_accepts_memory_size_with_serial_input() {
        let args = [
            "rux-run",
            "firmware.ruxi",
            "--memory",
            "1024",
            "--serial",
            "Rux!",
        ];

        assert_eq!(
            parse_args(args).unwrap(),
            RunConfig {
                path: "firmware.ruxi".to_string(),
                mode: RunMode::ScriptedSerial("Rux!".as_bytes().to_vec()),
                memory_size: 1024,
            }
        );
    }
}
