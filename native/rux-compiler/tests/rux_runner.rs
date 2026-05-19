use rux_compiler::{
    compile, render_terminal_ui, run_source, run_source_until_serial_output,
    run_source_with_serial_input,
};
use rux_vm::computer_machine::ComputerMachine;
use rux_vm::computer_machine::ComputerTextDisplaySnapshot;
use rux_vm::low_image_runner::LowImageSignal;

#[test]
fn run_source_reports_debug_output_and_machine_state() {
    let report = run_source(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(79);
                mmio<i32>(DEBUG_WRITE).store(75);
            }
            return 7;
        }",
    )
    .unwrap();

    assert_eq!(report.signal, LowImageSignal::HaltI32(7));
    assert_eq!(report.debug_output, "OK");
    assert_eq!(report.control_status, ComputerMachine::STATUS_HALTED);
    assert_eq!(report.exit_code, 7);
    assert_eq!(report.panic_code, 0);
}

#[test]
fn terminal_ui_renders_debug_output_inside_machine_frame() {
    let report = run_source(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(82);
                mmio<i32>(DEBUG_WRITE).store(117);
                mmio<i32>(DEBUG_WRITE).store(120);
            }
            return 0;
        }",
    )
    .unwrap();

    let ui = render_terminal_ui(&report);

    assert!(ui.contains("+--------------------------------------------------+"));
    assert!(ui.contains("| Rux Computer Terminal"));
    assert!(ui.contains("| Rux"));
    assert!(ui.contains("signal: HaltI32(0)"));
    assert!(ui.contains("status: 3"));
    assert!(ui.contains("exit: 0"));
    assert!(ui.contains("panic: 0"));
}

#[test]
fn example_terminal_firmware_runs() {
    let source = include_str!("../examples/firmware/terminal.rx");
    let report = run_source(source).unwrap();

    assert_eq!(report.exit_code, 0);
    assert_eq!(report.panic_code, 0);
    assert_eq!(report.debug_output, "RUX READY");
}

#[test]
fn example_terminal_firmware_uses_computer_std_for_status() {
    let source = include_str!("../examples/firmware/terminal.rx");

    assert!(!source.contains("CONTROL_STATUS"));
    assert!(source.contains("std::computer"));
}

#[test]
fn example_copy_firmware_runs() {
    let source = include_str!("../examples/firmware/copy.rx");
    let report = run_source(source).unwrap();

    assert_eq!(report.exit_code, 0x5855_52);
    assert_eq!(report.panic_code, 0);
    assert_eq!(report.debug_output, "RUX");
}

#[test]
fn example_hardware_discovery_firmware_runs() {
    let source = include_str!("../examples/firmware/hardware_debug.rx");
    let report = run_source(source).unwrap();

    assert_eq!(report.exit_code, 0);
    assert_eq!(report.panic_code, 0);
    assert_eq!(report.debug_output, "HW");
}

#[test]
fn example_hardware_discovery_uses_computer_std() {
    let source = include_str!("../examples/firmware/hardware_debug.rx");

    assert!(source.contains("std::computer::debug_base"));
    assert!(!source.contains("2u32"));
}

#[test]
fn example_display_hello_firmware_runs() {
    let source = include_str!("../examples/firmware/display_hello.rx");
    let report = run_source(source).unwrap();

    assert_eq!(report.exit_code, 0);
    assert_eq!(report.panic_code, 0);
}

#[test]
fn run_source_with_serial_input_echoes_bytes_through_firmware() {
    let source = include_str!("../examples/firmware/echo.rx");
    let report = run_source_with_serial_input(source, b"Rux!").unwrap();

    assert_eq!(report.exit_code, 0);
    assert_eq!(report.panic_code, 0);
    assert_eq!(report.debug_output, "Rux!");
}

#[test]
fn run_source_until_serial_output_supports_live_polling_firmware() {
    let source = include_str!("../examples/firmware/echo_live.rx");
    let report = run_source_until_serial_output(source, b"Rux!", 4, 32).unwrap();

    assert_eq!(report.panic_code, 0);
    assert_eq!(report.debug_output, "RUX READY\nRux!");
}

#[test]
fn example_laptop_firmware_draws_prompt_on_display() {
    let snapshot = run_laptop_firmware_display(b"", 32);

    assert_eq!(display_row(&snapshot, 0), "RUX LAPTOP READY");
    assert_eq!(display_row(&snapshot, 1), "> ");
}

#[test]
fn example_laptop_firmware_supports_enter_and_backspace_on_display() {
    let snapshot = run_laptop_firmware_display(b"AB\x08C\n", 64);

    assert_eq!(display_row(&snapshot, 1), "> AC");
    assert_eq!(display_row(&snapshot, 2), "> ");
}

fn run_laptop_firmware_display(input: &[u8], max_turns: usize) -> ComputerTextDisplaySnapshot {
    let source = include_str!("../examples/firmware/laptop.rx");
    let image = compile(source).unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    machine.push_serial_input(input);
    let cpu_id = machine.spawn_boot_cpu(image, 1_000).unwrap();

    for _ in 0..max_turns {
        let signal = machine.run_boot_cpu_until_signal(cpu_id).unwrap();
        if !matches!(signal, LowImageSignal::Pause) {
            break;
        }
    }

    machine.display0_snapshot().unwrap()
}

fn display_row(snapshot: &ComputerTextDisplaySnapshot, row: u32) -> String {
    let columns = snapshot.columns as usize;
    let start = row as usize * columns;
    let end = start + columns;
    let row = &snapshot.cells[start..end];
    let visible_end = row
        .iter()
        .rposition(|byte| *byte != 0)
        .map(|index| index + 1)
        .unwrap_or(0);

    row[..visible_end]
        .iter()
        .map(|byte| char::from(*byte))
        .collect()
}
