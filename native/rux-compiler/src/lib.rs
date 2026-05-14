mod ast;
mod codegen;
mod error;
mod lexer;
mod parser;
mod runner;

use rux_vm::low_image::Image;

pub use error::CompileError;
pub use lexer::{lex, Token, TokenKind};
pub use runner::{render_terminal_ui, run_source, run_source_with_limits, RuxRunReport};

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = parser::parse(tokens)?;
    codegen::compile(program)
}
