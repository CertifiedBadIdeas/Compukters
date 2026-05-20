use crate::frontend::ast::*;
use crate::frontend::error::CompileError;
use rux_vm::computer_abi;
use rux_vm::low_image::{Function, Image, Instruction};
use std::collections::{HashMap, HashSet};

pub(crate) fn compile(program: Program) -> Result<Image, CompileError> {
    Codegen::compile(program)
}

const DEFAULT_MEMORY_SIZE: u32 = 64 * 1024;

#[derive(Clone, Copy)]
enum ExprValue {
    I32(u16),
    U32(u16),
    U8(u16),
    Bool(u16),
    Addr(u16),
    RefMut {
        addr: u16,
        element_type: TypeName,
    },
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
            ExprValue::U8(_) => "u8",
            ExprValue::Bool(_) => "bool",
            ExprValue::Addr(_) => "address",
            ExprValue::RefMut { element_type, .. } => element_type.ref_mut_name(),
            ExprValue::Pointer { element_type, .. } => element_type.pointer_name(),
            ExprValue::Unit => "unit",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum PointerKind {
    Mmio,
    Ptr,
    Rodata,
    Stack,
}

impl PointerKind {
    fn access_name(self) -> &'static str {
        match self {
            PointerKind::Mmio => "MMIO",
            PointerKind::Ptr => "pointer",
            PointerKind::Rodata => "byte string",
            PointerKind::Stack => "stack buffer",
        }
    }

    fn requires_unsafe(self) -> bool {
        matches!(self, PointerKind::Mmio | PointerKind::Ptr)
    }

    fn type_name(self, element_type: TypeName) -> String {
        match self {
            PointerKind::Mmio => format!("mmio<{}>", element_type.name()),
            PointerKind::Ptr => format!("ptr<{}>", element_type.name()),
            PointerKind::Rodata => format!("rodata<{}>", element_type.name()),
            PointerKind::Stack => format!("stack<{}>", element_type.name()),
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
        "SERIAL_INPUT_BASE" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_BASE)),
        "SERIAL_INPUT_READY" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_READY)),
        "SERIAL_INPUT_READ" => Some(BuiltinConstant::Addr(computer_abi::SERIAL_INPUT_READ)),
        "SERIAL_INPUT_SIZE" => Some(BuiltinConstant::I32(computer_abi::SERIAL_INPUT_SIZE as i32)),
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
                BinaryOp::Rem => {
                    if rhs == 0 {
                        None
                    } else {
                        lhs.checked_rem(rhs)
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
        | Expr::ByteString(_)
        | Expr::Index { .. }
        | Expr::AddressOfMut(_)
        | Expr::Deref(_)
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

fn align_up(value: u32, alignment: u32) -> Result<u32, CompileError> {
    debug_assert!(alignment.is_power_of_two());
    let mask = alignment - 1;
    value
        .checked_add(mask)
        .map(|value| value & !mask)
        .ok_or_else(|| CompileError {
            message: "stack frame is too large".to_string(),
        })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ValueType {
    I32,
    U32,
    U8,
    Bool,
    PtrI32,
    PtrU32,
    PtrU8,
    RefMutI32,
    RefMutU32,
    RefMutU8,
    ArrayU8(u32),
}

impl From<TypeName> for ValueType {
    fn from(value: TypeName) -> Self {
        match value {
            TypeName::I32 => ValueType::I32,
            TypeName::U32 => ValueType::U32,
            TypeName::U8 => ValueType::U8,
            TypeName::Bool => ValueType::Bool,
            TypeName::PtrI32 => ValueType::PtrI32,
            TypeName::PtrU32 => ValueType::PtrU32,
            TypeName::PtrU8 => ValueType::PtrU8,
            TypeName::RefMutI32 => ValueType::RefMutI32,
            TypeName::RefMutU32 => ValueType::RefMutU32,
            TypeName::RefMutU8 => ValueType::RefMutU8,
            TypeName::ArrayU8(len) => ValueType::ArrayU8(len),
        }
    }
}

impl TypeName {
    fn name(self) -> &'static str {
        match self {
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
            TypeName::ArrayU8(_) => "[u8; N]",
        }
    }

    fn ref_mut_name(self) -> &'static str {
        match self {
            TypeName::I32 => "&mut i32",
            TypeName::U32 => "&mut u32",
            TypeName::U8 => "&mut u8",
            _ => self.name(),
        }
    }

    fn pointer_name(self) -> &'static str {
        match self {
            TypeName::I32 => "ptr<i32>",
            TypeName::U32 => "ptr<u32>",
            TypeName::U8 => "ptr<u8>",
            TypeName::Bool => "ptr<bool>",
            TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => self.name(),
        }
    }

    fn pointer_type_for(element_type: TypeName) -> Option<TypeName> {
        match element_type {
            TypeName::I32 => Some(TypeName::PtrI32),
            TypeName::U32 => Some(TypeName::PtrU32),
            TypeName::U8 => Some(TypeName::PtrU8),
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => None,
        }
    }

    fn ref_mut_type_for(element_type: TypeName) -> Option<TypeName> {
        match element_type {
            TypeName::I32 => Some(TypeName::RefMutI32),
            TypeName::U32 => Some(TypeName::RefMutU32),
            TypeName::U8 => Some(TypeName::RefMutU8),
            _ => None,
        }
    }

    fn stack_size(self) -> Option<u32> {
        match self {
            TypeName::I32 | TypeName::U32 => Some(4),
            TypeName::U8 => Some(1),
            TypeName::ArrayU8(len) => Some(len),
            _ => None,
        }
    }

    fn stack_alignment(self) -> Option<u32> {
        match self {
            TypeName::I32 | TypeName::U32 => Some(4),
            TypeName::U8 | TypeName::ArrayU8(_) => Some(1),
            _ => None,
        }
    }
}

