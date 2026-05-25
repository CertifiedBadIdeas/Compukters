mod backend;
mod frontend;
mod runtime;
pub mod volume;

use rux_vm::low_image::Image;

pub use frontend::{lex, CompileError, Token, TokenKind};
pub use runtime::{
    render_terminal_ui, run_source, run_source_until_serial_output, run_source_with_limits,
    run_source_with_serial_input, run_source_with_serial_input_and_limits, RuxRunReport,
};

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = frontend::parse(tokens)?;
    let program = frontend::resolve(program)?;
    backend::compile(program)
}
