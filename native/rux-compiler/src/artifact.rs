use crate::frontend::ast::{
    CompareOp, ConstDecl, Expr, FunctionDecl, Program, ReturnType, Statement, TypeName,
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
            Self::Boot => 2048,
            Self::Program => 0,
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
    let functions = collect_supported_functions(&program)?;
    let mut backend = Rux16ArtifactBackend::new(consts, functions, target.base_address());
    backend.inline_function("main")?;
    backend.words.push(rux16_asm::halt());

    Ok(Rux16Artifact {
        target,
        bytes: rux16_asm::encode_words(&backend.words),
    })
}

struct Rux16ArtifactBackend {
    words: Vec<u16>,
    consts: HashMap<String, i32>,
    functions: HashMap<String, FunctionDecl>,
    locals: HashMap<String, Rux16Local>,
    call_stack: Vec<String>,
    next_register: u8,
    base_address: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Rux16Local {
    ty: TypeName,
    register: u8,
}

impl Rux16ArtifactBackend {
    fn new(
        consts: HashMap<String, i32>,
        functions: HashMap<String, FunctionDecl>,
        base_address: u32,
    ) -> Self {
        Self {
            words: Vec::new(),
            consts,
            functions,
            locals: HashMap::new(),
            call_stack: Vec::new(),
            next_register: 3,
            base_address,
        }
    }

    fn inline_function(&mut self, name: &str) -> Result<(), CompileError> {
        if self.call_stack.iter().any(|active| active == name) {
            return unsupported(format!("recursive Rux16 helper call `{name}`"));
        }
        let function = self
            .functions
            .get(name)
            .cloned()
            .ok_or_else(|| CompileError {
                message: format!("Rux16 backend does not support this program yet: unknown helper function `{name}`"),
        })?;
        self.call_stack.push(name.to_string());
        let caller_locals = std::mem::take(&mut self.locals);
        let result = self.compile_statements(&function.statements, false);
        self.locals = caller_locals;
        self.call_stack.pop();
        result
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
            Statement::Let {
                name,
                ty,
                initializer,
            } => self.compile_let_statement(name, *ty, initializer.as_ref(), unsafe_context),
            Statement::If {
                condition,
                then_branch,
                else_branch,
            } => self.compile_if_statement(
                condition,
                then_branch,
                else_branch.as_deref(),
                unsafe_context,
            ),
            Statement::While { condition, body } => {
                self.compile_while_statement(condition, body, unsafe_context)
            }
            Statement::Assign { name, value } => {
                self.compile_assign_statement(name, value, unsafe_context)
            }
            Statement::AssignOp { .. }
            | Statement::IndexAssign { .. }
            | Statement::DerefAssign { .. }
            | Statement::Break
            | Statement::Continue
            | Statement::Return(_) => {
                unsupported("only unsafe MMIO store statements can be lowered")
            }
        }
    }

