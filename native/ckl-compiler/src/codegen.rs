use crate::ast::*;
use crate::error::CompileError;
use ckl_vm::computer_abi;
use ckl_vm::low_image::{Function, Image, Instruction};
use std::collections::{HashMap, HashSet};

pub(crate) fn compile(program: Program) -> Result<Image, CompileError> {
    Codegen::compile(program)
}

#[derive(Clone, Copy)]
enum ExprValue {
    I32(u16),
    U32(u16),
    Bool(u16),
    Addr(u16),
    Pointer {
        addr: u16,
        kind: PointerKind,
        element_type: TypeName,
    },
    Unit,
}

impl ExprValue {
    fn type_name(self) -> &'static str {
        match self {
            ExprValue::I32(_) => "i32",
            ExprValue::U32(_) => "u32",
            ExprValue::Bool(_) => "bool",
            ExprValue::Addr(_) => "address",
            ExprValue::Pointer { .. } => "pointer",
            ExprValue::Unit => "unit",
        }
    }
}

#[derive(Clone, Copy)]
enum PointerKind {
    Mmio,
    Ptr,
}

impl PointerKind {
    fn access_name(self) -> &'static str {
        match self {
            PointerKind::Mmio => "MMIO",
            PointerKind::Ptr => "pointer",
        }
    }

    fn type_name(self, element_type: TypeName) -> String {
        match self {
            PointerKind::Mmio => format!("mmio<{}>", element_type.name()),
            PointerKind::Ptr => format!("ptr<{}>", element_type.name()),
        }
    }
}

#[derive(Clone, Copy)]
enum AddressContext {
    Mmio,
    Ptr,
}

