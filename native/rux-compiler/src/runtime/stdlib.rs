pub(crate) fn module_source(module: &str) -> Option<&'static str> {
    match module {
        "computer" => Some(include_str!("../../stdlib/std/computer.rx")),
        "display" => Some(include_str!("../../stdlib/std/display.rx")),
        "hardware" => Some(include_str!("../../stdlib/std/hardware.rx")),
        "io" => Some(include_str!("../../stdlib/std/io.rx")),
        "mem" => Some(include_str!("../../stdlib/std/mem.rx")),
        _ => None,
    }
}
