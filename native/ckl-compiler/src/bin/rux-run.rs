use ckl_compiler::{render_terminal_ui, run_source};
use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let Some(path) = env::args().nth(1) else {
        eprintln!("usage: rux-run <path.rux>");
        return ExitCode::from(2);
    };

    let source = match fs::read_to_string(&path) {
        Ok(source) => source,
        Err(error) => {
            eprintln!("failed to read {path}: {error}");
            return ExitCode::from(1);
        }
    };

    match run_source(&source) {
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
