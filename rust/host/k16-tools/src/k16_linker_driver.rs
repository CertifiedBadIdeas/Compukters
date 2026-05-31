use crate::artifact::K16ArtifactTarget;
use crate::object_link::{link_k16_objects_to_k16e, K16LinkInput};
use std::fs;

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
    let output = link_k16_objects_to_k16e(&inputs, config.target)?;
    fs::write(&config.output_path, output).map_err(|error| {
        format!(
            "failed to write linker output {}: {error}",
            config.output_path
        )
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LinkerDriverConfig {
    target: K16ArtifactTarget,
    output_path: String,
    input_paths: Vec<String>,
}

fn parse_linker_args(args: &[String]) -> Result<LinkerDriverConfig, String> {
    let mut target = None;
    let mut output_path = None;
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
            "-flavor" | "-z" | "-L" | "-l" | "-m" => {
                index += 1;
                if args.get(index).is_none() {
                    return Err(format!("k16-ld requires a value after {arg}"));
                }
            }
            "--as-needed" | "-Bstatic" | "-Bdynamic" | "--eh-frame-hdr" | "--gc-sections"
            | "--no-gc-sections" => {}
            _ if arg.starts_with("--k16-target=") => {
                let value = arg
                    .strip_prefix("--k16-target=")
                    .expect("prefix already checked");
                target = Some(K16ArtifactTarget::parse(value)?);
            }
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
        input_paths,
    })
}

fn is_link_input(arg: &str) -> bool {
    arg.ends_with(".o") || arg.ends_with(".rlib") || arg.ends_with(".a")
}
