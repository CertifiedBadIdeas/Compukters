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
    Bool(u16),
    Addr(u16),
    Pointer { addr: u16, kind: PointerKind },
    Unit,
}

impl ExprValue {
    fn type_name(self) -> &'static str {
        match self {
            ExprValue::I32(_) => "i32",
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

    fn type_name(self) -> &'static str {
        match self {
            PointerKind::Mmio => "mmio<i32>",
            PointerKind::Ptr => "ptr<i32>",
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
                BinaryOp::Shl => Some(lhs.wrapping_shl(rhs as u32)),
                BinaryOp::Shr => Some(lhs.wrapping_shr(rhs as u32)),
            }
            .ok_or_else(|| CompileError {
                message: "const initializer arithmetic overflow".to_string(),
            })
        }
        Expr::Compare { .. }
        | Expr::Bool(_)
        | Expr::Call { .. }
        | Expr::Mmio(_)
        | Expr::Ptr(_)
        | Expr::MethodCall { .. } => Err(CompileError {
            message: "const initializer is not compile-time evaluable".to_string(),
        }),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ValueType {
    I32,
    Bool,
}

impl From<TypeName> for ValueType {
    fn from(value: TypeName) -> Self {
        match value {
            TypeName::I32 => ValueType::I32,
            TypeName::Bool => ValueType::Bool,
        }
    }
}

impl TypeName {
    fn name(self) -> &'static str {
        match self {
            TypeName::I32 => "i32",
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
}

struct Codegen {
    instructions: Vec<Instruction>,
    next_register: u16,
    return_type: ReturnType,
    unsafe_depth: usize,
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
            if outcome == BlockOutcome::AlwaysReturns {
                return Err(CompileError {
                    message: "unreachable statement after return".to_string(),
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
                    ValueType::Bool => TypeName::Bool,
                };
                let src = self.compile_expr_as(value, expected)?;
                self.instructions.push(Instruction::I32Move {
                    dst: local.register,
                    src,
                });
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
                self.compile_statements(body)?;
                self.instructions
                    .push(Instruction::Jump { target: loop_start });
                let loop_end = self.instructions.len();
                self.patch_jump(exit_jump, loop_end)?;
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::Return(None) => match self.return_type {
                ReturnType::Unit => {
                    self.instructions.push(Instruction::ReturnUnit);
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::I32 => Err(CompileError {
                    message: "`i32` function cannot use `return;`".to_string(),
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
        match self.compile_expr(expr)? {
            ExprValue::I32(register) if expected == TypeName::I32 => Ok(register),
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
            Expr::Int(value) => {
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
            Expr::Int(value) => {
                let value = i32::try_from(*value).map_err(|_| CompileError {
                    message: format!("integer literal `{value}` does not fit `i32`"),
                })?;
                Ok(ExprValue::I32(self.emit_i32_const(value)?))
            }
            Expr::Bool(value) => Ok(ExprValue::Bool(self.emit_bool_const(*value)?)),
            Expr::Local(name) => {
                if let Some(local) = self.locals.get(name) {
                    return match local.ty {
                        ValueType::I32 => Ok(ExprValue::I32(local.register)),
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
            Expr::Mmio(address) => Ok(ExprValue::Pointer {
                addr: self.compile_addr_expr(address, AddressContext::Mmio)?,
                kind: PointerKind::Mmio,
            }),
            Expr::Ptr(address) => Ok(ExprValue::Pointer {
                addr: self.compile_addr_expr(address, AddressContext::Ptr)?,
                kind: PointerKind::Ptr,
            }),
            Expr::MethodCall {
                receiver,
                method,
                args,
            } => self.compile_method_call(receiver, method, args),
            Expr::Binary { op, lhs, rhs } => {
                let lhs = self.compile_i32_expr(lhs)?;
                let rhs = self.compile_i32_expr(rhs)?;
                let dst = self.alloc_register()?;
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
                Ok(ExprValue::I32(dst))
            }
            Expr::Compare { op, lhs, rhs } => {
                let lhs = self.compile_i32_expr(lhs)?;
                let rhs = self.compile_i32_expr(rhs)?;
                self.compile_compare(*op, lhs, rhs)
            }
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
            ReturnType::I32 | ReturnType::Bool => Some(self.alloc_register()?),
        };
        self.instructions.push(Instruction::CallStatic {
            return_register,
            function_index: signature.index,
            arguments,
        });
        match return_register {
            Some(register) => match signature.return_type {
                ReturnType::I32 => Ok(ExprValue::I32(register)),
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
                Expr::Mmio(_) => PointerKind::Mmio.access_name(),
                Expr::Ptr(_) => PointerKind::Ptr.access_name(),
                _ => "raw memory",
            };
            return Err(CompileError {
                message: format!("{access_name} access requires `unsafe`"),
            });
        }
        let ExprValue::Pointer { addr, kind } = self.compile_expr(receiver)? else {
            return Err(CompileError {
                message: "memory method receiver must be a pointer capability".to_string(),
            });
        };

        match method {
            "store" => {
                if args.len() != 1 {
                    return Err(CompileError {
                        message: format!("{}.store expects one argument", kind.type_name()),
                    });
                }
                let src = self.compile_i32_expr(&args[0])?;
                self.instructions.push(Instruction::Store32 { addr, src });
                Ok(ExprValue::Unit)
            }
            "load" => {
                if !args.is_empty() {
                    return Err(CompileError {
                        message: format!("{}.load expects no arguments", kind.type_name()),
                    });
                }
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::Load32 { dst, addr });
                Ok(ExprValue::I32(dst))
            }
            _ => Err(CompileError {
                message: format!("unknown MMIO method `{method}`"),
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

    fn compile_compare(
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
            CompareOp::Eq => {
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::I32Eq { dst, lhs, rhs });
                Ok(ExprValue::Bool(dst))
            }
            CompareOp::Ne => {
                let eq = self.alloc_register()?;
                self.instructions
                    .push(Instruction::I32Eq { dst: eq, lhs, rhs });
                let zero = self.emit_i32_const(0)?;
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::I32Eq {
                    dst,
                    lhs: eq,
                    rhs: zero,
                });
                Ok(ExprValue::Bool(dst))
            }
            CompareOp::Gt => self.compile_compare(CompareOp::Lt, rhs, lhs),
            CompareOp::Le => self.compile_not_less_than(rhs, lhs),
            CompareOp::Ge => self.compile_not_less_than(lhs, rhs),
        }
    }

    fn compile_not_less_than(&mut self, lhs: u16, rhs: u16) -> Result<ExprValue, CompileError> {
        let lt = self.alloc_register()?;
        self.instructions
            .push(Instruction::I32Lt { dst: lt, lhs, rhs });
        let zero = self.emit_i32_const(0)?;
        let dst = self.alloc_register()?;
        self.instructions.push(Instruction::I32Eq {
            dst,
            lhs: lt,
            rhs: zero,
        });
        Ok(ExprValue::Bool(dst))
    }
}
