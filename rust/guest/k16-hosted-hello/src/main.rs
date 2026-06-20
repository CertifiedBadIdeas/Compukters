use std::io::Write;

fn main() {
    let mut values = Vec::new();
    for value in 0..5 {
        values.push(value * 2);
    }
    let message = format!(
        "hosted std heap {} {}",
        values.len(),
        values.iter().sum::<i32>()
    );
    println!("{message}");
    std::io::stdout().flush().unwrap();
}