impl ValueType {
    fn type_name(self) -> TypeName {
        match self {
            ValueType::I32 => TypeName::I32,
            ValueType::U32 => TypeName::U32,
            ValueType::U8 => TypeName::U8,
            ValueType::Bool => TypeName::Bool,
            ValueType::PtrI32 => TypeName::PtrI32,
            ValueType::PtrU32 => TypeName::PtrU32,
            ValueType::PtrU8 => TypeName::PtrU8,
            ValueType::RefMutI32 => TypeName::RefMutI32,
            ValueType::RefMutU32 => TypeName::RefMutU32,
            ValueType::RefMutU8 => TypeName::RefMutU8,
            ValueType::ArrayU8(len) => TypeName::ArrayU8(len),
        }
    }
}

impl ReturnType {
    fn name(self) -> &'static str {
        match self {
            ReturnType::Unit => "unit",
            ReturnType::I32 => "i32",
            ReturnType::U32 => "u32",
            ReturnType::U8 => "u8",
            ReturnType::Bool => "bool",
            ReturnType::PtrI32 => "ptr<i32>",
            ReturnType::PtrU32 => "ptr<u32>",
            ReturnType::PtrU8 => "ptr<u8>",
        }
    }

    fn value_type(self) -> Option<TypeName> {
        match self {
            ReturnType::Unit => None,
            ReturnType::I32 => Some(TypeName::I32),
            ReturnType::U32 => Some(TypeName::U32),
            ReturnType::U8 => Some(TypeName::U8),
            ReturnType::Bool => Some(TypeName::Bool),
            ReturnType::PtrI32 => Some(TypeName::PtrI32),
            ReturnType::PtrU32 => Some(TypeName::PtrU32),
            ReturnType::PtrU8 => Some(TypeName::PtrU8),
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct Local {
    register: u16,
    ty: ValueType,
    mutable: bool,
    stack_addr: Option<u16>,
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

struct Codegen<'rodata> {
    instructions: Vec<Instruction>,
    rodata: &'rodata mut Vec<u8>,
    next_register: u16,
    return_type: ReturnType,
    unsafe_depth: usize,
    loop_stack: Vec<LoopContext>,
    locals: HashMap<String, Local>,
    next_stack_offset: u32,
    source_consts: HashMap<String, i32>,
    function_signatures: HashMap<String, FunctionSignature>,
    current_function: String,
}

impl<'rodata> Codegen<'rodata> {
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

        let mut rodata = Vec::new();
        let mut functions = Vec::with_capacity(program.functions.len());
        let mut next_stack_offset = 0;
        for function in &program.functions {
            functions.push(Self::compile_function(
                function,
                &mut rodata,
                &mut next_stack_offset,
                source_consts.clone(),
                function_signatures.clone(),
            )?);
        }

        Ok(Image {
            memory_size: DEFAULT_MEMORY_SIZE,
            rodata,
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: main.index,
            functions,
        })
    }

    fn compile_function<'a>(
        function: &FunctionDecl,
        rodata: &'a mut Vec<u8>,
        next_stack_offset: &mut u32,
        source_consts: HashMap<String, i32>,
        function_signatures: HashMap<String, FunctionSignature>,
    ) -> Result<Function, CompileError> {
        let mut codegen = Codegen {
            instructions: Vec::new(),
            rodata,
            next_register: 0,
            return_type: function.return_type,
            unsafe_depth: 0,
            loop_stack: Vec::new(),
            locals: HashMap::new(),
            next_stack_offset: *next_stack_offset,
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
                    stack_addr: None,
                },
            );
        }

        let outcome = codegen.compile_statements(&function.statements)?;
        if outcome == BlockOutcome::FallsThrough {
            if codegen.return_type == ReturnType::Unit {
                codegen.instructions.push(Instruction::ReturnUnit);
            } else {
                return Err(CompileError {
                    message: format!(
                        "missing return in `{}` function",
                        codegen.return_type.name()
                    ),
                });
            }
        }

        *next_stack_offset = codegen.next_stack_offset;
        Ok(Function {
            name: function.name.clone(),
            register_count: codegen.next_register,
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
                let stack_addr = if matches!(ty, TypeName::ArrayU8(_)) {
                    let addr = self.alloc_stack_slot(*ty)?;
                    if let Some(initializer) = initializer {
                        self.initialize_stack_array(*ty, addr, initializer)?;
                    }
                    self.instructions
                        .push(Instruction::I32Move { dst, src: addr });
                    None
                } else {
                    let initializer = initializer.as_ref().ok_or_else(|| CompileError {
                        message: format!("local `{name}` requires an initializer"),
                    })?;
                    let src = self.compile_expr_as(initializer, *ty)?;
                    self.instructions.push(Instruction::I32Move { dst, src });
                    None
                };
                self.locals.insert(
                    name.clone(),
                    Local {
                        register: dst,
                        ty: (*ty).into(),
                        mutable: true,
                        stack_addr,
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
                let expected = local.ty.type_name();
                let src = self.compile_expr_as(value, expected)?;
                if let Some(addr) = local.stack_addr {
                    self.emit_store(expected, addr, src);
                } else {
                    self.instructions.push(Instruction::I32Move {
                        dst: local.register,
                        src,
                    });
                }
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
                    ValueType::U8
                    | ValueType::Bool
                    | ValueType::PtrI32
                    | ValueType::PtrU32
                    | ValueType::PtrU8
                    | ValueType::RefMutI32
                    | ValueType::RefMutU32
                    | ValueType::RefMutU8
                    | ValueType::ArrayU8(_) => {
                        return Err(CompileError {
                            message: "compound assignment requires `i32` or `u32` local"
                                .to_string(),
                        });
                    }
                };
                let rhs = self.compile_expr_as(value, expected)?;
                let lhs = if let Some(addr) = local.stack_addr {
                    let lhs = self.alloc_register()?;
                    self.emit_load(expected, lhs, addr);
                    lhs
                } else {
                    local.register
                };
                let dst = if local.stack_addr.is_some() {
                    self.alloc_register()?
                } else {
                    local.register
                };
                match local.ty {
                    ValueType::I32 => self.emit_i32_binary_instruction(*op, dst, lhs, rhs),
                    ValueType::U32 => self.emit_u32_binary_instruction(*op, dst, lhs, rhs),
                    ValueType::U8
                    | ValueType::Bool
                    | ValueType::PtrI32
                    | ValueType::PtrU32
                    | ValueType::PtrU8
                    | ValueType::RefMutI32
                    | ValueType::RefMutU32
                    | ValueType::RefMutU8
                    | ValueType::ArrayU8(_) => {
                        unreachable!("non-arithmetic locals are rejected above")
                    }
                }
                if let Some(addr) = local.stack_addr {
                    self.emit_store(expected, addr, dst);
                }
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::IndexAssign {
                target,
                index,
                value,
            } => {
                let (addr, kind, element_type) = self.compile_index_address(target, index)?;
                if self.unsafe_depth == 0 && kind.requires_unsafe() {
                    return Err(CompileError {
                        message: format!("{} access requires `unsafe`", kind.access_name()),
                    });
                }
                let src = self.compile_expr_as(value, element_type)?;
                self.emit_store(element_type, addr, src);
                Ok(BlockOutcome::FallsThrough)
            }
            Statement::DerefAssign { target, value } => {
                let (addr, element_type) = self.compile_ref_mut_expr(target)?;
                let src = self.compile_expr_as(value, element_type)?;
                self.emit_store(element_type, addr, src);
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
                return_type => Err(CompileError {
                    message: format!("`{}` function cannot use `return;`", return_type.name()),
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
                ReturnType::U8 => {
                    let src = self.compile_u8_expr(expr)?;
                    self.instructions.push(Instruction::ReturnI32 { src });
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::Bool => {
                    let src = self.compile_bool_expr(expr)?;
                    self.instructions.push(Instruction::ReturnBool { src });
                    Ok(BlockOutcome::AlwaysReturns)
                }
                ReturnType::PtrI32 | ReturnType::PtrU32 | ReturnType::PtrU8 => {
                    let expected = self.return_type.value_type().ok_or_else(|| CompileError {
                        message: "internal compiler error: pointer return type missing value type"
                            .to_string(),
                    })?;
                    let src = self.compile_expr_as(expr, expected)?;
                    self.instructions.push(Instruction::ReturnAddr { src });
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

    fn compile_u8_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        self.compile_expr_as(expr, TypeName::U8)
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
                TypeName::U8 => Ok(self.emit_u8_literal(*value)?),
                TypeName::Bool => Err(CompileError {
                    message: "expected `bool`, found i32".to_string(),
                }),
                TypeName::PtrI32
                | TypeName::PtrU32
                | TypeName::PtrU8
                | TypeName::RefMutI32
                | TypeName::RefMutU32
                | TypeName::RefMutU8
                | TypeName::ArrayU8(_) => Err(CompileError {
                    message: format!("expected `{}`, found i32", expected.name()),
                }),
            };
        }
        if let Expr::IntU32(value) = expr {
            return match expected {
                TypeName::U32 => Ok(self.emit_u32_literal(*value)?),
                TypeName::I32 => Err(CompileError {
                    message: "expected `i32`, found u32".to_string(),
                }),
                TypeName::U8 => Err(CompileError {
                    message: "expected `u8`, found u32".to_string(),
                }),
                TypeName::Bool => Err(CompileError {
                    message: "expected `bool`, found u32".to_string(),
                }),
                TypeName::PtrI32
                | TypeName::PtrU32
                | TypeName::PtrU8
                | TypeName::RefMutI32
                | TypeName::RefMutU32
                | TypeName::RefMutU8
                | TypeName::ArrayU8(_) => Err(CompileError {
                    message: format!("expected `{}`, found u32", expected.name()),
                }),
            };
        }
        if let Expr::IntU8(value) = expr {
            return match expected {
                TypeName::U8 => Ok(self.emit_u8_literal(*value)?),
                TypeName::I32 => Err(CompileError {
                    message: "expected `i32`, found u8".to_string(),
                }),
                TypeName::U32 => Err(CompileError {
                    message: "expected `u32`, found u8".to_string(),
                }),
                TypeName::Bool => Err(CompileError {
                    message: "expected `bool`, found u8".to_string(),
                }),
                TypeName::PtrI32
                | TypeName::PtrU32
                | TypeName::PtrU8
                | TypeName::RefMutI32
                | TypeName::RefMutU32
                | TypeName::RefMutU8
                | TypeName::ArrayU8(_) => Err(CompileError {
                    message: format!("expected `{}`, found u8", expected.name()),
                }),
            };
        }
        if matches!(expr, Expr::Binary { .. }) && expected == TypeName::U32 {
            return self.compile_u32_binary_expr(expr);
        }
        match self.compile_expr(expr)? {
            ExprValue::I32(register) if expected == TypeName::I32 => Ok(register),
            ExprValue::U32(register) if expected == TypeName::U32 => Ok(register),
            ExprValue::U8(register) if expected == TypeName::U8 => Ok(register),
            ExprValue::Bool(register) if expected == TypeName::Bool => Ok(register),
            ExprValue::Pointer {
                addr, element_type, ..
            } if TypeName::pointer_type_for(element_type) == Some(expected) => Ok(addr),
            ExprValue::RefMut { addr, element_type }
                if TypeName::ref_mut_type_for(element_type) == Some(expected) =>
            {
                Ok(addr)
            }
            value => Err(CompileError {
                message: format!(
                    "expected `{}`, found {}",
                    expected.name(),
                    value.type_name()
                ),
            }),
        }
    }

    fn initialize_stack_array(
        &mut self,
        ty: TypeName,
        base_addr: u16,
        initializer: &Expr,
    ) -> Result<(), CompileError> {
        let TypeName::ArrayU8(len) = ty else {
            return Err(CompileError {
                message: format!("type `{}` is not an array", ty.name()),
            });
        };
        let Expr::ByteString(bytes) = initializer else {
            return Err(CompileError {
                message: "array initializer must be a byte string".to_string(),
            });
        };
        if bytes.len() != len as usize {
            return Err(CompileError {
                message: format!(
                    "byte string length {} does not match array length {len}",
                    bytes.len()
                ),
            });
        }
        for (index, byte) in bytes.iter().copied().enumerate() {
            let addr = if index == 0 {
                base_addr
            } else {
                let offset = self.emit_u32_literal(index as i64)?;
                let addr = self.alloc_register()?;
                self.instructions.push(Instruction::AddrAdd {
                    dst: addr,
                    base: base_addr,
                    offset,
                });
                addr
            };
            let src = self.emit_u8_literal(i64::from(byte))?;
            self.instructions.push(Instruction::Store8 { addr, src });
        }
        Ok(())
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
            Expr::Int(value) | Expr::IntU32(value) | Expr::IntU8(value) => {
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
                ExprValue::U32(register) => Ok(register),
                ExprValue::I32(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::U8(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::Bool(_) => Err(CompileError {
                    message: format!("{} must be an address expression", context.address_name()),
                }),
                ExprValue::Pointer { .. } => Err(CompileError {
                    message: "address expression cannot be a pointer capability".to_string(),
                }),
                ExprValue::RefMut { .. } => Err(CompileError {
                    message: "address expression cannot be a reference".to_string(),
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
            Expr::IntU8(value) => Ok(ExprValue::U8(self.emit_u8_literal(*value)?)),
            Expr::ByteString(bytes) => {
                let offset = u32::try_from(self.rodata.len()).map_err(|_| CompileError {
                    message: "rodata address does not fit `u32`".to_string(),
                })?;
                let addr = computer_abi::PROFILE_V2_PROGRAM_BASE
                    .checked_add(offset)
                    .ok_or_else(|| CompileError {
                        message: "rodata address does not fit `u32`".to_string(),
                    })?;
                self.rodata.extend_from_slice(bytes);
                let register = self.alloc_register()?;
                self.instructions.push(Instruction::AddrConst {
                    dst: register,
                    value: addr,
                });
                Ok(ExprValue::Pointer {
                    addr: register,
                    kind: PointerKind::Rodata,
                    element_type: TypeName::U8,
                })
            }
            Expr::Bool(value) => Ok(ExprValue::Bool(self.emit_bool_const(*value)?)),
            Expr::Local(name) => {
                if let Some(local) = self.locals.get(name).copied() {
                    if let Some(addr) = local.stack_addr {
                        let dst = self.alloc_register()?;
                        let ty = local.ty.type_name();
                        self.emit_load(ty, dst, addr);
                        return match ty {
                            TypeName::I32 => Ok(ExprValue::I32(dst)),
                            TypeName::U32 => Ok(ExprValue::U32(dst)),
                            TypeName::U8 => Ok(ExprValue::U8(dst)),
                            _ => {
                                Err(CompileError {
                                    message: format!(
                                        "internal compiler error: stack-backed local `{name}` has unsupported type"
                                    ),
                                })
                            }
                        };
                    }
                    return match local.ty {
                        ValueType::I32 => Ok(ExprValue::I32(local.register)),
                        ValueType::U32 => Ok(ExprValue::U32(local.register)),
                        ValueType::U8 => Ok(ExprValue::U8(local.register)),
                        ValueType::Bool => Ok(ExprValue::Bool(local.register)),
                        ValueType::PtrI32 => Ok(ExprValue::Pointer {
                            addr: local.register,
                            kind: PointerKind::Ptr,
                            element_type: TypeName::I32,
                        }),
                        ValueType::PtrU32 => Ok(ExprValue::Pointer {
                            addr: local.register,
                            kind: PointerKind::Ptr,
                            element_type: TypeName::U32,
                        }),
                        ValueType::PtrU8 => Ok(ExprValue::Pointer {
                            addr: local.register,
                            kind: PointerKind::Ptr,
                            element_type: TypeName::U8,
                        }),
                        ValueType::RefMutI32 => Ok(ExprValue::RefMut {
                            addr: local.register,
                            element_type: TypeName::I32,
                        }),
                        ValueType::RefMutU32 => Ok(ExprValue::RefMut {
                            addr: local.register,
                            element_type: TypeName::U32,
                        }),
                        ValueType::RefMutU8 => Ok(ExprValue::RefMut {
                            addr: local.register,
                            element_type: TypeName::U8,
                        }),
                        ValueType::ArrayU8(_) => Ok(ExprValue::Pointer {
                            addr: local.register,
                            kind: PointerKind::Stack,
                            element_type: TypeName::U8,
                        }),
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
            Expr::Index { target, index } => self.compile_index(target, index),
            Expr::AddressOfMut(target) => self.compile_address_of_mut(target),
            Expr::Deref(target) => self.compile_deref(target),
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

    fn compile_address_of_mut(&mut self, target: &Expr) -> Result<ExprValue, CompileError> {
        let Expr::Local(name) = target else {
            return Err(CompileError {
                message: "`&mut` can only borrow local variables for now".to_string(),
            });
        };
        let (addr, element_type) = self.ensure_scalar_stack_slot(name)?;
        Ok(ExprValue::RefMut { addr, element_type })
    }

    fn compile_deref(&mut self, target: &Expr) -> Result<ExprValue, CompileError> {
        let (addr, element_type) = self.compile_ref_mut_expr(target)?;
        let dst = self.alloc_register()?;
        self.emit_load(element_type, dst, addr);
        match element_type {
            TypeName::I32 => Ok(ExprValue::I32(dst)),
            TypeName::U32 => Ok(ExprValue::U32(dst)),
            TypeName::U8 => Ok(ExprValue::U8(dst)),
            _ => unreachable!("references are restricted to scalar memory types"),
        }
    }

    fn compile_ref_mut_expr(&mut self, expr: &Expr) -> Result<(u16, TypeName), CompileError> {
        match self.compile_expr(expr)? {
            ExprValue::RefMut { addr, element_type } => Ok((addr, element_type)),
            value => Err(CompileError {
                message: format!("expected mutable reference, found {}", value.type_name()),
            }),
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
            BinaryOp::Rem => Instruction::I32Rem { dst, lhs, rhs },
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
            BinaryOp::Div => Instruction::U32Div { dst, lhs, rhs },
            BinaryOp::Rem => Instruction::U32Rem { dst, lhs, rhs },
            BinaryOp::Add => Instruction::I32Add { dst, lhs, rhs },
            BinaryOp::Sub => Instruction::I32Sub { dst, lhs, rhs },
            BinaryOp::Mul => Instruction::I32Mul { dst, lhs, rhs },
            BinaryOp::BitAnd => Instruction::I32BitAnd { dst, lhs, rhs },
            BinaryOp::BitOr => Instruction::I32BitOr { dst, lhs, rhs },
            BinaryOp::BitXor => Instruction::I32BitXor { dst, lhs, rhs },
        };
        self.instructions.push(instruction);
    }

    fn compile_cast(&mut self, expr: &Expr, target: TypeName) -> Result<ExprValue, CompileError> {
        match target {
            TypeName::I32 => match self.compile_expr(expr)? {
                ExprValue::I32(register) | ExprValue::U32(register) | ExprValue::U8(register) => {
                    Ok(ExprValue::I32(register))
                }
                ExprValue::Bool(_) => Err(CompileError {
                    message: "cannot cast bool to `i32`".to_string(),
                }),
                value => Err(CompileError {
                    message: format!("cannot cast {} to `i32`", value.type_name()),
                }),
            },
            TypeName::U32 => match self.compile_expr(expr)? {
                ExprValue::I32(register) | ExprValue::U32(register) | ExprValue::U8(register) => {
                    Ok(ExprValue::U32(register))
                }
                ExprValue::Bool(_) => Err(CompileError {
                    message: "cannot cast bool to `u32`".to_string(),
                }),
                value => Err(CompileError {
                    message: format!("cannot cast {} to `u32`", value.type_name()),
                }),
            },
            TypeName::U8 => match self.compile_expr(expr)? {
                ExprValue::U8(register) => Ok(ExprValue::U8(register)),
                ExprValue::I32(register) | ExprValue::U32(register) => {
                    let mask = self.emit_i32_const(0xff)?;
                    let dst = self.alloc_register()?;
                    self.instructions.push(Instruction::I32BitAnd {
                        dst,
                        lhs: register,
                        rhs: mask,
                    });
                    Ok(ExprValue::U8(dst))
                }
                ExprValue::Bool(_) => Err(CompileError {
                    message: "cannot cast bool to `u8`".to_string(),
                }),
                value => Err(CompileError {
                    message: format!("cannot cast {} to `u8`", value.type_name()),
                }),
            },
            TypeName::Bool => Err(CompileError {
                message: "casts to `bool` are not supported".to_string(),
            }),
            TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => Err(CompileError {
                message: format!("casts to `{}` are not supported", target.name()),
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
            let register = self
                .compile_expr_as(arg, expected)
                .map_err(|error| self.function_argument_error(name, index, expected, error))?;
            arguments.push(register);
        }
        let return_register = match signature.return_type {
            ReturnType::Unit => None,
            ReturnType::I32
            | ReturnType::U32
            | ReturnType::U8
            | ReturnType::Bool
            | ReturnType::PtrI32
            | ReturnType::PtrU32
            | ReturnType::PtrU8 => Some(self.alloc_register()?),
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
                ReturnType::U8 => Ok(ExprValue::U8(register)),
                ReturnType::Bool => Ok(ExprValue::Bool(register)),
                ReturnType::PtrI32 => Ok(ExprValue::Pointer {
                    addr: register,
                    kind: PointerKind::Ptr,
                    element_type: TypeName::I32,
                }),
                ReturnType::PtrU32 => Ok(ExprValue::Pointer {
                    addr: register,
                    kind: PointerKind::Ptr,
                    element_type: TypeName::U32,
                }),
                ReturnType::PtrU8 => Ok(ExprValue::Pointer {
                    addr: register,
                    kind: PointerKind::Ptr,
                    element_type: TypeName::U8,
                }),
                ReturnType::Unit => unreachable!("unit functions do not allocate return registers"),
            },
            None => Ok(ExprValue::Unit),
        }
    }

    fn function_argument_error(
        &self,
        function_name: &str,
        index: usize,
        expected: TypeName,
        error: CompileError,
    ) -> CompileError {
        let expected_prefix = format!("expected `{}`, ", expected.name());
        let detail = error
            .message
            .strip_prefix(&expected_prefix)
            .unwrap_or(&error.message);
        CompileError {
            message: format!(
                "function `{function_name}` argument {index} expected `{}`, {detail}",
                expected.name(),
            ),
        }
    }

    fn compile_method_call(
        &mut self,
        receiver: &Expr,
        method: &str,
        args: &[Expr],
    ) -> Result<ExprValue, CompileError> {
        if method == "len" {
            if !args.is_empty() {
                return Err(CompileError {
                    message: "array.len expects no arguments".to_string(),
                });
            }
            if let Expr::Local(name) = receiver {
                if let Some(Local {
                    ty: ValueType::ArrayU8(len),
                    ..
                }) = self.locals.get(name).copied()
                {
                    return Ok(ExprValue::U32(self.emit_u32_literal(i64::from(len))?));
                }
            }
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
        if self.unsafe_depth == 0 && kind.requires_unsafe() {
            return Err(CompileError {
                message: format!("{} access requires `unsafe`", kind.access_name()),
            });
        }

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
                self.emit_store(element_type, addr, src);
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
                self.emit_load(element_type, dst, addr);
                match element_type {
                    TypeName::I32 => Ok(ExprValue::I32(dst)),
                    TypeName::U32 => Ok(ExprValue::U32(dst)),
                    TypeName::U8 => Ok(ExprValue::U8(dst)),
                    TypeName::Bool
                    | TypeName::PtrI32
                    | TypeName::PtrU32
                    | TypeName::PtrU8
                    | TypeName::RefMutI32
                    | TypeName::RefMutU32
                    | TypeName::RefMutU8
                    | TypeName::ArrayU8(_) => {
                        unreachable!("non-scalar memory element types are rejected earlier")
                    }
                }
            }
            _ => Err(CompileError {
                message: format!("unknown MMIO method `{method}`"),
            }),
        }
    }

    fn compile_index(&mut self, target: &Expr, index: &Expr) -> Result<ExprValue, CompileError> {
        let (addr, kind, element_type) = self.compile_index_address(target, index)?;
        if self.unsafe_depth == 0 && kind.requires_unsafe() {
            return Err(CompileError {
                message: format!("{} access requires `unsafe`", kind.access_name()),
            });
        }
        let dst = self.alloc_register()?;
        self.emit_load(element_type, dst, addr);
        match element_type {
            TypeName::I32 => Ok(ExprValue::I32(dst)),
            TypeName::U32 => Ok(ExprValue::U32(dst)),
            TypeName::U8 => Ok(ExprValue::U8(dst)),
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unreachable!("non-scalar memory element types are rejected earlier")
            }
        }
    }

    fn compile_index_address(
        &mut self,
        target: &Expr,
        index: &Expr,
    ) -> Result<(u16, PointerKind, TypeName), CompileError> {
        self.validate_literal_stack_array_index(target, index)?;
        let ExprValue::Pointer {
            addr: base,
            kind,
            element_type,
        } = self.compile_expr(target)?
        else {
            return Err(CompileError {
                message: "index target must be a pointer capability".to_string(),
            });
        };

        let index = self.compile_u32_expr(index)?;
        let offset = match element_type {
            TypeName::U8 => index,
            TypeName::I32 | TypeName::U32 => {
                let scale = self.emit_i32_const(4)?;
                let offset = self.alloc_register()?;
                self.instructions.push(Instruction::I32Mul {
                    dst: offset,
                    lhs: index,
                    rhs: scale,
                });
                offset
            }
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unreachable!("non-scalar memory element types are rejected earlier")
            }
        };
        let addr = self.alloc_register()?;
        self.instructions.push(Instruction::AddrAdd {
            dst: addr,
            base,
            offset,
        });
        Ok((addr, kind, element_type))
    }

    fn validate_literal_stack_array_index(
        &self,
        target: &Expr,
        index: &Expr,
    ) -> Result<(), CompileError> {
        let Expr::Local(name) = target else {
            return Ok(());
        };
        let Some(Local {
            ty: ValueType::ArrayU8(len),
            ..
        }) = self.locals.get(name).copied()
        else {
            return Ok(());
        };
        let literal = match index {
            Expr::Int(value) | Expr::IntU32(value) | Expr::IntU8(value) => *value,
            _ => return Ok(()),
        };
        let Ok(index) = u32::try_from(literal) else {
            return Err(CompileError {
                message: format!("array index {literal} is out of bounds for `[u8; {len}]`"),
            });
        };
        if index >= len {
            return Err(CompileError {
                message: format!("array index {index} is out of bounds for `[u8; {len}]`"),
            });
        }
        Ok(())
    }

    fn emit_load(&mut self, element_type: TypeName, dst: u16, addr: u16) {
        match element_type {
            TypeName::U8 => self.instructions.push(Instruction::Load8 { dst, addr }),
            TypeName::I32 | TypeName::U32 => {
                self.instructions.push(Instruction::Load32 { dst, addr })
            }
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unreachable!("non-scalar memory element types are rejected earlier")
            }
        }
    }

    fn emit_store(&mut self, element_type: TypeName, addr: u16, src: u16) {
        match element_type {
            TypeName::U8 => self.instructions.push(Instruction::Store8 { addr, src }),
            TypeName::I32 | TypeName::U32 => {
                self.instructions.push(Instruction::Store32 { addr, src })
            }
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unreachable!("non-scalar memory element types are rejected earlier")
            }
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
            TypeName::I32 | TypeName::U32 | TypeName::U8 => Ok(()),
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => Err(CompileError {
                message: "memory pointer element type must be `i32`, `u32`, or `u8`".to_string(),
            }),
        }
    }

    fn ensure_scalar_stack_slot(&mut self, name: &str) -> Result<(u16, TypeName), CompileError> {
        let local = *self.locals.get(name).ok_or_else(|| CompileError {
            message: format!("borrow of undeclared local `{name}`"),
        })?;
        if !local.mutable {
            return Err(CompileError {
                message: format!("cannot borrow immutable local `{name}` as mutable"),
            });
        }
        let element_type = match local.ty {
            ValueType::I32 => TypeName::I32,
            ValueType::U32 => TypeName::U32,
            ValueType::U8 => TypeName::U8,
            _ => {
                return Err(CompileError {
                    message: format!(
                        "`&mut` supports only scalar locals for now, found {}",
                        local.ty.type_name().name()
                    ),
                });
            }
        };
        if let Some(addr) = local.stack_addr {
            return Ok((addr, element_type));
        }

        let addr = self.alloc_stack_slot(element_type)?;
        self.emit_store(element_type, addr, local.register);
        let local = self.locals.get_mut(name).ok_or_else(|| CompileError {
            message: format!("borrow of undeclared local `{name}`"),
        })?;
        local.stack_addr = Some(addr);
        Ok((addr, element_type))
    }

    fn alloc_stack_slot(&mut self, ty: TypeName) -> Result<u16, CompileError> {
        let size = ty.stack_size().ok_or_else(|| CompileError {
            message: format!("type `{}` cannot be stack allocated", ty.name()),
        })?;
        let alignment = ty.stack_alignment().ok_or_else(|| CompileError {
            message: format!("type `{}` cannot be stack allocated", ty.name()),
        })?;
        self.next_stack_offset = align_up(self.next_stack_offset, alignment)?;
        self.next_stack_offset =
            self.next_stack_offset
                .checked_add(size)
                .ok_or_else(|| CompileError {
                    message: "stack frame is too large".to_string(),
                })?;
        let value = DEFAULT_MEMORY_SIZE
            .checked_sub(self.next_stack_offset)
            .ok_or_else(|| CompileError {
                message: "stack frame exceeds image memory".to_string(),
            })?;
        let dst = self.alloc_register()?;
        self.instructions
            .push(Instruction::AddrConst { dst, value });
        Ok(dst)
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

    fn emit_u8_literal(&mut self, value: i64) -> Result<u16, CompileError> {
        let value = u8::try_from(value).map_err(|_| CompileError {
            message: format!("integer literal `{value}` does not fit `u8`"),
        })?;
        self.emit_i32_const(i32::from(value))
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
            (ExprValue::U8(lhs), ExprValue::U8(rhs)) => self.compile_u32_compare(op, lhs, rhs),
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