    fn compile_let_statement(
        &mut self,
        name: &str,
        ty: TypeName,
        initializer: Option<&Expr>,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        if self.locals.contains_key(name) {
            return unsupported(format!("duplicate Rux16 local `{name}`"));
        }
        let initializer = initializer.ok_or_else(|| CompileError {
            message: format!(
                "Rux16 backend does not support this program yet: local `{name}` requires an initializer"
            ),
        })?;
        let register = self.alloc_register()?;
        match ty {
            TypeName::I32 => self.compile_i32_expr_into(register, initializer, unsafe_context)?,
            TypeName::U8 => self.compile_u8_expr_into(register, initializer, unsafe_context)?,
            TypeName::U32
            | TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                return unsupported("only i32 and u8 locals can be lowered to Rux16 yet");
            }
        }
        self.locals
            .insert(name.to_string(), Rux16Local { ty, register });
        Ok(())
    }

    fn compile_assign_statement(
        &mut self,
        name: &str,
        value: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let local = self.locals.get(name).copied().ok_or_else(|| CompileError {
            message: format!(
                "Rux16 backend does not support this program yet: unknown local `{name}`"
            ),
        })?;
        match local.ty {
            TypeName::I32 => self.compile_i32_expr_into(local.register, value, unsafe_context),
            TypeName::U8 => self.compile_u8_expr_into(local.register, value, unsafe_context),
            TypeName::U32
            | TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unsupported("only i32 and u8 locals can be assigned in Rux16 yet")
            }
        }
    }

    fn compile_if_statement(
        &mut self,
        condition: &Expr,
        then_branch: &[Statement],
        else_branch: Option<&[Statement]>,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        self.compile_condition_into(2, condition, unsafe_context)?;
        self.words.push(rux16_asm::branch_if_nonzero(2, 4));
        let false_jump = self.emit_absolute_jump_placeholder();
        self.compile_statements(then_branch, unsafe_context)?;
        if let Some(else_branch) = else_branch {
            let end_jump = self.emit_absolute_jump_placeholder();
            self.patch_absolute_jump(false_jump, self.current_address())?;
            self.compile_statements(else_branch, unsafe_context)?;
            self.patch_absolute_jump(end_jump, self.current_address())?;
        } else {
            self.patch_absolute_jump(false_jump, self.current_address())?;
        }
        Ok(())
    }

    fn compile_while_statement(
        &mut self,
        condition: &Expr,
        body: &[Statement],
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let loop_start = self.current_address();
        self.compile_condition_into(2, condition, unsafe_context)?;
        self.words.push(rux16_asm::branch_if_nonzero(2, 4));
        let exit_jump = self.emit_absolute_jump_placeholder();
        self.compile_statements(body, unsafe_context)?;
        self.emit_absolute_jump(loop_start);
        self.patch_absolute_jump(exit_jump, self.current_address())?;
        Ok(())
    }

    fn compile_expr_statement(
        &mut self,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        if let Expr::Call { name, args } = expr {
            if name == "rux16_jump" {
                if args.len() != 1 {
                    return unsupported("Rux16 jump requires exactly one target argument");
                }
                if !unsafe_context {
                    return unsupported("Rux16 jump requires `unsafe`");
                }
                let target = self.compile_i32_expr_to_scratch(&args[0], unsafe_context)?;
                self.words.push(rux16_asm::jmp(target));
                return Ok(());
            }
            if !args.is_empty() {
                return unsupported("Rux16 helper calls do not support arguments yet");
            }
            return self.inline_function(name);
        }

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
        let address = self.eval_mmio_address(address)?;
        self.words
            .extend_from_slice(&rux16_asm::const32(1, address));
        match ty {
            TypeName::I32 => {
                let src = self.compile_i32_expr_to_scratch(&args[0], unsafe_context)?;
                self.words.push(rux16_asm::store32(1, src));
            }
            TypeName::U8 => {
                let src = self.compile_u8_expr_to_scratch(&args[0], unsafe_context)?;
                self.words.push(rux16_asm::store8(1, src));
            }
            TypeName::U32
            | TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                return unsupported("only `mmio<i32>` and `mmio<u8>` stores can be lowered");
            }
        }
        Ok(())
    }

    fn compile_condition_into(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let Expr::Compare { op, lhs, rhs } = expr else {
            return unsupported("only equality comparisons can be lowered as Rux16 conditions");
        };
        let lhs = self.compile_i32_expr_to_scratch(lhs, unsafe_context)?;
        let rhs = self.compile_i32_expr_to_register_or_use(14, rhs, unsafe_context)?;
        self.words.extend_from_slice(&rux16_asm::eq(dst, lhs, rhs));
        match op {
            CompareOp::Eq => Ok(()),
            CompareOp::Ne => {
                self.words.extend_from_slice(&rux16_asm::eq(dst, dst, 0));
                Ok(())
            }
            CompareOp::Lt | CompareOp::Gt | CompareOp::Le | CompareOp::Ge => {
                unsupported("only `==` and `!=` comparisons can be lowered as Rux16 conditions")
            }
        }
    }

    fn compile_i32_expr_to_scratch(
        &mut self,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::I32)? {
                return Ok(local.register);
            }
        }
        self.compile_i32_expr_into(2, expr, unsafe_context)?;
        Ok(2)
    }

    fn compile_i32_expr_to_register_or_use(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::I32)? {
                return Ok(local.register);
            }
        }
        self.compile_i32_expr_into(dst, expr, unsafe_context)?;
        Ok(dst)
    }

    fn compile_u8_expr_to_scratch(
        &mut self,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::U8)? {
                return Ok(local.register);
            }
        }
        self.compile_u8_expr_into(2, expr, unsafe_context)?;
        Ok(2)
    }

    fn compile_i32_expr_into(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match expr {
            Expr::Local(name) => {
                if self.local(name, TypeName::I32)?.is_some() {
                    return unsupported("Rux16 local-to-local moves are not supported yet");
                }
                let value = self.eval_i32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value as u32));
                Ok(())
            }
            Expr::MethodCall {
                receiver,
                method,
                args,
            } if method == "load" => {
                if !args.is_empty() {
                    return unsupported("memory `.load()` requires no arguments");
                }
                if !unsafe_context {
                    return unsupported("memory load requires `unsafe`");
                }
                match receiver.as_ref() {
                    Expr::Mmio { ty, address } => {
                        if *ty != TypeName::I32 {
                            return unsupported(
                                "i32 local initializer requires `mmio<i32>(...).load()`",
                            );
                        }
                        let address = self.eval_mmio_address(address)?;
                        self.words
                            .extend_from_slice(&rux16_asm::const32(1, address));
                        self.words.push(rux16_asm::load32(dst, 1));
                        Ok(())
                    }
                    Expr::Ptr { ty, address } => {
                        if *ty != TypeName::I32 {
                            return unsupported(
                                "i32 local initializer requires `ptr<i32>(...).load()`",
                            );
                        }
                        let address = self.eval_ram_address(address)?;
                        self.words
                            .extend_from_slice(&rux16_asm::const32(1, address));
                        self.words.push(rux16_asm::load32(dst, 1));
                        Ok(())
                    }
                    _ => unsupported(
                        "only `mmio<T>(...).load()` and `ptr<T>(...).load()` can be lowered",
                    ),
                }
            }
            _ => {
                let value = self.eval_i32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value as u32));
                Ok(())
            }
        }
    }

    fn compile_u8_expr_into(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match expr {
            Expr::Local(name) => {
                if self.local(name, TypeName::U8)?.is_some() {
                    return unsupported("Rux16 local-to-local moves are not supported yet");
                }
                let value = self.eval_u8_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, u32::from(value)));
                Ok(())
            }
            Expr::MethodCall {
                receiver,
                method,
                args,
            } if method == "load" => {
                if !args.is_empty() {
                    return unsupported("memory `.load()` requires no arguments");
                }
                if !unsafe_context {
                    return unsupported("memory load requires `unsafe`");
                }
                match receiver.as_ref() {
                    Expr::Mmio { ty, address } => {
                        if *ty != TypeName::U8 {
                            return unsupported(
                                "u8 local initializer requires `mmio<u8>(...).load()`",
                            );
                        }
                        let address = self.eval_mmio_address(address)?;
                        self.words
                            .extend_from_slice(&rux16_asm::const32(1, address));
                        self.words.push(rux16_asm::load8(dst, 1));
                        Ok(())
                    }
                    Expr::Ptr { ty, address } => {
                        if *ty != TypeName::U8 {
                            return unsupported(
                                "u8 local initializer requires `ptr<u8>(...).load()`",
                            );
                        }
                        let address = self.eval_ram_address(address)?;
                        self.words
                            .extend_from_slice(&rux16_asm::const32(1, address));
                        self.words.push(rux16_asm::load8(dst, 1));
                        Ok(())
                    }
                    _ => unsupported(
                        "only `mmio<T>(...).load()` and `ptr<T>(...).load()` can be lowered",
                    ),
                }
            }
            _ => {
                let value = self.eval_u8_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, u32::from(value)));
                Ok(())
            }
        }
    }

    fn local(&self, name: &str, expected: TypeName) -> Result<Option<Rux16Local>, CompileError> {
        let Some(local) = self.locals.get(name).copied() else {
            return Ok(None);
        };
        if local.ty != expected {
            return unsupported(format!(
                "local `{name}` has type {}, expected {}",
                type_name(local.ty),
                type_name(expected)
            ));
        }
        Ok(Some(local))
    }

    fn alloc_register(&mut self) -> Result<u8, CompileError> {
        let register = self.next_register;
        if register > 13 {
            return unsupported("Rux16 backend ran out of registers");
        }
        self.next_register += 1;
        Ok(register)
    }

    fn current_address(&self) -> u32 {
        self.base_address + self.words.len() as u32 * 2
    }

    fn emit_absolute_jump_placeholder(&mut self) -> usize {
        let const_index = self.words.len();
        self.words.extend_from_slice(&rux16_asm::const32(15, 0));
        self.words.push(rux16_asm::jmp(15));
        const_index
    }

    fn emit_absolute_jump(&mut self, address: u32) {
        self.words
            .extend_from_slice(&rux16_asm::const32(15, address));
        self.words.push(rux16_asm::jmp(15));
    }

    fn patch_absolute_jump(
        &mut self,
        const_index: usize,
        address: u32,
    ) -> Result<(), CompileError> {
        let low_index = const_index.checked_add(1).ok_or_else(|| CompileError {
            message: "Rux16 absolute jump patch index overflow".to_string(),
        })?;
        let high_index = const_index.checked_add(2).ok_or_else(|| CompileError {
            message: "Rux16 absolute jump patch index overflow".to_string(),
        })?;
        let low = self.words.get_mut(low_index).ok_or_else(|| CompileError {
            message: "Rux16 absolute jump patch index is out of bounds".to_string(),
        })?;
        *low = (address & 0xffff) as u16;
        let high = self.words.get_mut(high_index).ok_or_else(|| CompileError {
            message: "Rux16 absolute jump patch index is out of bounds".to_string(),
        })?;
        *high = (address >> 16) as u16;
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

    fn eval_ram_address(&self, expr: &Expr) -> Result<u32, CompileError> {
        match expr {
            Expr::Int(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("RAM address literal `{value}` does not fit `u32`"),
            }),
            Expr::IntU32(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("RAM address literal `{value}u32` does not fit `u32`"),
            }),
            Expr::IntU8(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("RAM address literal `{value}u8` does not fit `u32`"),
            }),
            Expr::Local(name) => {
                if let Some(value) = self.consts.get(name).copied() {
                    return u32::try_from(value).map_err(|_| CompileError {
                        message: format!("const `{name}` value `{value}` does not fit `u32`"),
                    });
                }
                unsupported(format!("unknown Rux16 RAM address `{name}`"))
            }
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
                unsupported("only literal or const RAM addresses can be lowered")
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

    fn eval_u8_value(&self, expr: &Expr) -> Result<u8, CompileError> {
        match expr {
            Expr::Int(value) => u8::try_from(*value).map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `u8`"),
            }),
            Expr::IntU8(value) => u8::try_from(*value).map_err(|_| CompileError {
                message: format!("u8 literal `{value}` does not fit `u8`"),
            }),
            Expr::Local(name) => {
                if let Some(value) = self.consts.get(name).copied() {
                    return u8::try_from(value).map_err(|_| CompileError {
                        message: format!("const `{name}` value `{value}` does not fit `u8`"),
                    });
                }
                match resolve_builtin_constant(name) {
                    Some(BuiltinConstant::I32(value)) => {
                        u8::try_from(value).map_err(|_| CompileError {
                            message: format!(
                                "ABI constant `{name}` value `{value}` does not fit `u8`"
                            ),
                        })
                    }
                    Some(BuiltinConstant::Addr(_)) => {
                        unsupported(format!("`{name}` is an address, expected u8 value"))
                    }
                    None => unsupported(format!("unknown Rux16 u8 value `{name}`")),
                }
            }
            Expr::IntU32(value) => Err(CompileError {
                message: format!("u32 literal `{value}` cannot be stored as u8 without a cast"),
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
            | Expr::Compare { .. } => unsupported("only u8 literal store values can be lowered"),
        }
    }
}

