use std::env;
use std::process::ExitCode;

fn main() -> ExitCode {
    match k16_tools::k16_linker_driver::run_k16_linker_driver(env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::from(1)
        }
    }
}
