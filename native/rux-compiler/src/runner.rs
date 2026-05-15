use crate::{compile, CompileError};
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::low_image_runner::LowImageSignal;
use std::fmt::Write;

const DEFAULT_MEMORY_SIZE: usize = 64 * 1024;
const DEFAULT_SLICE_BUDGET_NANOS: u64 = 1_000_000;
const TERMINAL_TEXT_WIDTH: usize = 48;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuxRunReport {
    pub signal: LowImageSignal,
    pub debug_output: String,
    pub control_status: i32,
    pub exit_code: i32,
    pub panic_code: i32,
}

pub fn run_source(source: &str) -> Result<RuxRunReport, String> {
    run_source_with_limits(source, DEFAULT_MEMORY_SIZE, DEFAULT_SLICE_BUDGET_NANOS)
}

pub fn run_source_with_serial_input(source: &str, input: &[u8]) -> Result<RuxRunReport, String> {
    run_source_with_serial_input_and_limits(
        source,
        input,
        DEFAULT_MEMORY_SIZE,
        DEFAULT_SLICE_BUDGET_NANOS,
    )
}

pub fn run_source_until_serial_output(
    source: &str,
    input: &[u8],
    output_bytes: usize,
    max_turns: usize,
) -> Result<RuxRunReport, String> {
    let image = compile(source).map_err(format_compile_error)?;
    let mut machine =
        ComputerMachine::new(DEFAULT_MEMORY_SIZE).map_err(|error| error.to_string())?;
    machine.push_serial_input(input);
    let cpu_id = machine.spawn_boot_cpu(image, DEFAULT_SLICE_BUDGET_NANOS)?;
    let mut output = Vec::new();

    for _ in 0..max_turns {
        let signal = machine.run_boot_cpu_until_signal(cpu_id)?;
        output.extend(machine.drain_debug_output_bytes());
        if output.len() >= output_bytes || !matches!(signal, LowImageSignal::Pause) {
            return Ok(RuxRunReport {
                signal,
                debug_output: String::from_utf8_lossy(&output).into_owned(),
                control_status: machine.control_status(),
                exit_code: machine.exit_code(),
                panic_code: machine.panic_code(),
            });
        }
    }

    Err(format!(
        "serial output did not reach {output_bytes} bytes after {max_turns} turns",
    ))
}

pub fn run_source_with_limits(
    source: &str,
    memory_size: usize,
    slice_budget_nanos: u64,
) -> Result<RuxRunReport, String> {
    run_source_with_serial_input_and_limits(source, &[], memory_size, slice_budget_nanos)
}

pub fn run_source_with_serial_input_and_limits(
    source: &str,
    input: &[u8],
    memory_size: usize,
    slice_budget_nanos: u64,
) -> Result<RuxRunReport, String> {
    let image = compile(source).map_err(format_compile_error)?;
    let mut machine = ComputerMachine::new(memory_size).map_err(|error| error.to_string())?;
    machine.push_serial_input(input);
    let cpu_id = machine.spawn_boot_cpu(image, slice_budget_nanos)?;
    let signal = machine.run_boot_cpu_until_signal(cpu_id)?;

    Ok(RuxRunReport {
        signal,
        debug_output: machine.debug_output_string(),
        control_status: machine.control_status(),
        exit_code: machine.exit_code(),
        panic_code: machine.panic_code(),
    })
}

pub fn render_terminal_ui(report: &RuxRunReport) -> String {
    let mut out = String::new();
    let border = format!("+{}+", "-".repeat(TERMINAL_TEXT_WIDTH + 2));
    let _ = writeln!(out, "{border}");
    push_frame_line(&mut out, "Rux Computer Terminal");
    let _ = writeln!(out, "{border}");

    if report.debug_output.is_empty() {
        push_frame_line(&mut out, "");
    } else {
        for line in report.debug_output.lines() {
            push_frame_wrapped_line(&mut out, line);
        }
        if report.debug_output.ends_with('\n') {
            push_frame_line(&mut out, "");
        }
    }

    let _ = writeln!(out, "{border}");
    let _ = writeln!(out, "signal: {:?}", report.signal);
    let _ = writeln!(out, "status: {}", report.control_status);
    let _ = writeln!(out, "exit: {}", report.exit_code);
    let _ = writeln!(out, "panic: {}", report.panic_code);
    out
}

fn format_compile_error(error: CompileError) -> String {
    format!("compile error: {}", error.message)
}

fn push_frame_wrapped_line(out: &mut String, line: &str) {
    if line.is_empty() {
        push_frame_line(out, "");
        return;
    }

    let mut start = 0;
    while start < line.len() {
        let end = next_boundary(line, start, TERMINAL_TEXT_WIDTH);
        push_frame_line(out, &line[start..end]);
        start = end;
    }
}

fn next_boundary(line: &str, start: usize, max_width: usize) -> usize {
    let mut end = start;
    for (count, (index, ch)) in line[start..].char_indices().enumerate() {
        if count == max_width {
            break;
        }
        end = start + index + ch.len_utf8();
    }
    end
}

fn push_frame_line(out: &mut String, text: &str) {
    let visible_width = text.chars().count();
    let padding = TERMINAL_TEXT_WIDTH.saturating_sub(visible_width);
    let _ = writeln!(out, "| {text}{} |", " ".repeat(padding));
}
