use std::io::{Read, Write};

const BUFFER_SIZE: usize = 512;

fn main() {
    let mut had_path = false;
    let mut failed = false;
    let mut stdout = std::io::stdout();

    for path in std::env::args().skip(1) {
        had_path = true;
        if copy_path_to_stdout(&path, &mut stdout).is_err() {
            write_io_error(&path, &mut stdout);
            failed = true;
        }
    }

    if !had_path || failed {
        std::process::exit(1);
    }
}

fn copy_path_to_stdout(path: &str, stdout: &mut std::io::Stdout) -> std::io::Result<()> {
    let mut file = std::fs::File::open(path)?;
    let mut buffer = [0; BUFFER_SIZE];

    loop {
        let bytes_read = file.read(&mut buffer)?;
        if bytes_read == 0 {
            return Ok(());
        }
        stdout.write_all(&buffer[..bytes_read])?;
    }
}

fn write_io_error(path: &str, stdout: &mut std::io::Stdout) {
    let _ = stdout.write_all(b"ERR IO ");
    let _ = stdout.write_all(path.as_bytes());
    let _ = stdout.write_all(b"\n");
}
