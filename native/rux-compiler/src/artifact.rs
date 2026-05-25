use crate::frontend::ast::{FunctionDecl, Program, ReturnType};
use crate::frontend::CompileError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rux16ArtifactTarget {
    Bios,
    Boot,
    Program,
}

impl Rux16ArtifactTarget {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "bios" => Ok(Self::Bios),
            "boot" => Ok(Self::Boot),
            "program" => Ok(Self::Program),
            _ => Err(format!(
                "unknown compile target `{value}`; expected bios, boot, or program"
            )),
        }
    }

    pub fn base_address(self) -> u32 {
        match self {
            Self::Bios => rux_vm::computer_machine::ComputerMachine::RUX16_BIOS_FLASH_BASE,
            Self::Boot | Self::Program => 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rux16Artifact {
    pub target: Rux16ArtifactTarget,
    pub bytes: Vec<u8>,
}

pub(crate) fn compile(
    program: Program,
    target: Rux16ArtifactTarget,
) -> Result<Rux16Artifact, CompileError> {
    let main = supported_empty_main(&program)?;
    if !main.statements.is_empty() {
        return unsupported("only empty `fn main() {}` can be lowered to Rux16 artifacts yet");
    }

    Ok(Rux16Artifact {
        target,
        bytes: encode_words(&[halt()]),
    })
}

fn supported_empty_main(program: &Program) -> Result<&FunctionDecl, CompileError> {
    if !program.uses.is_empty() || !program.consts.is_empty() {
        return unsupported("uses and consts are not supported by the Rux16 backend yet");
    }
    if program.functions.len() != 1 {
        return unsupported("only a single `main` function is supported by the Rux16 backend yet");
    }
    let main = &program.functions[0];
    if main.name != "main" || !main.parameters.is_empty() || main.return_type != ReturnType::Unit {
        return unsupported("only `fn main() {}` is supported by the Rux16 backend yet");
    }
    Ok(main)
}

fn unsupported<T>(message: impl Into<String>) -> Result<T, CompileError> {
    Err(CompileError {
        message: format!(
            "Rux16 backend does not support this program yet: {}",
            message.into()
        ),
    })
}

fn encode_words(words: &[u16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(words.len() * 2);
    for word in words {
        bytes.extend_from_slice(&word.to_le_bytes());
    }
    bytes
}

fn halt() -> u16 {
    0x0001
}
