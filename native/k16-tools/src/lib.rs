pub mod advice;
pub mod artifact;
pub mod cli;
mod frontend;
pub mod inspect;
mod k16_asm;
pub mod k16_disasm;
pub mod k16_runtime;
pub mod k16e;
pub mod k16fs;
pub mod k16fs_volume;
pub mod object_link;
pub mod partition;
mod runtime;
pub mod volume;

pub use frontend::{lex, CompileError, Token, TokenKind};

pub fn compile_k16_artifact(
    source: &str,
    target: artifact::K16ArtifactTarget,
) -> Result<artifact::K16Artifact, CompileError> {
    let tokens = lex(source)?;
    let program = frontend::parse(tokens)?;
    let program = frontend::resolve(program)?;
    artifact::compile(program, target)
}
