use rux_vm::low_disasm::disassemble_bytes;
use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(input_path) = args.next() else {
        eprintln!("usage: rux-disasm <input.ruxi>");
        return ExitCode::from(2);
    };
    if args.next().is_some() {
        eprintln!("usage: rux-disasm <input.ruxi>");
        return ExitCode::from(2);
    }

    let bytes = match fs::read(&input_path) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("failed to read {input_path}: {error}");
            return ExitCode::from(1);
        }
    };
    let disassembly = match disassemble_bytes(&bytes) {
        Ok(disassembly) => disassembly,
        Err(error) => {
            eprintln!("decode error: {error}");
            return ExitCode::from(1);
        }
    };

    print!("{disassembly}");
    ExitCode::SUCCESS
}
