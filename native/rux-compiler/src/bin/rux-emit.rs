use rux_compiler::compile;
use rux_vm::low_image::encode_image;
use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(source_path) = args.next() else {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    };
    let Some(output_path) = args.next() else {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    };
    if args.next().is_some() {
        eprintln!("usage: rux-emit <input.rx> <output.ruxi>");
        return ExitCode::from(2);
    }

    let source = match fs::read_to_string(&source_path) {
        Ok(source) => source,
        Err(error) => {
            eprintln!("failed to read {source_path}: {error}");
            return ExitCode::from(1);
        }
    };
    let image = match compile(&source) {
        Ok(image) => image,
        Err(error) => {
            eprintln!("compile error: {}", error.message);
            return ExitCode::from(1);
        }
    };
    let bytes = match encode_image(&image) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("encode error: {error}");
            return ExitCode::from(1);
        }
    };
    if let Err(error) = fs::write(&output_path, bytes) {
        eprintln!("failed to write {output_path}: {error}");
        return ExitCode::from(1);
    }
    ExitCode::SUCCESS
}
