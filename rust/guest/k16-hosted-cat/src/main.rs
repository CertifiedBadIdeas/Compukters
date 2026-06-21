fn main() {
    let mut had_path = false;
    let mut failed = false;

    for path in std::env::args().skip(1) {
        had_path = true;
        match std::fs::read_to_string(&path) {
            Ok(text) => print!("{text}"),
            Err(_) => {
                println!("ERR IO {path}");
                failed = true;
            }
        }
    }

    if !had_path || failed {
        std::process::exit(1);
    }
}
