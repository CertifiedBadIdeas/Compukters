use ckl_vm::low_image::Image;
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompileError {
    pub message: String,
}

impl Display for CompileError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for CompileError {}

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let trimmed = source.trim_start();
    if !trimmed.starts_with("fn") {
        return Err(CompileError {
            message: "expected `fn`".to_string(),
        });
    }
    Err(CompileError {
        message: "compiler seed is not implemented yet".to_string(),
    })
}
