mod ast;
mod codegen;
mod error;
mod lexer;
mod parser;
mod resolver;
mod runner;
mod stdlib;

use rux_vm::low_image::Image;

pub use error::CompileError;
pub use lexer::{lex, Token, TokenKind};
pub use runner::{
    render_terminal_ui, run_source, run_source_until_serial_output, run_source_with_limits,
    run_source_with_serial_input, run_source_with_serial_input_and_limits, RuxRunReport,
};

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = parser::parse(tokens)?;
    let program = resolver::resolve(program)?;
    codegen::compile(program)
}
