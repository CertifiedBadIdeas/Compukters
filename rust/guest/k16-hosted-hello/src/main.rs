use std::io::Write;

fn main() {
    println!("hosted std hello");
    std::io::stdout().flush().unwrap();
}
