pub mod advice;
pub mod artifact;
pub mod cli;
mod frontend;
pub mod inspect;
pub mod object_link;
pub mod partition;
mod runtime;
mod rux16_asm;
pub mod rux16_disasm;
pub mod rux16_runtime;
pub mod ruxe;
pub mod ruxfs;
pub mod ruxfs_volume;
pub mod volume;

pub use frontend::{lex, CompileError, Token, TokenKind};

pub fn compile_rux16_artifact(
    source: &str,
    target: artifact::Rux16ArtifactTarget,
) -> Result<artifact::Rux16Artifact, CompileError> {
    let tokens = lex(source)?;
    let program = frontend::parse(tokens)?;
    let program = frontend::resolve(program)?;
    artifact::compile(program, target)
}
