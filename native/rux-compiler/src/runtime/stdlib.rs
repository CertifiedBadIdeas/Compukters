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

pub(crate) fn source_for_path(path: &[String]) -> Option<&'static str> {
    match path {
        [root, module] if root == "std" => module_source(module),
        [root, abi, module] if root == "rux" && abi == "abi" && module == "computer" => {
            Some(include_str!("../../stdlib/rux/abi/computer.rx"))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "control" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/control.rx"))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "debug" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/debug.rx"))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "display0" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/display0.rx"))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "memory" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/memory.rx"))
        }
        [root, abi, module, device]
            if root == "rux"
                && abi == "abi"
                && module == "computer"
                && device == "serial_input" =>
        {
            Some(include_str!(
                "../../stdlib/rux/abi/computer/serial_input.rx"
            ))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "status" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/status.rx"))
        }
        [root, abi, module, device]
            if root == "rux" && abi == "abi" && module == "computer" && device == "storage0" =>
        {
            Some(include_str!("../../stdlib/rux/abi/computer/storage0.rx"))
        }
        _ => None,
    }
}
