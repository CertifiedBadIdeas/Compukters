use crate::artifact::K16ArtifactTarget;
use crate::object_link::{
    link_k16_objects_with_options, K16LinkDylib, K16LinkImport, K16LinkInput, K16LinkOptions,
};
use std::fs;
use std::path::Path;

pub fn run_k16_linker_driver(args: Vec<String>) -> Result<(), String> {
    let config = parse_linker_args(&args)?;
    let input_bytes = config
        .input_paths
        .iter()
        .map(|path| {
            fs::read(path)
                .map(|bytes| (path.as_str(), bytes))
                .map_err(|error| format!("failed to read linker input {path}: {error}"))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let inputs = input_bytes
        .iter()
        .map(|(path, bytes)| K16LinkInput { name: path, bytes })
        .collect::<Vec<_>>();
    let dylibs = read_dylibs(&config.dylib_paths)?;
    let output = link_k16_objects_with_options(
        &inputs,
        config.target,
        K16LinkOptions {
            shared_cpu_helpers: false,
            imports: config.imports,
            dylibs,
        },
    )?;
    fs::write(&config.output_path, output.bytes).map_err(|error| {
        format!(
            "failed to write linker output {}: {error}",
            config.output_path
        )
    })?;
    if let Some(map_path) = config.map_path {
        fs::write(&map_path, output.map.to_text())
            .map_err(|error| format!("failed to write linker map {map_path}: {error}"))?;
    }
    mark_linker_output_executable(&config.output_path)
}

#[cfg(unix)]
fn mark_linker_output_executable(path: &str) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;

    let metadata = fs::metadata(path)
        .map_err(|error| format!("failed to stat linker output {path}: {error}"))?;
    let mut permissions = metadata.permissions();
    permissions.set_mode(permissions.mode() | 0o111);
    fs::set_permissions(path, permissions)
        .map_err(|error| format!("failed to mark linker output {path} executable: {error}"))
}

#[cfg(not(unix))]
fn mark_linker_output_executable(_path: &str) -> Result<(), String> {
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LinkerDriverConfig {
    target: K16ArtifactTarget,
    output_path: String,
    map_path: Option<String>,
    imports: Vec<K16LinkImport>,
    dylib_paths: Vec<String>,
    input_paths: Vec<String>,
}

fn parse_linker_args(args: &[String]) -> Result<LinkerDriverConfig, String> {
    let mut target = None;
    let mut output_path = None;
    let mut map_path = None;
    let mut imports = Vec::new();
    let mut dylib_paths = Vec::new();
    let mut input_paths = Vec::new();
    let mut index = 0;

    while index < args.len() {
        let arg = &args[index];
        match arg.as_str() {
            "--k16-target" => {
                index += 1;
                let Some(value) = args.get(index) else {
                    return Err("k16-ld requires a value after --k16-target".to_string());
                };
                target = Some(K16ArtifactTarget::parse(value)?);
            }
            "-o" => {
                index += 1;
                let Some(value) = args.get(index) else {
                    return Err("k16-ld requires a value after -o".to_string());
                };
                output_path = Some(value.clone());
            }
            "--map" => {
                index += 1;
                let Some(value) = args.get(index) else {
                    return Err("k16-ld requires a value after --map".to_string());
                };
                map_path = Some(value.clone());
            }
            "--import" => {
                index += 1;
                let Some(value) = args.get(index) else {
                    return Err("k16-ld requires a value after --import".to_string());
                };
                imports.push(parse_k16_import(value)?);
            }
            "--dylib" => {
                index += 1;
                let Some(value) = args.get(index) else {
                    return Err("k16-ld requires a value after --dylib".to_string());
                };
                dylib_paths.push(value.clone());
            }
            "-flavor" | "-z" | "-L" | "-l" | "-m" | "-O" => {
                index += 1;
                if args.get(index).is_none() {
                    return Err(format!("k16-ld requires a value after {arg}"));
                }
            }
            "--as-needed" | "-Bstatic" | "-Bdynamic" | "--eh-frame-hdr" | "--gc-sections"
            | "--no-gc-sections" | "--strip-debug" => {}
            _ if arg.starts_with("--k16-target=") => {
                let value = arg
                    .strip_prefix("--k16-target=")
                    .expect("prefix already checked");
                target = Some(K16ArtifactTarget::parse(value)?);
            }
            _ if is_linker_optimization_arg(arg) => {}
            _ if arg.starts_with("-L") || arg.starts_with("-l") || arg.starts_with("-z") => {}
            _ if arg.starts_with("--") && arg.contains('=') => {}
            _ if arg.starts_with('-') => {
                return Err(format!("k16-ld does not support linker argument `{arg}`"));
            }
            _ if is_link_input(arg) => input_paths.push(arg.clone()),
            _ => return Err(format!("k16-ld does not recognize linker input `{arg}`")),
        }
        index += 1;
    }

    let target = target.ok_or_else(|| "k16-ld requires explicit --k16-target".to_string())?;
    let output_path =
        output_path.ok_or_else(|| "k16-ld requires output path with -o".to_string())?;
    if input_paths.is_empty() {
        return Err("k16-ld requires at least one K16 object or archive input".to_string());
    }

    Ok(LinkerDriverConfig {
        target,
        output_path,
        map_path,
        imports,
        dylib_paths,
        input_paths,
    })
}

fn read_dylibs(paths: &[String]) -> Result<Vec<K16LinkDylib>, String> {
    paths
        .iter()
        .map(|path| {
            let bytes = fs::read(path)
                .map_err(|error| format!("failed to read K16 dylib {path}: {error}"))?;
            let library = dylib_library_name(path)?;
            Ok(K16LinkDylib { library, bytes })
        })
        .collect()
}

fn dylib_library_name(path: &str) -> Result<String, String> {
    Path::new(path)
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .map(ToString::to_string)
        .ok_or_else(|| format!("K16 dylib path `{path}` has no valid UTF-8 file name"))
}

fn parse_k16_import(value: &str) -> Result<K16LinkImport, String> {
    let Some((library, symbol)) = value.split_once(':') else {
        return Err(format!(
            "invalid K16 import `{value}`; expected <library>:<symbol>"
        ));
    };
    if library.is_empty() || symbol.is_empty() || symbol.contains(':') {
        return Err(format!(
            "invalid K16 import `{value}`; expected <library>:<symbol>"
        ));
    }
    Ok(K16LinkImport {
        library: library.to_string(),
        symbol: symbol.to_string(),
    })
}

fn is_link_input(arg: &str) -> bool {
    arg.ends_with(".o") || arg.ends_with(".rlib") || arg.ends_with(".a")
}

fn is_linker_optimization_arg(arg: &str) -> bool {
    matches!(arg, "-O0" | "-O1" | "-O2" | "-O3" | "-Os" | "-Oz")
}
