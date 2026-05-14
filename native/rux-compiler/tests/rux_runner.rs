use rux_compiler::{render_terminal_ui, run_source};
use rux_vm::computer_machine::ComputerMachine;
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
