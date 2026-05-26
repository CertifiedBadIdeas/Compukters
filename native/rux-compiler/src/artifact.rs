use crate::frontend::ast::{
    ConstDecl, Expr, FunctionDecl, Program, ReturnType, Statement, TypeName,
};
use crate::frontend::CompileError;
use crate::rux16_asm;
use rux_vm::computer_abi;
use std::collections::HashMap;

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
    let consts = evaluate_consts(&program.consts)?;
    let main = supported_main(&program)?;
    let mut backend = Rux16ArtifactBackend::new(consts);
    backend.compile_statements(&main.statements, false)?;
    backend.words.push(rux16_asm::halt());

    Ok(Rux16Artifact {
        target,
        bytes: rux16_asm::encode_words(&backend.words),
    })
}

struct Rux16ArtifactBackend {
    words: Vec<u16>,
    consts: HashMap<String, i32>,
}

impl Rux16ArtifactBackend {
    fn new(consts: HashMap<String, i32>) -> Self {
        Self {
            words: Vec::new(),
            consts,
        }
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

        let address = self.eval_mmio_address(address)?;
        let value = self.eval_i32_value(&args[0])?;
        self.words
            .extend_from_slice(&rux16_asm::const32(1, address));
        self.words
            .extend_from_slice(&rux16_asm::const32(2, value as u32));
        self.words.push(rux16_asm::store32(1, 2));
        Ok(())
    }

    fn eval_mmio_address(&self, expr: &Expr) -> Result<u32, CompileError> {
        match expr {
            Expr::Local(name) => match resolve_builtin_constant(name) {
                Some(BuiltinConstant::Addr(value)) => Ok(value),
                Some(BuiltinConstant::I32(_)) => {
                    unsupported(format!("`{name}` is an i32 value, expected MMIO address"))
                }
                None if self.consts.contains_key(name) => unsupported(format!(
                    "const `{name}` is an i32 value, expected MMIO address"
                )),
                None => unsupported(format!("unknown Rux16 MMIO address `{name}`")),
            },
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
                unsupported("only literal or ABI MMIO addresses can be lowered")
            }
        }
    }

    fn eval_i32_value(&self, expr: &Expr) -> Result<i32, CompileError> {
        match expr {
            Expr::Int(value) => i32::try_from(*value).map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `i32`"),
            }),
            Expr::IntU8(value) => i32::try_from(*value).map_err(|_| CompileError {
                message: format!("u8 literal `{value}` does not fit `i32`"),
            }),
            Expr::Local(name) => {
                if let Some(value) = self.consts.get(name).copied() {
                    return Ok(value);
                }
                match resolve_builtin_constant(name) {
                    Some(BuiltinConstant::I32(value)) => Ok(value),
                    Some(BuiltinConstant::Addr(_)) => {
                        unsupported(format!("`{name}` is an address, expected i32 value"))
                    }
                    None => unsupported(format!("unknown Rux16 i32 value `{name}`")),
                }
            }
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
}

fn supported_main(program: &Program) -> Result<&FunctionDecl, CompileError> {
    if !program.uses.is_empty() {
        return unsupported("uses are not supported by the Rux16 backend yet");
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BuiltinConstant {
    Addr(u32),
    I32(i32),
}

fn resolve_builtin_constant(name: &str) -> Option<BuiltinConstant> {
    match name {
        "CONTROL_BASE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_BASE)),
        "CONTROL_STATUS" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_STATUS)),
        "CONTROL_PANIC_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_PANIC_CODE)),
        "CONTROL_EXIT_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_EXIT_CODE)),
        "DEBUG_BASE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_BASE)),
        "DEBUG_WRITE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_WRITE)),
        "STATUS_RESET" => Some(BuiltinConstant::I32(computer_abi::STATUS_RESET)),
        "STATUS_BOOTING" => Some(BuiltinConstant::I32(computer_abi::STATUS_BOOTING)),
        "STATUS_READY" => Some(BuiltinConstant::I32(computer_abi::STATUS_READY)),
        "STATUS_HALTED" => Some(BuiltinConstant::I32(computer_abi::STATUS_HALTED)),
        "STATUS_PANIC" => Some(BuiltinConstant::I32(computer_abi::STATUS_PANIC)),
        _ => None,
    }
}

fn evaluate_consts(consts: &[ConstDecl]) -> Result<HashMap<String, i32>, CompileError> {
    let mut values = HashMap::new();
    for declaration in consts {
        if resolve_builtin_constant(&declaration.name).is_some() {
            return Err(CompileError {
                message: format!(
                    "const `{}` cannot shadow built-in ABI constant",
                    declaration.name
                ),
            });
        }
        if values.contains_key(&declaration.name) {
            return Err(CompileError {
                message: format!("duplicate const `{}`", declaration.name),
            });
        }
        let value = evaluate_const_expr(&declaration.value, &values)?;
        values.insert(declaration.name.clone(), value);
    }
    Ok(values)
}

fn evaluate_const_expr(
    expr: &Expr,
    source_consts: &HashMap<String, i32>,
) -> Result<i32, CompileError> {
    match expr {
        Expr::Int(value) => i32::try_from(*value).map_err(|_| CompileError {
            message: format!("integer literal `{value}` does not fit `i32`"),
        }),
        Expr::IntU8(value) => i32::try_from(*value).map_err(|_| CompileError {
            message: format!("u8 literal `{value}` does not fit `i32`"),
        }),
        Expr::Local(name) => {
            if let Some(value) = source_consts.get(name).copied() {
                return Ok(value);
            }
            match resolve_builtin_constant(name) {
                Some(BuiltinConstant::I32(value)) => Ok(value),
                Some(BuiltinConstant::Addr(_)) => Err(CompileError {
                    message: format!("const initializer `{name}` is an address, expected `i32`"),
                }),
                None => Err(CompileError {
                    message: format!("unknown const initializer identifier `{name}`"),
                }),
            }
        }
        Expr::IntU32(value) => Err(CompileError {
            message: format!("u32 literal `{value}` cannot initialize an i32 const without a cast"),
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
        | Expr::Compare { .. } => Err(CompileError {
            message: "const initializer is not compile-time evaluable".to_string(),
        }),
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
