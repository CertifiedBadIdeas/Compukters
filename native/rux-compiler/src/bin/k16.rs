use std::env;
use std::process::ExitCode;

fn main() -> ExitCode {
    match rux_compiler::cli::run_k16_cli(env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::from(1)
        }
    }
}
