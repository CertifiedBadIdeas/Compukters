use crate::frontend::ast::{Expr, FunctionDecl, Program, ReturnType, Statement, TypeName};
use crate::frontend::CompileError;
use crate::rux16_asm;
use rux_vm::computer_abi;

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
    let main = supported_main(&program)?;
    let mut backend = Rux16ArtifactBackend::new();
    backend.compile_statements(&main.statements, false)?;
    backend.words.push(rux16_asm::halt());

    Ok(Rux16Artifact {
        target,
        bytes: rux16_asm::encode_words(&backend.words),
    })
}

struct Rux16ArtifactBackend {
    words: Vec<u16>,
}

impl Rux16ArtifactBackend {
    fn new() -> Self {
        Self { words: Vec::new() }
    }

    fn compile_statements(
        &mut self,
        statements: &[Statement],
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        for statement in statements {
            self.compile_statement(statement, unsafe_context)?;
        }
        Ok(())
    }

    fn compile_statement(
        &mut self,
        statement: &Statement,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match statement {
            Statement::Unsafe(statements) => self.compile_statements(statements, true),
            Statement::Expr(expr) => self.compile_expr_statement(expr, unsafe_context),
            Statement::Let { .. }
            | Statement::Assign { .. }
            | Statement::AssignOp { .. }
            | Statement::IndexAssign { .. }
            | Statement::DerefAssign { .. }
            | Statement::If { .. }
            | Statement::While { .. }
            | Statement::Break
            | Statement::Continue
            | Statement::Return(_) => {
                unsupported("only unsafe MMIO store statements can be lowered")
            }
        }
    }

    fn compile_expr_statement(
        &mut self,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let Expr::MethodCall {
            receiver,
            method,
            args,
        } = expr
        else {
            return unsupported("only memory method calls can be lowered as statements");
        };
        if method != "store" {
            return unsupported("only memory `.store(...)` calls can be lowered");
        }
        if args.len() != 1 {
            return unsupported("memory `.store(...)` requires exactly one argument");
        }
        let Expr::Mmio { ty, address } = receiver.as_ref() else {
            return unsupported("only `mmio<T>(...).store(...)` can be lowered");
        };
        if !unsafe_context {
            return unsupported("MMIO store requires `unsafe`");
        }
        if *ty != TypeName::I32 {
            return unsupported("only `mmio<i32>(...).store(...)` can be lowered");
        }

        let address = eval_mmio_address(address)?;
        let value = eval_i32_value(&args[0])?;
        self.words
            .extend_from_slice(&rux16_asm::const32(1, address));
        self.words
            .extend_from_slice(&rux16_asm::const32(2, value as u32));
        self.words.push(rux16_asm::store32(1, 2));
        Ok(())
    }
}

fn supported_main(program: &Program) -> Result<&FunctionDecl, CompileError> {
    if !program.uses.is_empty() || !program.consts.is_empty() {
        return unsupported("uses and consts are not supported by the Rux16 backend yet");
    }
    if program.functions.len() != 1 {
        return unsupported("only a single `main` function is supported by the Rux16 backend yet");
    }
    let main = &program.functions[0];
    if main.name != "main" || !main.parameters.is_empty() || main.return_type != ReturnType::Unit {
        return unsupported(
            "only `fn main()` with unit return is supported by the Rux16 backend yet",
        );
    }
    Ok(main)
}

fn eval_mmio_address(expr: &Expr) -> Result<u32, CompileError> {
    match expr {
        Expr::Local(name) if name == "DEBUG_WRITE" => Ok(computer_abi::DEBUG_WRITE),
        Expr::Local(name) => unsupported(format!("unknown Rux16 MMIO address `{name}`")),
        Expr::Int(value) => u32::try_from(*value).map_err(|_| CompileError {
            message: format!("MMIO address literal `{value}` does not fit `u32`"),
        }),
        Expr::IntU32(value) => u32::try_from(*value).map_err(|_| CompileError {
            message: format!("MMIO address literal `{value}u32` does not fit `u32`"),
        }),
        Expr::IntU8(value) => u32::try_from(*value).map_err(|_| CompileError {
            message: format!("MMIO address literal `{value}u8` does not fit `u32`"),
        }),
        Expr::Binary { .. }
        | Expr::ByteString(_)
        | Expr::Bool(_)
        | Expr::Call { .. }
        | Expr::Mmio { .. }
        | Expr::Ptr { .. }
        | Expr::MethodCall { .. }
        | Expr::Index { .. }
        | Expr::AddressOfMut(_)
        | Expr::Deref(_)
        | Expr::Cast { .. }
        | Expr::Unary { .. }
        | Expr::Logical { .. }
        | Expr::Compare { .. } => {
            unsupported("only literal or DEBUG_WRITE MMIO addresses can be lowered")
        }
    }
}

fn eval_i32_value(expr: &Expr) -> Result<i32, CompileError> {
    match expr {
        Expr::Int(value) => i32::try_from(*value).map_err(|_| CompileError {
            message: format!("integer literal `{value}` does not fit `i32`"),
        }),
        Expr::IntU8(value) => i32::try_from(*value).map_err(|_| CompileError {
            message: format!("u8 literal `{value}` does not fit `i32`"),
        }),
        Expr::Local(name) => unsupported(format!("unknown Rux16 i32 value `{name}`")),
        Expr::IntU32(value) => Err(CompileError {
            message: format!("u32 literal `{value}` cannot be stored as i32 without a cast"),
        }),
        Expr::Binary { .. }
        | Expr::ByteString(_)
        | Expr::Bool(_)
        | Expr::Call { .. }
        | Expr::Mmio { .. }
        | Expr::Ptr { .. }
        | Expr::MethodCall { .. }
        | Expr::Index { .. }
        | Expr::AddressOfMut(_)
        | Expr::Deref(_)
        | Expr::Cast { .. }
        | Expr::Unary { .. }
        | Expr::Logical { .. }
        | Expr::Compare { .. } => unsupported("only i32 literal store values can be lowered"),
    }
}

fn unsupported<T>(message: impl Into<String>) -> Result<T, CompileError> {
    Err(CompileError {
        message: format!(
            "Rux16 backend does not support this program yet: {}",
            message.into()
        ),
    })
}