fn collect_supported_functions(
    program: &Program,
) -> Result<HashMap<String, FunctionDecl>, CompileError> {
    if !program.uses.is_empty() {
        return unsupported("uses are not supported by the Rux16 backend yet");
    }
    let mut functions = HashMap::new();
    for function in &program.functions {
        if !function.parameters.is_empty() || function.return_type != ReturnType::Unit {
            return unsupported(
                "only no-argument functions with unit return are supported by the Rux16 backend yet",
            );
        }
        if functions
            .insert(function.name.clone(), function.clone())
            .is_some()
        {
            return Err(CompileError {
                message: format!("duplicate function `{}`", function.name),
            });
        }
    }
    if !functions.contains_key("main") {
        return unsupported(
            "a no-argument `fn main()` with unit return is required by the Rux16 backend",
        );
    }
    Ok(functions)
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
        "DISPLAY0_BASE" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_BASE)),
        "DISPLAY0_COLUMNS" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_COLUMNS)),
        "DISPLAY0_ROWS" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_ROWS)),
        "DISPLAY0_CURSOR_X" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_CURSOR_X)),
        "DISPLAY0_CURSOR_Y" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_CURSOR_Y)),
        "DISPLAY0_COMMAND" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_COMMAND)),
        "DISPLAY0_DATA" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_DATA)),
        "DISPLAY0_SEQUENCE_LOW" => Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_SEQUENCE_LOW)),
        "DISPLAY0_SEQUENCE_HIGH" => {
            Some(BuiltinConstant::Addr(computer_abi::DISPLAY0_SEQUENCE_HIGH))
        }
        "DISPLAY0_COMMAND_CLEAR" => {
            Some(BuiltinConstant::I32(computer_abi::DISPLAY0_COMMAND_CLEAR))
        }
        "DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR" => Some(BuiltinConstant::I32(
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR,
        )),
        "DISPLAY0_COMMAND_PUT_BYTE_AT_XY" => Some(BuiltinConstant::I32(
            computer_abi::DISPLAY0_COMMAND_PUT_BYTE_AT_XY,
        )),
        "DISPLAY0_COMMAND_NEWLINE" => {
            Some(BuiltinConstant::I32(computer_abi::DISPLAY0_COMMAND_NEWLINE))
        }
        "SERIAL_INPUT_BASE" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_BASE)),
        "SERIAL_INPUT_READY" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_READY)),
        "SERIAL_INPUT_READ" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_READ)),
        "STORAGE0_BASE" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_BASE)),
        "STORAGE0_STATUS" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_STATUS)),
        "STORAGE0_ERROR" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_ERROR)),
        "STORAGE0_COMMAND" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_COMMAND)),
        "STORAGE0_BLOCK_SIZE" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_BLOCK_SIZE)),
        "STORAGE0_LBA_LOW" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_LBA_LOW)),
        "STORAGE0_LBA_HIGH" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_LBA_HIGH)),
        "STORAGE0_BLOCK_COUNT" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_BLOCK_COUNT)),
        "STORAGE0_BUFFER_ADDR" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_BUFFER_ADDR)),
        "STORAGE0_BYTES_DONE" => Some(BuiltinConstant::Addr(computer_abi::STORAGE0_BYTES_DONE)),
        "STORAGE_STATUS_READY" => Some(BuiltinConstant::I32(computer_abi::STORAGE_STATUS_READY)),
        "STORAGE_STATUS_BUSY" => Some(BuiltinConstant::I32(computer_abi::STORAGE_STATUS_BUSY)),
        "STORAGE_STATUS_DONE" => Some(BuiltinConstant::I32(computer_abi::STORAGE_STATUS_DONE)),
        "STORAGE_STATUS_ERROR" => Some(BuiltinConstant::I32(computer_abi::STORAGE_STATUS_ERROR)),
        "STORAGE_COMMAND_READ_BLOCKS" => Some(BuiltinConstant::I32(
            computer_abi::STORAGE_COMMAND_READ_BLOCKS,
        )),
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

fn type_name(ty: TypeName) -> &'static str {
    match ty {
        TypeName::I32 => "i32",
        TypeName::U32 => "u32",
        TypeName::U8 => "u8",
        TypeName::Bool => "bool",
        TypeName::PtrI32 => "ptr<i32>",
        TypeName::PtrU32 => "ptr<u32>",
        TypeName::PtrU8 => "ptr<u8>",
        TypeName::RefMutI32 => "&mut i32",
        TypeName::RefMutU32 => "&mut u32",
        TypeName::RefMutU8 => "&mut u8",
        TypeName::ArrayU8(_) => "[u8]",
    }
}
