mod ast;
mod codegen;
mod error;
mod lexer;
mod parser;

use ckl_vm::low_image::Image;

pub use error::CompileError;
pub use lexer::{lex, Token, TokenKind};

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = parser::parse(tokens)?;
    codegen::compile(program)
}