impl AddressContext {
    fn address_name(self) -> &'static str {
        match self {
            AddressContext::Mmio => "MMIO address",
            AddressContext::Ptr => "pointer address",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BuiltinConstant {
    Addr(u32),
    I32(i32),
}

fn resolve_builtin_constant(name: &str) -> Option<BuiltinConstant> {
    match name {
        "RAM_BASE" => Some(BuiltinConstant::Addr(computer_abi::RAM_BASE)),
        "CONTROL_BASE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_BASE)),
        "CONTROL_STATUS" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_STATUS)),
        "CONTROL_PANIC_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_PANIC_CODE)),
        "CONTROL_EXIT_CODE" => Some(BuiltinConstant::Addr(computer_abi::CONTROL_EXIT_CODE)),
        "CONTROL_SIZE" => Some(BuiltinConstant::I32(computer_abi::CONTROL_SIZE as i32)),
        "DEBUG_BASE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_BASE)),
        "DEBUG_WRITE" => Some(BuiltinConstant::Addr(computer_abi::DEBUG_WRITE)),
        "DEBUG_SIZE" => Some(BuiltinConstant::I32(computer_abi::DEBUG_SIZE as i32)),
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
        Expr::IntU32(value) => Err(CompileError {
            message: format!("u32 literal `{value}` cannot initialize an i32 const without a cast"),
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
        Expr::Binary { op, lhs, rhs } => {
            let lhs = evaluate_const_expr(lhs, source_consts)?;
            let rhs = evaluate_const_expr(rhs, source_consts)?;
            match op {
                BinaryOp::Add => lhs.checked_add(rhs),
                BinaryOp::Sub => lhs.checked_sub(rhs),
                BinaryOp::Mul => lhs.checked_mul(rhs),
                BinaryOp::Div => {
                    if rhs == 0 {
                        None
                    } else {
                        lhs.checked_div(rhs)
                    }
                }
                BinaryOp::BitAnd => Some(lhs & rhs),
                BinaryOp::BitOr => Some(lhs | rhs),
                BinaryOp::BitXor => Some(lhs ^ rhs),
                BinaryOp::Shl => Some(i32_shl_unbounded(lhs, rhs)),
                BinaryOp::Shr => Some(i32_shr_unbounded(lhs, rhs)),
            }
            .ok_or_else(|| CompileError {
                message: "const initializer arithmetic overflow".to_string(),
            })
        }
        Expr::Compare { .. }
        | Expr::Bool(_)
        | Expr::Unary { .. }
        | Expr::Logical { .. }
        | Expr::Call { .. }
        | Expr::Mmio { .. }
        | Expr::Ptr { .. }
        | Expr::MethodCall { .. }
        | Expr::Cast { .. } => Err(CompileError {
            message: "const initializer is not compile-time evaluable".to_string(),
        }),
    }
}

fn i32_shl_unbounded(value: i32, amount: i32) -> i32 {
    if !(0..32).contains(&amount) {
        return 0;
    }
    value.wrapping_shl(amount as u32)
}

fn i32_shr_unbounded(value: i32, amount: i32) -> i32 {
    if !(0..32).contains(&amount) {
        return if value < 0 { -1 } else { 0 };
    }
    value.wrapping_shr(amount as u32)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ValueType {
    I32,
    U32,
    Bool,
}

impl From<TypeName> for ValueType {
    fn from(value: TypeName) -> Self {
        match value {
            TypeName::I32 => ValueType::I32,
            TypeName::U32 => ValueType::U32,
            TypeName::Bool => ValueType::Bool,
        }
    }
}

impl TypeName {
    fn name(self) -> &'static str {
        match self {
            TypeName::I32 => "i32",
            TypeName::U32 => "u32",
            TypeName::Bool => "bool",
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct Local {
    register: u16,
    ty: ValueType,
    mutable: bool,
}

#[derive(Debug, Clone)]
struct FunctionSignature {
    index: usize,
    return_type: ReturnType,
    parameter_types: Vec<TypeName>,
}

fn collect_function_signatures(
    functions: &[FunctionDecl],
    source_consts: &HashMap<String, i32>,
) -> Result<HashMap<String, FunctionSignature>, CompileError> {
    let mut signatures = HashMap::new();
    for (index, function) in functions.iter().enumerate() {
        if resolve_builtin_constant(&function.name).is_some() {
            return Err(CompileError {
                message: format!(
                    "function `{}` cannot shadow built-in ABI constant",
                    function.name
                ),
            });
        }
        if source_consts.contains_key(&function.name) {
            return Err(CompileError {
                message: format!("function `{}` cannot shadow const", function.name),
            });
        }
        if signatures.contains_key(&function.name) {
            return Err(CompileError {
                message: format!("duplicate function `{}`", function.name),
            });
        }
        signatures.insert(
            function.name.clone(),
            FunctionSignature {
                index,
                return_type: function.return_type,
                parameter_types: function
                    .parameters
                    .iter()
                    .map(|parameter| parameter.ty)
                    .collect(),
            },
        );
    }
    Ok(signatures)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BlockOutcome {
    FallsThrough,
    AlwaysReturns,
    AlwaysBreaks,
    AlwaysContinues,
    AlwaysStops,
}

impl BlockOutcome {
    fn terminates(self) -> bool {
        self != BlockOutcome::FallsThrough
    }
}

#[derive(Debug, Clone)]
struct LoopContext {
    continue_target: usize,
    break_jumps: Vec<usize>,
}

struct Codegen {
    instructions: Vec<Instruction>,
    next_register: u16,
    return_type: ReturnType,
    unsafe_depth: usize,
    loop_stack: Vec<LoopContext>,
    locals: HashMap<String, Local>,
    source_consts: HashMap<String, i32>,
    function_signatures: HashMap<String, FunctionSignature>,
    current_function: String,
}

impl Codegen {
    fn compile(program: Program) -> Result<Image, CompileError> {
        let source_consts = evaluate_consts(&program.consts)?;
        let function_signatures = collect_function_signatures(&program.functions, &source_consts)?;
        let main = function_signatures
            .get("main")
            .cloned()
            .ok_or_else(|| CompileError {
                message: "missing `main` function".to_string(),
            })?;
        let main_function = &program.functions[main.index];
        if !main_function.parameters.is_empty() {
            return Err(CompileError {
                message: "`main` cannot have parameters".to_string(),
            });
        }

        let mut functions = Vec::with_capacity(program.functions.len());
        for function in &program.functions {
            functions.push(Self::compile_function(
                function,
                source_consts.clone(),
                function_signatures.clone(),
            )?);
        }

        Ok(Image {
            language_version: "ckm-seed-0".to_string(),
            memory_size: 64 * 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: main.index,
            functions,
        })
    }

    fn compile_function(
        function: &FunctionDecl,
        source_consts: HashMap<String, i32>,
        function_signatures: HashMap<String, FunctionSignature>,
    ) -> Result<Function, CompileError> {
        let mut codegen = Self {
            instructions: Vec::new(),
            next_register: 0,
            return_type: function.return_type,
            unsafe_depth: 0,
            loop_stack: Vec::new(),
            locals: HashMap::new(),
            source_consts,
            function_signatures,
            current_function: function.name.clone(),
        };
        let mut parameters = Vec::with_capacity(function.parameters.len());
        let mut parameter_names = HashSet::new();
        for parameter in &function.parameters {
            if !parameter_names.insert(parameter.name.clone()) {
                return Err(CompileError {
                    message: format!("duplicate parameter `{}`", parameter.name),
                });
            }
            let register = codegen.alloc_register()?;
            parameters.push(register);
            codegen.locals.insert(
                parameter.name.clone(),
                Local {
                    register,
                    ty: parameter.ty.into(),
                    mutable: false,
                },
            );
        }

        let outcome = codegen.compile_statements(&function.statements)?;
        if outcome == BlockOutcome::FallsThrough {
            match codegen.return_type {
                ReturnType::Unit => codegen.instructions.push(Instruction::ReturnUnit),
                ReturnType::I32 => {
                    return Err(CompileError {
                        message: "missing return in `i32` function".to_string(),
                    });
                }
                ReturnType::U32 => {
                    return Err(CompileError {
                        message: "missing return in `u32` function".to_string(),
                    });
                }
                ReturnType::Bool => {
                    return Err(CompileError {
                        message: "missing return in `bool` function".to_string(),
                    });
                }
            }
        }

        Ok(Function {
            name: function.name.clone(),
            register_count: usize::from(codegen.next_register),
            parameters,
            instructions: codegen.instructions,
        })
    }

    fn compile_statements(
        &mut self,
        statements: &[Statement],
    ) -> Result<BlockOutcome, CompileError> {
        let mut outcome = BlockOutcome::FallsThrough;
        for statement in statements {
            if outcome.terminates() {
                let message = if outcome == BlockOutcome::AlwaysReturns {
                    "unreachable statement after return"
                } else {
                    "unreachable statement after loop control"
                };
                return Err(CompileError {
                    message: message.to_string(),
                });
            }
            outcome = self.compile_statement(statement)?;
        }
        Ok(outcome)
    }

    fn compile_statement(&mut self, statement: &Statement) -> Result<BlockOutcome, CompileError> {
        match statement {
            Statement::Let {
                name,
                ty,
                initializer,
            } => {
                if resolve_builtin_constant(name).is_some() {
                    return Err(CompileError {
                        message: format!("local `{name}` cannot shadow built-in ABI constant"),
                    });
                }
                if self.locals.contains_key(name) {
                    return Err(CompileError {
                        message: format!("duplicate local `{name}`"),
                    });
                }
                let dst = self.alloc_register()?;
                let src = self.compile_expr_as(initializer, *ty)?;
                self.instructions.push(Instruction::I32Move { dst, src });
                self.locals.insert(
                    name.clone(),
                    Local {
                        register: dst,
                        ty: (*ty).into(),
                        mutable: true,
                    },
                );
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::Assign { name, value } => {
                let local = *self.locals.get(name).ok_or_else(|| CompileError {
                    message: format!("assignment to undeclared local `{name}`"),
                })?;
                if !local.mutable {
                    return Err(CompileError {
                        message: format!("assignment to immutable local `{name}`"),
                    });
                }
                let expected = match local.ty {
                    ValueType::I32 => TypeName::I32,
                    ValueType::U32 => TypeName::U32,
                    ValueType::Bool => TypeName::Bool,
                };
                let src = self.compile_expr_as(value, expected)?;
                self.instructions.push(Instruction::I32Move {
                    dst: local.register,
                    src,
                });
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::AssignOp { name, op, value } => {
                let local = *self.locals.get(name).ok_or_else(|| CompileError {
                    message: format!("assignment to undeclared local `{name}`"),
                })?;
                if !local.mutable {
                    return Err(CompileError {
                        message: format!("assignment to immutable local `{name}`"),
                    });
                }
                let expected = match local.ty {
                    ValueType::I32 => TypeName::I32,
                    ValueType::U32 => TypeName::U32,
                    ValueType::Bool => {
                        return Err(CompileError {
                            message: "compound assignment requires `i32` or `u32` local"
                                .to_string(),
                        });
                    }
                };
                let rhs = self.compile_expr_as(value, expected)?;
                match local.ty {
                    ValueType::I32 => {
                        self.emit_i32_binary_instruction(*op, local.register, local.register, rhs)
                    }
                    ValueType::U32 => {
                        self.emit_u32_binary_instruction(*op, local.register, local.register, rhs)
                    }
                    ValueType::Bool => unreachable!("bool locals are rejected above"),
                }
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::If {
                condition,
                then_branch,
                else_branch,
            } => {
                let cond = self.compile_bool_expr(condition)?;
                let false_jump = self.emit_jump_if_false_placeholder(cond);
                let then_outcome = self.compile_statements(then_branch)?;
                if let Some(else_branch) = else_branch {
                    let end_jump = self.emit_jump_placeholder();
                    let else_start = self.instructions.len();
                    self.patch_jump(false_jump, else_start)?;
                    let else_outcome = self.compile_statements(else_branch)?;
                    let end = self.instructions.len();
                    self.patch_jump(end_jump, end)?;
                    if then_outcome == BlockOutcome::AlwaysReturns
                        && else_outcome == BlockOutcome::AlwaysReturns
                    {
                        Ok(BlockOutcome::AlwaysReturns)
                    } else if then_outcome.terminates() && else_outcome.terminates() {
                        Ok(BlockOutcome::AlwaysStops)
                    } else {
                        Ok(BlockOutcome::FallsThrough)
                    }
                } else {
                    let end = self.instructions.len();
                    self.patch_jump(false_jump, end)?;
                    Ok(BlockOutcome::FallsThrough)
                }
            }
            Statement::While { condition, body } => {
                let loop_start = self.instructions.len();
                let cond = self.compile_bool_expr(condition)?;
                let exit_jump = self.emit_jump_if_false_placeholder(cond);
                self.loop_stack.push(LoopContext {
                    continue_target: loop_start,
                    break_jumps: Vec::new(),
                });
                let body_outcome = self.compile_statements(body)?;
                let context = self.loop_stack.pop().ok_or_else(|| CompileError {
                    message: "internal compiler error: missing loop context".to_string(),
                })?;
                if body_outcome == BlockOutcome::FallsThrough {
                    self.instructions
                        .push(Instruction::Jump { target: loop_start });
                }
                let loop_end = self.instructions.len();
                self.patch_jump(exit_jump, loop_end)?;
                for jump in context.break_jumps {
                    self.patch_jump(jump, loop_end)?;
                }
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::Break => {
                if self.loop_stack.is_empty() {
                    return Err(CompileError {
                        message: "`break` outside loop".to_string(),
                    });
                }
                let jump = self.emit_jump_placeholder();
                let context = self.loop_stack.last_mut().ok_or_else(|| CompileError {
                    message: "`break` outside loop".to_string(),
                })?;
                context.break_jumps.push(jump);
                Ok(BlockOutcome::AlwaysBreaks)
            }
            Statement::Continue => {
                let target = self
                    .loop_stack
                    .last()
                    .map(|context| context.continue_target)
                    .ok_or_else(|| CompileError {
                        message: "`continue` outside loop".to_string(),
                    })?;
                self.instructions.push(Instruction::Jump { target });
                Ok(BlockOutcome::AlwaysContinues)
            }
            Statement::Return(None) => match self.return_type {
                ReturnType::Unit => {
                    self.instructions.push(Instruction::ReturnUnit);
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::I32 => Err(CompileError {
                    message: "`i32` function cannot use `return;`".to_string(),
                }),
                ReturnType::U32 => Err(CompileError {
                    message: "`u32` function cannot use `return;`".to_string(),
                }),
                ReturnType::Bool => Err(CompileError {
                    message: "`bool` function cannot use `return;`".to_string(),
                }),
            },
            Statement::Return(Some(expr)) => match self.return_type {
                ReturnType::Unit => Err(CompileError {
                    message: "unit function cannot return a value".to_string(),
                }),
                ReturnType::I32 => {
                    let src = self.compile_i32_expr(expr)?;
                    self.instructions.push(Instruction::ReturnI32 { src });
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::U32 => {
                    let src = self.compile_u32_expr(expr)?;
                    self.instructions.push(Instruction::ReturnI32 { src });
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::Bool => {
                    let src = self.compile_bool_expr(expr)?;
                    self.instructions.push(Instruction::ReturnBool { src });
                    Ok(BlockOutcome::AlwaysReturns)
                }
            },
            Statement::Unsafe(statements) => {
                self.unsafe_depth += 1;
                let result = self.compile_statements(statements);
                self.unsafe_depth -= 1;
                result
            }
            Statement::Expr(expr) => {
                self.compile_expr(expr)?;
                Ok(BlockOutcome::FallsThrough)
            }
        }
    }

    fn compile_i32_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        self.compile_expr_as(expr, TypeName::I32)
    }

    fn compile_u32_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        self.compile_expr_as(expr, TypeName::U32)
    }

    fn compile_bool_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        self.compile_expr_as(expr, TypeName::Bool)
    }

    fn compile_expr_as(&mut self, expr: &Expr, expected: TypeName) -> Result<u16, CompileError> {
        if let Expr::Call { name, .. } = expr {
            if self
                .function_signatures
                .get(name)
                .is_some_and(|signature| signature.return_type == ReturnType::Unit)
            {
                return Err(CompileError {
                    message: format!("unit function `{name}` used as `{}` value", expected.name()),
                });
            }
        }
        if let Expr::Int(value) = expr {
            return match expected {
                TypeName::I32 => Ok(self.emit_i32_literal(*value)?),
                TypeName::U32 => Ok(self.emit_u32_literal(*value)?),
                TypeName::Bool => Err(CompileError {
                    message: "expected `bool`, found i32".to_string(),
                }),
            };
        }
        if let Expr::IntU32(value) = expr {
            return match expected {
                TypeName::U32 => Ok(self.emit_u32_literal(*value)?),
                TypeName::I32 => Err(CompileError {
                    message: "expected `i32`, found u32".to_string(),
                }),
                TypeName::Bool => Err(CompileError {
                    message: "expected `bool`, found u32".to_string(),
                }),
            };
        }
        if matches!(expr, Expr::Binary { .. }) && expected == TypeName::U32 {
            return self.compile_u32_binary_expr(expr);
        }
        match self.compile_expr(expr)? {
            ExprValue::I32(register) if expected == TypeName::I32 => Ok(register),
            ExprValue::U32(register) if expected == TypeName::U32 => Ok(register),
            ExprValue::Bool(register) if expected == TypeName::Bool => Ok(register),
            value => Err(CompileError {
                message: format!(
                    "expected `{}`, found {}",
                    expected.name(),
                    value.type_name()
                ),
            }),
        }
    }

    fn compile_addr_expr(
        &mut self,
        expr: &Expr,
        context: AddressContext,
    ) -> Result<u16, CompileError> {
        match expr {
            Expr::Binary {
                op: BinaryOp::Add,
                lhs,
                rhs,
            } => {
                let base = self.compile_addr_expr(lhs, context)?;
                let offset = self.compile_i32_expr(rhs)?;
                let dst = self.alloc_register()?;
                self.instructions
                    .push(Instruction::AddrAdd { dst, base, offset });
                Ok(dst)
            }
            Expr::Binary { .. } => Err(CompileError {
                message: "address arithmetic supports only `+`".to_string(),
            }),
            Expr::Int(value) | Expr::IntU32(value) => {
                let value = u32::try_from(*value).map_err(|_| CompileError {
                    message: format!("address literal `{value}` does not fit `u32`"),
                })?;
                let dst = self.alloc_register()?;
                self.instructions
                    .push(Instruction::AddrConst { dst, value });
                Ok(dst)
            }
            _ => match self.compile_expr(expr)? {
                ExprValue::Addr(register) => Ok(register),
                ExprValue::I32(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::U32(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::Bool(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::Pointer { .. } => Err(CompileError {
                    message: "address expression cannot be a pointer capability".to_string(),
                }),
                ExprValue::Unit => Err(CompileError {
                    message: format!("{} cannot be unit", context.address_name()),
                }),
            },
        }
    }

    fn compile_expr(&mut self, expr: &Expr) -> Result<ExprValue, CompileError> {
        match expr {
            Expr::Int(value) => Ok(ExprValue::I32(self.emit_i32_literal(*value)?)),
            Expr::IntU32(value) => Ok(ExprValue::U32(self.emit_u32_literal(*value)?)),
            Expr::Bool(value) => Ok(ExprValue::Bool(self.emit_bool_const(*value)?)),
            Expr::Local(name) => {
                if let Some(local) = self.locals.get(name) {
                    return match local.ty {
                        ValueType::I32 => Ok(ExprValue::I32(local.register)),
                        ValueType::U32 => Ok(ExprValue::U32(local.register)),
                        ValueType::Bool => Ok(ExprValue::Bool(local.register)),
                    };
                }

                if let Some(value) = self.source_consts.get(name).copied() {
                    return Ok(ExprValue::I32(self.emit_i32_const(value)?));
                }

                match resolve_builtin_constant(name) {
                    Some(BuiltinConstant::Addr(value)) => {
                        let dst = self.alloc_register()?;
                        self.instructions
                            .push(Instruction::AddrConst { dst, value });
                        Ok(ExprValue::Addr(dst))
                    }
                    Some(BuiltinConstant::I32(value)) => {
                        Ok(ExprValue::I32(self.emit_i32_const(value)?))
                    }
                    None => Err(CompileError {
                        message: format!("use of undeclared local `{name}`"),
                    }),
                }
            }
            Expr::Call { name, args } => self.compile_call(name, args),
            Expr::Mmio { ty, address } => {
                self.validate_memory_element_type(*ty)?;
                Ok(ExprValue::Pointer {
                    addr: self.compile_addr_expr(address, AddressContext::Mmio)?,
                    kind: PointerKind::Mmio,
                    element_type: *ty,
                })
            }
            Expr::Ptr { ty, address } => {
                self.validate_memory_element_type(*ty)?;
                Ok(ExprValue::Pointer {
                    addr: self.compile_addr_expr(address, AddressContext::Ptr)?,
                    kind: PointerKind::Ptr,
                    element_type: *ty,
                })
            }
            Expr::MethodCall {
                receiver,
                method,
                args,
            } => self.compile_method_call(receiver, method, args),
            Expr::Cast { expr, target } => self.compile_cast(expr, *target),
            Expr::Unary { op, expr } => match op {
                UnaryOp::Not => self.compile_bool_not(expr),
                UnaryOp::Neg => self.compile_i32_neg(expr),
            },
            Expr::Logical { op, lhs, rhs } => match op {
                LogicalOp::And => self.compile_logical_and(lhs, rhs),
                LogicalOp::Or => self.compile_logical_or(lhs, rhs),
            },
            Expr::Binary { op, lhs, rhs } => {
                let lhs = self.compile_i32_expr(lhs)?;
                let rhs = self.compile_i32_expr(rhs)?;
                let dst = self.alloc_register()?;
                self.emit_i32_binary_instruction(*op, dst, lhs, rhs);
                Ok(ExprValue::I32(dst))
            }
            Expr::Compare { op, lhs, rhs } => self.compile_compare_expr(*op, lhs, rhs),
        }
    }

    fn compile_u32_binary_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        let Expr::Binary { op, lhs, rhs } = expr else {
            return Err(CompileError {
                message: "internal compiler error: expected u32 binary expression".to_string(),
            });
        };
        let lhs = self.compile_u32_expr(lhs)?;
        let rhs = self.compile_u32_expr(rhs)?;
        let dst = self.alloc_register()?;
        self.emit_u32_binary_instruction(*op, dst, lhs, rhs);
        Ok(dst)
    }

    fn emit_i32_binary_instruction(&mut self, op: BinaryOp, dst: u16, lhs: u16, rhs: u16) {
        let instruction = match op {
            BinaryOp::Add => Instruction::I32Add { dst, lhs, rhs },
            BinaryOp::Sub => Instruction::I32Sub { dst, lhs, rhs },
            BinaryOp::Mul => Instruction::I32Mul { dst, lhs, rhs },
            BinaryOp::Div => Instruction::I32Div { dst, lhs, rhs },
            BinaryOp::BitAnd => Instruction::I32BitAnd { dst, lhs, rhs },
            BinaryOp::BitOr => Instruction::I32BitOr { dst, lhs, rhs },
            BinaryOp::BitXor => Instruction::I32BitXor { dst, lhs, rhs },
            BinaryOp::Shl => Instruction::I32Shl { dst, lhs, rhs },
            BinaryOp::Shr => Instruction::I32Shr { dst, lhs, rhs },
        };
        self.instructions.push(instruction);
    }

    fn emit_u32_binary_instruction(&mut self, op: BinaryOp, dst: u16, lhs: u16, rhs: u16) {
        let instruction = match op {
            BinaryOp::Shl => Instruction::U32Shl { dst, lhs, rhs },
            BinaryOp::Shr => Instruction::U32Shr { dst, lhs, rhs },
            BinaryOp::Add => Instruction::I32Add { dst, lhs, rhs },
            BinaryOp::Sub => Instruction::I32Sub { dst, lhs, rhs },
            BinaryOp::Mul => Instruction::I32Mul { dst, lhs, rhs },
            BinaryOp::Div => Instruction::I32Div { dst, lhs, rhs },
            BinaryOp::BitAnd => Instruction::I32BitAnd { dst, lhs, rhs },
            BinaryOp::BitOr => Instruction::I32BitOr { dst, lhs, rhs },
            BinaryOp::BitXor => Instruction::I32BitXor { dst, lhs, rhs },
        };
        self.instructions.push(instruction);
    }

    fn compile_cast(&mut self, expr: &Expr, target: TypeName) -> Result<ExprValue, CompileError> {
        match target {
            TypeName::I32 => match self.compile_expr(expr)? {
                ExprValue::I32(register) | ExprValue::U32(register) => Ok(ExprValue::I32(register)),
                ExprValue::Bool(_) => Err(CompileError {
                    message: "cannot cast bool to `i32`".to_string(),
                }),
                value => Err(CompileError {
                    message: format!("cannot cast {} to `i32`", value.type_name()),
                }),
            },
            TypeName::U32 => match self.compile_expr(expr)? {
                ExprValue::I32(register) | ExprValue::U32(register) => Ok(ExprValue::U32(register)),
                ExprValue::Bool(_) => Err(CompileError {
                    message: "cannot cast bool to `u32`".to_string(),
                }),
                value => Err(CompileError {
                    message: format!("cannot cast {} to `u32`", value.type_name()),
                }),
            },
            TypeName::Bool => Err(CompileError {
                message: "casts to `bool` are not supported".to_string(),
            }),
        }
    }

    fn compile_call(&mut self, name: &str, args: &[Expr]) -> Result<ExprValue, CompileError> {
        if name == self.current_function {
            return Err(CompileError {
                message: format!("recursive function call `{name}`"),
            });
        }
        let signature =
            self.function_signatures
                .get(name)
                .cloned()
                .ok_or_else(|| CompileError {
                    message: format!("unknown function `{name}`"),
                })?;
        if args.len() != signature.parameter_types.len() {
            return Err(CompileError {
                message: format!(
                    "function `{name}` expects {} arguments but got {}",
                    signature.parameter_types.len(),
                    args.len()
                ),
            });
        }
        let mut arguments = Vec::with_capacity(args.len());
        for (index, (arg, expected)) in args
            .iter()
            .zip(signature.parameter_types.iter().copied())
            .enumerate()
        {
            match self.compile_expr(arg)? {
                ExprValue::I32(register) if expected == TypeName::I32 => arguments.push(register),
                ExprValue::U32(register) if expected == TypeName::U32 => arguments.push(register),
                ExprValue::Bool(register) if expected == TypeName::Bool => arguments.push(register),
                value => {
                    return Err(CompileError {
                        message: format!(
                            "function `{name}` argument {index} expected `{}`, found {}",
                            expected.name(),
                            value.type_name()
                        ),
                    });
                }
            }
        }
        let return_register = match signature.return_type {
            ReturnType::Unit => None,
            ReturnType::I32 | ReturnType::U32 | ReturnType::Bool => Some(self.alloc_register()?),
        };
        self.instructions.push(Instruction::CallStatic {
            return_register,
            function_index: signature.index,
            arguments,
        });
        match return_register {
            Some(register) => match signature.return_type {
                ReturnType::I32 => Ok(ExprValue::I32(register)),
                ReturnType::U32 => Ok(ExprValue::U32(register)),
                ReturnType::Bool => Ok(ExprValue::Bool(register)),
                ReturnType::Unit => unreachable!("unit functions do not allocate return registers"),
            },
            None => Ok(ExprValue::Unit),
        }
    }

    fn compile_method_call(
        &mut self,
        receiver: &Expr,
        method: &str,
        args: &[Expr],
    ) -> Result<ExprValue, CompileError> {
        if self.unsafe_depth == 0 {
            let access_name = match receiver {
                Expr::Mmio { .. } => PointerKind::Mmio.access_name(),
                Expr::Ptr { .. } => PointerKind::Ptr.access_name(),
                _ => "raw memory",
            };
            return Err(CompileError {
                message: format!("{access_name} access requires `unsafe`"),
            });
        }
        let ExprValue::Pointer {
            addr,
            kind,
            element_type,
        } = self.compile_expr(receiver)?
        else {
            return Err(CompileError {
                message: "memory method receiver must be a pointer capability".to_string(),
            });
        };

        match method {
            "store" => {
                if args.len() != 1 {
                    return Err(CompileError {
                        message: format!(
                            "{}.store expects one argument",
                            kind.type_name(element_type)
                        ),
                    });
                }
                let src = self.compile_expr_as(&args[0], element_type)?;
                self.instructions.push(Instruction::Store32 { addr, src });
                Ok(ExprValue::Unit)
            }
            "load" => {
                if !args.is_empty() {
                    return Err(CompileError {
                        message: format!(
                            "{}.load expects no arguments",
                            kind.type_name(element_type)
                        ),
                    });
                }
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::Load32 { dst, addr });
                match element_type {
                    TypeName::I32 => Ok(ExprValue::I32(dst)),
                    TypeName::U32 => Ok(ExprValue::U32(dst)),
                    TypeName::Bool => unreachable!("bool memory element type is rejected earlier"),
                }
            }
            _ => Err(CompileError {
                message: format!("unknown MMIO method `{method}`"),
            }),
        }
    }

    fn compile_bool_not(&mut self, expr: &Expr) -> Result<ExprValue, CompileError> {
        let src = self.compile_bool_expr(expr)?;
        let false_register = self.emit_bool_const(false)?;
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Eq {
            dst,
            lhs: src,
            rhs: false_register,
        });
        Ok(ExprValue::Bool(dst))
    }

    fn compile_i32_neg(&mut self, expr: &Expr) -> Result<ExprValue, CompileError> {
        let src = match self.compile_expr(expr)? {
            ExprValue::I32(register) => register,
            ExprValue::U32(_) | ExprValue::Bool(_) => {
                return Err(CompileError {
                    message: "unary `-` requires `i32`".to_string(),
                });
            }
            value => {
                return Err(CompileError {
                    message: format!("unary `-` requires `i32`, found {}", value.type_name()),
                });
            }
        };
        let zero = self.emit_i32_const(0)?;
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Sub {
            dst,
            lhs: zero,
            rhs: src,
        });
        Ok(ExprValue::I32(dst))
    }

    fn compile_logical_and(&mut self, lhs: &Expr, rhs: &Expr) -> Result<ExprValue, CompileError> {
        let dst = self.emit_bool_const(false)?;
        let lhs = self.compile_bool_expr(lhs)?;
        let end_jump = self.emit_jump_if_false_placeholder(lhs);
        let rhs = self.compile_bool_expr(rhs)?;
        self.instructions
            .push(Instruction::I32Move { dst, src: rhs });
        let end = self.instructions.len();
        self.patch_jump(end_jump, end)?;
        Ok(ExprValue::Bool(dst))
    }

    fn compile_logical_or(&mut self, lhs: &Expr, rhs: &Expr) -> Result<ExprValue, CompileError> {
        let dst = self.emit_bool_const(true)?;
        let lhs = self.compile_bool_expr(lhs)?;
        let rhs_jump = self.emit_jump_if_false_placeholder(lhs);
        let end_jump = self.emit_jump_placeholder();
        let rhs_start = self.instructions.len();
        self.patch_jump(rhs_jump, rhs_start)?;
        let rhs = self.compile_bool_expr(rhs)?;
        self.instructions
            .push(Instruction::I32Move { dst, src: rhs });
        let end = self.instructions.len();
        self.patch_jump(end_jump, end)?;
        Ok(ExprValue::Bool(dst))
    }

    fn validate_memory_element_type(&self, ty: TypeName) -> Result<(), CompileError> {
        match ty {
            TypeName::I32 | TypeName::U32 => Ok(()),
            TypeName::Bool => Err(CompileError {
                message: "memory pointer element type must be `i32` or `u32`".to_string(),
            }),
        }
    }

    fn alloc_register(&mut self) -> Result<u16, CompileError> {
        let register = self.next_register;
        self.next_register = self
            .next_register
            .checked_add(1)
            .ok_or_else(|| CompileError {
                message: "too many registers in function".to_string(),
            })?;
        Ok(register)
    }

    fn emit_i32_literal(&mut self, value: i64) -> Result<u16, CompileError> {
        let value = i32::try_from(value).map_err(|_| CompileError {
            message: format!("integer literal `{value}` does not fit `i32`"),
        })?;
        self.emit_i32_const(value)
    }

    fn emit_u32_literal(&mut self, value: i64) -> Result<u16, CompileError> {
        let value = u32::try_from(value).map_err(|_| CompileError {
            message: format!("integer literal `{value}` does not fit `u32`"),
        })?;
        self.emit_i32_const(value as i32)
    }

    fn emit_i32_const(&mut self, value: i32) -> Result<u16, CompileError> {
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Const { dst, value });
        Ok(dst)
    }

    fn emit_bool_const(&mut self, value: bool) -> Result<u16, CompileError> {
        self.emit_i32_const(i32::from(value))
    }

    fn emit_jump_placeholder(&mut self) -> usize {
        let index = self.instructions.len();
        self.instructions
            .push(Instruction::Jump { target: usize::MAX });
        index
    }

    fn emit_jump_if_false_placeholder(&mut self, cond: u16) -> usize {
        let index = self.instructions.len();
        self.instructions.push(Instruction::JumpIfFalse {
            cond,
            target: usize::MAX,
        });
        index
    }

    fn patch_jump(&mut self, index: usize, target: usize) -> Result<(), CompileError> {
        match self.instructions.get_mut(index) {
            Some(Instruction::Jump { target: current }) => {
                *current = target;
                Ok(())
            }
            Some(Instruction::JumpIfFalse {
                target: current, ..
            }) => {
                *current = target;
                Ok(())
            }
            _ => Err(CompileError {
                message: format!("internal compiler error: instruction {index} is not a jump"),
            }),
        }
    }

    fn compile_compare_expr(
        &mut self,
        op: CompareOp,
        lhs: &Expr,
        rhs: &Expr,
    ) -> Result<ExprValue, CompileError> {
        let lhs = self.compile_expr(lhs)?;
        let rhs = self.compile_expr(rhs)?;
        match (lhs, rhs) {
            (ExprValue::I32(lhs), ExprValue::I32(rhs)) => self.compile_i32_compare(op, lhs, rhs),
            (ExprValue::U32(lhs), ExprValue::U32(rhs)) => self.compile_u32_compare(op, lhs, rhs),
            (ExprValue::Bool(lhs), ExprValue::Bool(rhs)) => match op {
                CompareOp::Eq | CompareOp::Ne => self.compile_equality(op, lhs, rhs),
                CompareOp::Lt | CompareOp::Le | CompareOp::Gt | CompareOp::Ge => {
                    Err(CompileError {
                        message: "ordering comparison requires `i32` or `u32`".to_string(),
                    })
                }
            },
            (lhs, rhs) => Err(CompileError {
                message: format!(
                    "comparison operands must have the same type, found {} and {}",
                    lhs.type_name(),
                    rhs.type_name()
                ),
            }),
        }
    }

    fn compile_i32_compare(
        &mut self,
        op: CompareOp,
        lhs: u16,
        rhs: u16,
    ) -> Result<ExprValue, CompileError> {
        match op {
            CompareOp::Lt => {
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::I32Lt { dst, lhs, rhs });
                Ok(ExprValue::Bool(dst))
            }
            CompareOp::Eq | CompareOp::Ne => self.compile_equality(op, lhs, rhs),
            CompareOp::Gt => self.compile_i32_compare(CompareOp::Lt, rhs, lhs),
            CompareOp::Le => self.compile_i32_not_less_than(rhs, lhs),
            CompareOp::Ge => self.compile_i32_not_less_than(lhs, rhs),
        }
    }

    fn compile_u32_compare(
        &mut self,
        op: CompareOp,
        lhs: u16,
        rhs: u16,
    ) -> Result<ExprValue, CompileError> {
        match op {
            CompareOp::Lt => {
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::U32Lt { dst, lhs, rhs });
                Ok(ExprValue::Bool(dst))
            }
            CompareOp::Eq | CompareOp::Ne => self.compile_equality(op, lhs, rhs),
            CompareOp::Gt => self.compile_u32_compare(CompareOp::Lt, rhs, lhs),
            CompareOp::Le => self.compile_u32_not_less_than(rhs, lhs),
            CompareOp::Ge => self.compile_u32_not_less_than(lhs, rhs),
        }
    }

    fn compile_equality(
        &mut self,
        op: CompareOp,
        lhs: u16,
        rhs: u16,
    ) -> Result<ExprValue, CompileError> {
        let eq = self.alloc_register()?;
        self.instructions
            .push(Instruction::I32Eq { dst: eq, lhs, rhs });
        if op == CompareOp::Eq {
            return Ok(ExprValue::Bool(eq));
        }

        let zero = self.emit_i32_const(0)?;
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Eq {
            dst,
            lhs: eq,
            rhs: zero,
        });
        Ok(ExprValue::Bool(dst))
    }

    fn compile_i32_not_less_than(&mut self, lhs: u16, rhs: u16) -> Result<ExprValue, CompileError> {
        let lt = self.alloc_register()?;
        self.instructions
            .push(Instruction::I32Lt { dst: lt, lhs, rhs });
        self.compile_bool_is_false(lt)
    }

    fn compile_u32_not_less_than(&mut self, lhs: u16, rhs: u16) -> Result<ExprValue, CompileError> {
        let lt = self.alloc_register()?;
        self.instructions
            .push(Instruction::U32Lt { dst: lt, lhs, rhs });
        self.compile_bool_is_false(lt)
    }

    fn compile_bool_is_false(&mut self, src: u16) -> Result<ExprValue, CompileError> {
        let zero = self.emit_i32_const(0)?;
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Eq {
            dst,
            lhs: src,
            rhs: zero,
        });
        Ok(ExprValue::Bool(dst))
    }
}
