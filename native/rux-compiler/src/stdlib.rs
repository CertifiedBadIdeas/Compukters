pub(crate) fn module_source(module: &str) -> Option<&'static str> {
    match module {
        "io" => Some(include_str!("../stdlib/std/io.rx")),
        "mem" => Some(include_str!("../stdlib/std/mem.rx")),
        _ => None,
    }
}
