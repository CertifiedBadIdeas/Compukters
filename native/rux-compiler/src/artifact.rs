use crate::frontend::ast::{
    BinaryOp, CompareOp, ConstDecl, Expr, FunctionDecl, Program, ReturnType, Statement, TypeName,
};
use crate::frontend::CompileError;
use crate::rux16_asm;
use crate::ruxe;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rux16ArtifactTarget {
    Bios,
    Boot,
    Kernel,
    Program,
}

impl Rux16ArtifactTarget {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "bios" => Ok(Self::Bios),
            "boot" => Ok(Self::Boot),
            "kernel" => Ok(Self::Kernel),
            "program" => Ok(Self::Program),
            _ => Err(format!(
                "unknown compile target `{value}`; expected bios, boot, kernel, or program"
            )),
        }
    }

    pub fn base_address(self) -> u32 {
        match self {
            Self::Bios => rux_vm::computer_machine::ComputerMachine::RUX16_BIOS_FLASH_BASE,
            Self::Boot => 2048,
            Self::Kernel => 0x4000,
            Self::Program => 0,
        }
    }

    pub fn fixed_image_abi_kind(self) -> Option<ruxe::RuxeAbiKind> {
        match self {
            Self::Boot => Some(ruxe::RuxeAbiKind::Bootloader),
            Self::Kernel => Some(ruxe::RuxeAbiKind::Kernel),
            Self::Bios | Self::Program => None,
        }
    }

    fn initial_stack_top(self) -> u32 {
        match self {
            Self::Bios => 512,
            Self::Boot => Self::Boot.base_address(),
            Self::Kernel => Self::Kernel.base_address(),
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
    if target == Rux16ArtifactTarget::Program {
        return Err(CompileError {
            message: "Rux16 user-space program ABI is not defined yet".to_string(),
        });
    }
    let consts = evaluate_consts(&program.consts)?;
    let functions = collect_supported_functions(&program)?;
    let mut backend = Rux16ArtifactBackend::new(
        consts,
        functions,
        target.base_address(),
        target.initial_stack_top(),
    );
    backend.inline_unit_function("main", &[], false)?;
    backend.words.push(rux16_asm::halt());
    backend.emit_pending_function_bodies()?;
    backend.patch_pending_calls()?;

    let code = rux16_asm::encode_words(&backend.words);
    let bytes = match target {
        Rux16ArtifactTarget::Boot | Rux16ArtifactTarget::Kernel => ruxe::encode_rux16_executable(
            &code,
            target.fixed_image_abi_kind().unwrap(),
            target.base_address(),
            target.base_address(),
        )
        .map_err(|message| CompileError { message })?,
        Rux16ArtifactTarget::Bios => code,
        Rux16ArtifactTarget::Program => unreachable!("program target is rejected before lowering"),
    };

    Ok(Rux16Artifact { target, bytes })
}

struct Rux16ArtifactBackend {
    words: Vec<u16>,
    consts: HashMap<String, ConstValue>,
    functions: HashMap<String, FunctionDecl>,
    locals: HashMap<String, Rux16Local>,
    call_stack: Vec<String>,
    active_function_return_type: Option<ReturnType>,
    next_register: u8,
    base_address: u32,
    initial_stack_top: u32,
    stack_initialized: bool,
    pending_functions: Vec<String>,
    function_addresses: HashMap<String, u32>,
    pending_call_patches: Vec<(usize, String)>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Rux16Local {
    ty: TypeName,
    register: u8,
}

impl Rux16ArtifactBackend {
    fn new(
        consts: HashMap<String, ConstValue>,
        functions: HashMap<String, FunctionDecl>,
        base_address: u32,
        initial_stack_top: u32,
    ) -> Self {
        Self {
            words: Vec::new(),
            consts,
            functions,
            locals: HashMap::new(),
            call_stack: Vec::new(),
            active_function_return_type: None,
            next_register: 3,
            base_address,
            initial_stack_top,
            stack_initialized: false,
            pending_functions: Vec::new(),
            function_addresses: HashMap::new(),
            pending_call_patches: Vec::new(),
        }
    }

    fn inline_unit_function(
        &mut self,
        name: &str,
        args: &[Expr],
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
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
        if function.return_type != ReturnType::Unit {
            return unsupported(format!("helper function `{name}` does not return unit"));
        }
        let callee_locals = self.compile_call_arguments(&function, args, unsafe_context)?;
        self.call_stack.push(name.to_string());
        let caller_locals = std::mem::take(&mut self.locals);
        self.locals = callee_locals;
        let result = self.compile_statements(&function.statements, false);
        self.locals = caller_locals;
        self.call_stack.pop();
        result
    }

    fn emit_real_function_call(
        &mut self,
        name: &str,
        args: &[Expr],
        expected_return: Option<TypeName>,
        return_destination: Option<u8>,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        if self.call_stack.iter().any(|active| active == name) {
            return unsupported(format!("recursive Rux16 helper call `{name}`"));
        }
        let function = self
            .functions
            .get(name)
            .cloned()
            .ok_or_else(|| CompileError {
                message: format!(
                    "Rux16 backend does not support this program yet: unknown helper function `{name}`"
                ),
            })?;
        self.validate_real_function_call(&function, args.len(), expected_return)?;
        self.ensure_stack_initialized();
        if !self.function_addresses.contains_key(name)
            && !self.pending_functions.iter().any(|pending| pending == name)
        {
            self.pending_functions.push(name.to_string());
        }
        let saved_registers = self.live_local_registers();
        for register in &saved_registers {
            self.emit_push_register(*register);
        }
        self.compile_call_abi_arguments(&function, args, unsafe_context)?;
        let const_index = self.words.len();
        self.words
            .extend_from_slice(&rux16_asm::const32(rux16_asm::SCRATCH_REGISTER, 0));
        self.words
            .push(rux16_asm::call(rux16_asm::SCRATCH_REGISTER));
        self.pending_call_patches
            .push((const_index, name.to_string()));
        for register in saved_registers.into_iter().rev() {
            self.emit_pop_register(register);
        }
        if let Some(destination) = return_destination {
            self.emit_register_copy(destination, rux16_asm::RETURN_REGISTER);
        }
        Ok(())
    }

    fn validate_real_function_call(
        &self,
        function: &FunctionDecl,
        arg_count: usize,
        expected_return: Option<TypeName>,
    ) -> Result<(), CompileError> {
        if arg_count != function.parameters.len() {
            return unsupported(format!(
                "helper function `{}` expects {} arguments, got {}",
                function.name,
                function.parameters.len(),
                arg_count
            ));
        }
        if arg_count > rux16_asm::ARGUMENT_REGISTERS.len() {
            return unsupported(format!(
                "helper function `{}` has {} parameters, but the Rux16 call ABI supports at most {}",
                function.name,
                arg_count,
                rux16_asm::ARGUMENT_REGISTERS.len()
            ));
        }
        for parameter in &function.parameters {
            if !is_call_abi_value_type(parameter.ty) {
                return unsupported(
                    "only i32, u32, and u8 parameters can be lowered to Rux16 calls yet",
                );
            }
        }
        let return_ty = return_type_to_type_name(function.return_type);
        match (expected_return, return_ty) {
            (None, None) => Ok(()),
            (None, Some(_)) => unsupported(format!(
                "helper function `{}` returns a value but is called as a statement",
                function.name
            )),
            (Some(_), None) => unsupported(format!(
                "helper function `{}` does not return a value",
                function.name
            )),
            (Some(expected), Some(actual))
                if expected == actual && is_call_abi_value_type(actual) =>
            {
                Ok(())
            }
            (Some(expected), Some(actual)) if actual == expected => {
                unsupported("only i32, u32, and u8 returns can be lowered to Rux16 calls yet")
            }
            (Some(expected), Some(actual)) => unsupported(format!(
                "helper function `{}` returns {}, expected {}",
                function.name,
                type_name(actual),
                type_name(expected)
            )),
        }
    }

    fn live_local_registers(&self) -> Vec<u8> {
        let mut registers = self
            .locals
            .values()
            .map(|local| local.register)
            .collect::<Vec<_>>();
        registers.sort_unstable();
        registers.dedup();
        registers
    }

    fn ensure_stack_initialized(&mut self) {
        if self.stack_initialized {
            return;
        }
        self.words.extend_from_slice(&rux16_asm::const32(
            rux16_asm::STACK_POINTER_REGISTER,
            self.initial_stack_top,
        ));
        self.stack_initialized = true;
    }

    fn emit_push_register(&mut self, register: u8) {
        self.words.extend_from_slice(&rux16_asm::const32(
            rux16_asm::SCRATCH_REGISTER,
            u32::MAX - 3,
        ));
        self.words.push(rux16_asm::add(
            rux16_asm::STACK_POINTER_REGISTER,
            rux16_asm::STACK_POINTER_REGISTER,
            rux16_asm::SCRATCH_REGISTER,
        ));
        self.words.push(rux16_asm::store32(
            rux16_asm::STACK_POINTER_REGISTER,
            register,
        ));
    }

    fn emit_pop_register(&mut self, register: u8) {
        self.words.push(rux16_asm::load32(
            register,
            rux16_asm::STACK_POINTER_REGISTER,
        ));
        self.words
            .extend_from_slice(&rux16_asm::const32(rux16_asm::SCRATCH_REGISTER, 4));
        self.words.push(rux16_asm::add(
            rux16_asm::STACK_POINTER_REGISTER,
            rux16_asm::STACK_POINTER_REGISTER,
            rux16_asm::SCRATCH_REGISTER,
        ));
    }

    fn emit_pending_function_bodies(&mut self) -> Result<(), CompileError> {
        let mut index = 0;
        while index < self.pending_functions.len() {
            let name = self.pending_functions[index].clone();
            index += 1;
            if self.function_addresses.contains_key(&name) {
                continue;
            }
            let function = self
                .functions
                .get(&name)
                .cloned()
                .ok_or_else(|| CompileError {
                    message: format!(
                        "Rux16 backend does not support this program yet: unknown helper function `{name}`"
                    ),
                })?;
            self.validate_real_function_call(
                &function,
                function.parameters.len(),
                return_type_to_type_name(function.return_type),
            )?;
            self.function_addresses
                .insert(name.clone(), self.current_address());
            self.call_stack.push(name);
            let caller_locals = std::mem::take(&mut self.locals);
            let caller_next_register = self.next_register;
            let caller_return_type = self.active_function_return_type;
            self.locals = self.call_abi_parameter_locals(&function)?;
            self.next_register = first_callee_local_register(function.parameters.len());
            self.active_function_return_type = Some(function.return_type);
            let result = self.compile_function_body(&function);
            if matches!(result, Ok(false)) {
                self.words.push(rux16_asm::ret());
            }
            self.locals = caller_locals;
            self.next_register = caller_next_register;
            self.active_function_return_type = caller_return_type;
            self.call_stack.pop();
            result?;
        }
        Ok(())
    }

    fn patch_pending_calls(&mut self) -> Result<(), CompileError> {
        for (const_index, name) in self.pending_call_patches.clone() {
            let address = *self
                .function_addresses
                .get(&name)
                .ok_or_else(|| CompileError {
                    message: format!("Rux16 helper function `{name}` was called but not emitted"),
                })?;
            self.patch_absolute_const32(const_index, address)?;
        }
        Ok(())
    }

    fn compile_call_arguments(
        &mut self,
        function: &FunctionDecl,
        args: &[Expr],
        unsafe_context: bool,
    ) -> Result<HashMap<String, Rux16Local>, CompileError> {
        if args.len() != function.parameters.len() {
            return unsupported(format!(
                "helper function `{}` expects {} arguments, got {}",
                function.name,
                function.parameters.len(),
                args.len()
            ));
        }
        let mut locals = HashMap::new();
        for (parameter, arg) in function.parameters.iter().zip(args) {
            if locals.contains_key(&parameter.name) {
                return unsupported(format!("duplicate Rux16 parameter `{}`", parameter.name));
            }
            let register = self.alloc_register()?;
            match parameter.ty {
                TypeName::I32 => self.compile_i32_expr_into(register, arg, unsafe_context)?,
                TypeName::U32 => self.compile_u32_expr_into(register, arg, unsafe_context)?,
                TypeName::U8 => self.compile_u8_expr_into(register, arg, unsafe_context)?,
                TypeName::Bool
                | TypeName::PtrI32
                | TypeName::PtrU32
                | TypeName::PtrU8
                | TypeName::RefMutI32
                | TypeName::RefMutU32
                | TypeName::RefMutU8
                | TypeName::ArrayU8(_) => {
                    return unsupported(
                        "only i32, u32, and u8 parameters can be lowered to Rux16 yet",
                    );
                }
            }
            locals.insert(
                parameter.name.clone(),
                Rux16Local {
                    ty: parameter.ty,
                    register,
                },
            );
        }
        Ok(locals)
    }

    fn compile_call_abi_arguments(
        &mut self,
        function: &FunctionDecl,
        args: &[Expr],
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        for ((parameter, arg), register) in function
            .parameters
            .iter()
            .zip(args)
            .zip(rux16_asm::ARGUMENT_REGISTERS)
        {
            match parameter.ty {
                TypeName::I32 => self.compile_i32_expr_into(register, arg, unsafe_context)?,
                TypeName::U32 => self.compile_u32_expr_into(register, arg, unsafe_context)?,
                TypeName::U8 => self.compile_u8_expr_into(register, arg, unsafe_context)?,
                TypeName::Bool
                | TypeName::PtrI32
                | TypeName::PtrU32
                | TypeName::PtrU8
                | TypeName::RefMutI32
                | TypeName::RefMutU32
                | TypeName::RefMutU8
                | TypeName::ArrayU8(_) => {
                    return unsupported(
                        "only i32, u32, and u8 parameters can be lowered to Rux16 calls yet",
                    );
                }
            }
        }
        Ok(())
    }

    fn call_abi_parameter_locals(
        &self,
        function: &FunctionDecl,
    ) -> Result<HashMap<String, Rux16Local>, CompileError> {
        let mut locals = HashMap::new();
        for (parameter, register) in function
            .parameters
            .iter()
            .zip(rux16_asm::ARGUMENT_REGISTERS)
        {
            if locals.contains_key(&parameter.name) {
                return unsupported(format!("duplicate Rux16 parameter `{}`", parameter.name));
            }
            locals.insert(
                parameter.name.clone(),
                Rux16Local {
                    ty: parameter.ty,
                    register,
                },
            );
        }
        Ok(locals)
    }

    fn compile_function_body(&mut self, function: &FunctionDecl) -> Result<bool, CompileError> {
        let returns_on_all_paths = statements_return_on_all_paths(&function.statements);
        if function.return_type != ReturnType::Unit && !returns_on_all_paths {
            return unsupported(format!(
                "returning helper function `{}` does not return on all paths",
                function.name
            ));
        }
        self.compile_statements(&function.statements, false)?;
        Ok(returns_on_all_paths)
    }

    fn compile_return_value_into(
        &mut self,
        dst: u8,
        expected_ty: TypeName,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match expected_ty {
            TypeName::I32 => self.compile_i32_expr_into(dst, expr, unsafe_context),
            TypeName::U32 => self.compile_u32_expr_into(dst, expr, unsafe_context),
            TypeName::U8 => self.compile_u8_expr_into(dst, expr, unsafe_context),
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unsupported("only i32, u32, and u8 returns can be lowered to Rux16 yet")
            }
        }
    }

    fn compile_return_statement(
        &mut self,
        value: Option<&Expr>,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let Some(return_type) = self.active_function_return_type else {
            return unsupported(
                "return statements are only supported inside Rux16 helper bodies yet",
            );
        };
        match (return_type, value) {
            (ReturnType::Unit, None) => {
                self.words.push(rux16_asm::ret());
                Ok(())
            }
            (ReturnType::Unit, Some(_)) => {
                unsupported("unit Rux16 helper return cannot carry a value")
            }
            (_, None) => unsupported("value-returning Rux16 helper return requires a value"),
            (_, Some(expr)) => {
                let expected_ty = return_type_to_type_name(return_type).ok_or_else(|| {
                    CompileError {
                        message: "Rux16 backend does not support this program yet: value-returning helper return requires a value".to_string(),
                    }
                })?;
                self.compile_return_value_into(
                    rux16_asm::RETURN_REGISTER,
                    expected_ty,
                    expr,
                    unsafe_context,
                )?;
                self.words.push(rux16_asm::ret());
                Ok(())
            }
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
            Statement::AssignOp { name, op, value } => {
                self.compile_assign_op_statement(name, *op, value, unsafe_context)
            }
            Statement::Return(value) => {
                self.compile_return_statement(value.as_ref(), unsafe_context)
            }
            Statement::IndexAssign { .. }
            | Statement::DerefAssign { .. }
            | Statement::Break
            | Statement::Continue => {
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
            TypeName::U32 => self.compile_u32_expr_into(register, initializer, unsafe_context)?,
            TypeName::U8 => self.compile_u8_expr_into(register, initializer, unsafe_context)?,
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                return unsupported("only i32, u32, and u8 locals can be lowered to Rux16 yet");
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
            TypeName::U32 => self.compile_u32_expr_into(local.register, value, unsafe_context),
            TypeName::U8 => self.compile_u8_expr_into(local.register, value, unsafe_context),
            TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unsupported("only i32, u32, and u8 locals can be assigned in Rux16 yet")
            }
        }
    }

    fn compile_assign_op_statement(
        &mut self,
        name: &str,
        op: BinaryOp,
        value: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        if op != BinaryOp::Add {
            return unsupported("only `+=` compound assignment can be lowered in Rux16 yet");
        }
        let local = self.locals.get(name).copied().ok_or_else(|| CompileError {
            message: format!(
                "Rux16 backend does not support this program yet: unknown local `{name}`"
            ),
        })?;
        match local.ty {
            TypeName::I32 => {
                let rhs = self.compile_i32_expr_to_register_or_use(14, value, unsafe_context)?;
                self.words
                    .push(rux16_asm::add(local.register, local.register, rhs));
                Ok(())
            }
            TypeName::U32 => {
                let rhs = self.compile_u32_expr_to_register_or_use(14, value, unsafe_context)?;
                self.words
                    .push(rux16_asm::add(local.register, local.register, rhs));
                Ok(())
            }
            TypeName::U8
            | TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => {
                unsupported("only i32 and u32 `+=` can be lowered in Rux16 yet")
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
            return self.emit_real_function_call(name, args, None, None, unsafe_context);
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
        match ty {
            TypeName::I32 => {
                let src = self.compile_i32_expr_to_scratch(&args[0], unsafe_context)?;
                let address_register = scratch_register_excluding(src);
                let address = self.compile_mmio_address_to_register_or_use(
                    address_register,
                    address,
                    unsafe_context,
                )?;
                self.words.push(rux16_asm::store32(address, src));
            }
            TypeName::U8 => {
                let src = self.compile_u8_expr_to_scratch(&args[0], unsafe_context)?;
                let address_register = scratch_register_excluding(src);
                let address = self.compile_mmio_address_to_register_or_use(
                    address_register,
                    address,
                    unsafe_context,
                )?;
                self.words.push(rux16_asm::store8(address, src));
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
        match op {
            CompareOp::Eq => {
                self.compile_equality_condition_into(dst, lhs, rhs, unsafe_context)?;
                Ok(())
            }
            CompareOp::Ne => {
                self.compile_equality_condition_into(dst, lhs, rhs, unsafe_context)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(rux16_asm::SCRATCH_REGISTER, 0));
                self.words
                    .extend_from_slice(&rux16_asm::eq(dst, dst, rux16_asm::SCRATCH_REGISTER));
                Ok(())
            }
            CompareOp::Lt => {
                let lhs = self.compile_u32_expr_to_register_or_use(2, lhs, unsafe_context)?;
                let rhs = self.compile_u32_expr_to_register_or_use(14, rhs, unsafe_context)?;
                self.words.extend_from_slice(&rux16_asm::ltu(dst, lhs, rhs));
                Ok(())
            }
            CompareOp::Gt | CompareOp::Le | CompareOp::Ge => unsupported(
                "only `==`, `!=`, and unsigned `<` comparisons can be lowered as Rux16 conditions",
            ),
        }
    }

    fn compile_equality_condition_into(
        &mut self,
        dst: u8,
        lhs: &Expr,
        rhs: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        let lhs_ty = self.condition_operand_type(lhs)?;
        let rhs_ty = self.condition_operand_type(rhs)?;
        if lhs_ty != rhs_ty {
            return unsupported(format!(
                "mixed {} and {} equality comparisons cannot be lowered as Rux16 conditions",
                type_name(lhs_ty),
                type_name(rhs_ty)
            ));
        }
        match lhs_ty {
            TypeName::I32 => {
                let lhs = self.compile_i32_expr_to_scratch(lhs, unsafe_context)?;
                let rhs = self.compile_i32_expr_to_register_or_use(
                    rux16_asm::SCRATCH_REGISTER,
                    rhs,
                    unsafe_context,
                )?;
                self.words.extend_from_slice(&rux16_asm::eq(dst, lhs, rhs));
                Ok(())
            }
            TypeName::U8 => {
                let lhs = self.compile_u8_expr_to_scratch(lhs, unsafe_context)?;
                self.compile_u8_expr_into(rux16_asm::SCRATCH_REGISTER, rhs, unsafe_context)?;
                self.words
                    .extend_from_slice(&rux16_asm::eq(dst, lhs, rux16_asm::SCRATCH_REGISTER));
                Ok(())
            }
            TypeName::U32
            | TypeName::Bool
            | TypeName::PtrI32
            | TypeName::PtrU32
            | TypeName::PtrU8
            | TypeName::RefMutI32
            | TypeName::RefMutU32
            | TypeName::RefMutU8
            | TypeName::ArrayU8(_) => unsupported(
                "only i32 and u8 equality comparisons can be lowered as Rux16 conditions",
            ),
        }
    }

    fn condition_operand_type(&self, expr: &Expr) -> Result<TypeName, CompileError> {
        match expr {
            Expr::Int(_) => Ok(TypeName::I32),
            Expr::IntU8(_) => Ok(TypeName::U8),
            Expr::IntU32(_) => Ok(TypeName::U32),
            Expr::Local(name) => {
                if let Some(local) = self.locals.get(name) {
                    return Ok(local.ty);
                }
                self.consts
                    .get(name)
                    .map(|value| value.type_name())
                    .ok_or_else(|| CompileError {
                        message: format!(
                            "Rux16 backend does not support this program yet: unknown Rux16 condition value `{name}`"
                        ),
                    })
            }
            Expr::Path(path) => {
                let name = path_name(path);
                self.consts
                    .get(&name)
                    .map(|value| value.type_name())
                    .ok_or_else(|| CompileError {
                        message: format!(
                            "Rux16 backend does not support this program yet: unknown Rux16 condition value `{name}`"
                        ),
                    })
            }
            Expr::Call { name, .. } => {
                let function = self.functions.get(name).ok_or_else(|| CompileError {
                    message: format!(
                        "Rux16 backend does not support this program yet: unknown helper function `{name}`"
                    ),
                })?;
                return_type_to_type_name(function.return_type).ok_or_else(|| CompileError {
                    message: format!(
                        "Rux16 backend does not support this program yet: helper function `{name}` does not return a condition value"
                    ),
                })
            }
            Expr::MethodCall {
                receiver,
                method,
                args,
            } if method == "load" && args.is_empty() => match receiver.as_ref() {
                Expr::Mmio { ty, .. } | Expr::Ptr { ty, .. } => Ok(*ty),
                _ => unsupported(
                    "only `mmio<T>(...).load()` and `ptr<T>(...).load()` condition operands can be lowered",
                ),
            },
            Expr::Binary { .. }
            | Expr::ByteString(_)
            | Expr::Bool(_)
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
                unsupported("only local, literal, const, and helper-call equality operands can be lowered as Rux16 conditions")
            }
        }
    }

    fn compile_mmio_address_to_register_or_use(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::U32)? {
                return Ok(local.register);
            }
        }
        if matches!(expr, Expr::Binary { .. } | Expr::Call { .. }) {
            self.compile_u32_expr_into(dst, expr, unsafe_context)?;
            return Ok(dst);
        }
        let address = self.eval_mmio_address(expr)?;
        self.words
            .extend_from_slice(&rux16_asm::const32(dst, address));
        Ok(dst)
    }

    fn compile_ram_address_to_register_or_use(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::U32)? {
                return Ok(local.register);
            }
        }
        if matches!(expr, Expr::Binary { .. } | Expr::Call { .. }) {
            self.compile_u32_expr_into(dst, expr, unsafe_context)?;
            return Ok(dst);
        }
        let address = self.eval_ram_address(expr)?;
        self.words
            .extend_from_slice(&rux16_asm::const32(dst, address));
        Ok(dst)
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

    fn compile_u32_expr_to_register_or_use(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<u8, CompileError> {
        if let Expr::Local(name) = expr {
            if let Some(local) = self.local(name, TypeName::U32)? {
                return Ok(local.register);
            }
        }
        self.compile_u32_expr_into(dst, expr, unsafe_context)?;
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
                if let Some(local) = self.local(name, TypeName::I32)? {
                    self.emit_register_copy(dst, local.register);
                    return Ok(());
                }
                let value = self.eval_i32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value as u32));
                Ok(())
            }
            Expr::Binary {
                op: BinaryOp::Add,
                lhs,
                rhs,
            } => {
                let lhs = self.compile_i32_expr_to_register_or_use(dst, lhs, unsafe_context)?;
                let rhs = self.compile_i32_expr_to_register_or_use(14, rhs, unsafe_context)?;
                self.words.push(rux16_asm::add(dst, lhs, rhs));
                Ok(())
            }
            Expr::Call { name, args } => self.emit_real_function_call(
                name,
                args,
                Some(TypeName::I32),
                Some(dst),
                unsafe_context,
            ),
            Expr::Path(_) => {
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
                        let address = self.compile_mmio_address_to_register_or_use(
                            1,
                            address,
                            unsafe_context,
                        )?;
                        self.words.push(rux16_asm::load32(dst, address));
                        Ok(())
                    }
                    Expr::Ptr { ty, address } => {
                        if *ty != TypeName::I32 {
                            return unsupported(
                                "i32 local initializer requires `ptr<i32>(...).load()`",
                            );
                        }
                        let address = self.compile_ram_address_to_register_or_use(
                            1,
                            address,
                            unsafe_context,
                        )?;
                        self.words.push(rux16_asm::load32(dst, address));
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

    fn compile_u32_expr_into(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match expr {
            Expr::Local(name) => {
                if let Some(local) = self.local(name, TypeName::U32)? {
                    self.emit_register_copy(dst, local.register);
                    return Ok(());
                }
                let value = self.eval_u32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value));
                Ok(())
            }
            Expr::Binary { op, lhs, rhs } => match op {
                BinaryOp::Add => {
                    let lhs = self.compile_u32_expr_to_register_or_use(dst, lhs, unsafe_context)?;
                    let rhs = self.compile_u32_expr_to_register_or_use(14, rhs, unsafe_context)?;
                    self.words.push(rux16_asm::add(dst, lhs, rhs));
                    Ok(())
                }
                BinaryOp::Mul => {
                    let multiplier = self.eval_u32_const_value(rhs)?;
                    self.compile_u32_expr_into(dst, lhs, unsafe_context)?;
                    self.emit_mul_by_small_const(dst, multiplier)
                }
                BinaryOp::Sub
                | BinaryOp::Div
                | BinaryOp::Rem
                | BinaryOp::BitAnd
                | BinaryOp::BitOr
                | BinaryOp::BitXor
                | BinaryOp::Shl
                | BinaryOp::Shr => {
                    unsupported("only u32 addition and multiplication by a constant can be lowered")
                }
            },
            Expr::Call { name, args } => self.emit_real_function_call(
                name,
                args,
                Some(TypeName::U32),
                Some(dst),
                unsafe_context,
            ),
            Expr::Path(_) => {
                let value = self.eval_u32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value));
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
                        if *ty != TypeName::U32 {
                            return unsupported(
                                "u32 local initializer requires `mmio<u32>(...).load()`",
                            );
                        }
                        let address = self.compile_mmio_address_to_register_or_use(
                            1,
                            address,
                            unsafe_context,
                        )?;
                        self.words.push(rux16_asm::load32(dst, address));
                        Ok(())
                    }
                    Expr::Ptr { ty, address } => {
                        if *ty != TypeName::U32 {
                            return unsupported(
                                "u32 local initializer requires `ptr<u32>(...).load()`",
                            );
                        }
                        let address =
                            self.compile_u32_expr_to_register_or_use(1, address, unsafe_context)?;
                        self.words.push(rux16_asm::load32(dst, address));
                        Ok(())
                    }
                    _ => unsupported(
                        "only `mmio<T>(...).load()` and `ptr<T>(...).load()` can be lowered",
                    ),
                }
            }
            _ => {
                let value = self.eval_u32_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, value));
                Ok(())
            }
        }
    }

    fn emit_register_copy(&mut self, dst: u8, src: u8) {
        if dst == src {
            return;
        }
        let zero = scratch_register_excluding(src);
        self.words.extend_from_slice(&rux16_asm::const32(zero, 0));
        self.words.push(rux16_asm::add(dst, src, zero));
    }

    fn emit_mul_by_small_const(&mut self, dst: u8, multiplier: u32) -> Result<(), CompileError> {
        if multiplier == 0 {
            self.words.extend_from_slice(&rux16_asm::const32(dst, 0));
            return Ok(());
        }
        if multiplier == 1 {
            return Ok(());
        }
        if multiplier > 16 {
            return unsupported(format!(
                "u32 multiplication by constant `{multiplier}` is too large for Rux16 lowering"
            ));
        }
        let scratch = scratch_register_excluding(dst);
        self.emit_register_copy(scratch, dst);
        for _ in 1..multiplier {
            self.words.push(rux16_asm::add(dst, dst, scratch));
        }
        Ok(())
    }

    fn compile_u8_expr_into(
        &mut self,
        dst: u8,
        expr: &Expr,
        unsafe_context: bool,
    ) -> Result<(), CompileError> {
        match expr {
            Expr::Local(name) => {
                if let Some(local) = self.local(name, TypeName::U8)? {
                    self.emit_register_copy(dst, local.register);
                    return Ok(());
                }
                let value = self.eval_u8_value(expr)?;
                self.words
                    .extend_from_slice(&rux16_asm::const32(dst, u32::from(value)));
                Ok(())
            }
            Expr::Call { name, args } => self.emit_real_function_call(
                name,
                args,
                Some(TypeName::U8),
                Some(dst),
                unsafe_context,
            ),
            Expr::Path(_) => {
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
                        let address = self.compile_mmio_address_to_register_or_use(
                            1,
                            address,
                            unsafe_context,
                        )?;
                        self.words.push(rux16_asm::load8(dst, address));
                        Ok(())
                    }
                    Expr::Ptr { ty, address } => {
                        if *ty != TypeName::U8 {
                            return unsupported(
                                "u8 local initializer requires `ptr<u8>(...).load()`",
                            );
                        }
                        let address = self.compile_ram_address_to_register_or_use(
                            1,
                            address,
                            unsafe_context,
                        )?;
                        self.words.push(rux16_asm::load8(dst, address));
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
        if register >= rux16_asm::SECONDARY_SCRATCH_REGISTER {
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
        self.words
            .extend_from_slice(&rux16_asm::const32(rux16_asm::SCRATCH_REGISTER, 0));
        self.words.push(rux16_asm::jmp(rux16_asm::SCRATCH_REGISTER));
        const_index
    }

    fn emit_absolute_jump(&mut self, address: u32) {
        self.words
            .extend_from_slice(&rux16_asm::const32(rux16_asm::SCRATCH_REGISTER, address));
        self.words.push(rux16_asm::jmp(rux16_asm::SCRATCH_REGISTER));
    }

    fn patch_absolute_jump(
        &mut self,
        const_index: usize,
        address: u32,
    ) -> Result<(), CompileError> {
        self.patch_absolute_const32(const_index, address)
    }

    fn patch_absolute_const32(
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
            Expr::Local(name) => match self.consts.get(name).copied() {
                Some(ConstValue::U32(value)) => Ok(value),
                Some(ConstValue::I32(_)) | Some(ConstValue::U8(_)) => {
                    unsupported(format!("`{name}` is not an address, expected MMIO address"))
                }
                None => unsupported(format!("unknown Rux16 MMIO address `{name}`")),
            },
            Expr::Path(path) => {
                let name = path_name(path);
                match self.consts.get(&name).copied() {
                    Some(ConstValue::U32(value)) => Ok(value),
                    Some(ConstValue::I32(_)) | Some(ConstValue::U8(_)) => {
                        unsupported(format!("`{name}` is not an address, expected MMIO address"))
                    }
                    None => unsupported(format!("unknown Rux16 MMIO address `{name}`")),
                }
            }
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
                    return value.as_u32(name);
                }
                unsupported(format!("unknown Rux16 RAM address `{name}`"))
            }
            Expr::Path(path) => {
                let name = path_name(path);
                if let Some(value) = self.consts.get(&name).copied() {
                    return value.as_u32(&name);
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
                    return value.as_i32(name);
                }
                unsupported(format!("unknown Rux16 i32 value `{name}`"))
            }
            Expr::Path(path) => {
                let name = path_name(path);
                if let Some(value) = self.consts.get(&name).copied() {
                    return value.as_i32(&name);
                }
                unsupported(format!("unknown Rux16 i32 value `{name}`"))
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

    fn eval_u32_value(&self, expr: &Expr) -> Result<u32, CompileError> {
        match expr {
            Expr::Int(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `u32`"),
            }),
            Expr::IntU32(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("u32 literal `{value}` does not fit `u32`"),
            }),
            Expr::IntU8(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("u8 literal `{value}` does not fit `u32`"),
            }),
            Expr::Local(name) => {
                if let Some(value) = self.consts.get(name).copied() {
                    return value.as_u32(name);
                }
                unsupported(format!("unknown Rux16 u32 value `{name}`"))
            }
            Expr::Path(path) => {
                let name = path_name(path);
                if let Some(value) = self.consts.get(&name).copied() {
                    return value.as_u32(&name);
                }
                unsupported(format!("unknown Rux16 u32 value `{name}`"))
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
            | Expr::Compare { .. } => unsupported("only u32 literal values can be lowered"),
        }
    }

    fn eval_u32_const_value(&self, expr: &Expr) -> Result<u32, CompileError> {
        match expr {
            Expr::Int(value) | Expr::IntU32(value) => {
                u32::try_from(*value).map_err(|_| CompileError {
                    message: format!("u32 constant `{value}` does not fit `u32`"),
                })
            }
            Expr::IntU8(value) => u32::try_from(*value).map_err(|_| CompileError {
                message: format!("u8 constant `{value}` does not fit `u32`"),
            }),
            Expr::Local(name) => {
                if let Some(value) = self.consts.get(name).copied() {
                    return value.as_u32(name);
                }
                unsupported(format!("`{name}` is not a compile-time u32 constant"))
            }
            Expr::Path(path) => {
                let name = path_name(path);
                if let Some(value) = self.consts.get(&name).copied() {
                    return value.as_u32(&name);
                }
                unsupported(format!("`{name}` is not a compile-time u32 constant"))
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
            | Expr::Compare { .. } => unsupported("expected a compile-time u32 constant"),
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
                    return value.as_u8(name);
                }
                unsupported(format!("unknown Rux16 u8 value `{name}`"))
            }
            Expr::Path(path) => {
                let name = path_name(path);
                if let Some(value) = self.consts.get(&name).copied() {
                    return value.as_u8(&name);
                }
                unsupported(format!("unknown Rux16 u8 value `{name}`"))
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
        for parameter in &function.parameters {
            if !matches!(parameter.ty, TypeName::I32 | TypeName::U32 | TypeName::U8) {
                return unsupported(
                    "only i32, u32, and u8 function parameters are supported by the Rux16 backend yet",
                );
            }
        }
        if !matches!(
            function.return_type,
            ReturnType::Unit | ReturnType::I32 | ReturnType::U32 | ReturnType::U8
        ) {
            return unsupported(
                "only unit, i32, u32, and u8 function returns are supported by the Rux16 backend yet",
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
    let main = functions.get("main").expect("main exists");
    if !main.parameters.is_empty() || main.return_type != ReturnType::Unit {
        return unsupported(
            "a no-argument `fn main()` with unit return is required by the Rux16 backend",
        );
    }
    Ok(functions)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ConstValue {
    I32(i32),
    U32(u32),
    U8(u8),
}

impl ConstValue {
    fn type_name(self) -> TypeName {
        match self {
            ConstValue::I32(_) => TypeName::I32,
            ConstValue::U32(_) => TypeName::U32,
            ConstValue::U8(_) => TypeName::U8,
        }
    }

    fn as_i32(self, name: &str) -> Result<i32, CompileError> {
        match self {
            ConstValue::I32(value) => Ok(value),
            ConstValue::U8(value) => Ok(i32::from(value)),
            ConstValue::U32(_) => {
                unsupported(format!("`{name}` is an address, expected i32 value"))
            }
        }
    }

    fn as_u32(self, name: &str) -> Result<u32, CompileError> {
        match self {
            ConstValue::U32(value) => Ok(value),
            ConstValue::U8(value) => Ok(u32::from(value)),
            ConstValue::I32(value) => u32::try_from(value).map_err(|_| CompileError {
                message: format!("const `{name}` value `{value}` does not fit `u32`"),
            }),
        }
    }

    fn as_u8(self, name: &str) -> Result<u8, CompileError> {
        match self {
            ConstValue::U8(value) => Ok(value),
            ConstValue::I32(value) => u8::try_from(value).map_err(|_| CompileError {
                message: format!("const `{name}` value `{value}` does not fit `u8`"),
            }),
            ConstValue::U32(_) => unsupported(format!("`{name}` is an address, expected u8 value")),
        }
    }
}

fn evaluate_consts(consts: &[ConstDecl]) -> Result<HashMap<String, ConstValue>, CompileError> {
    let mut values = HashMap::new();
    for declaration in consts {
        if values.contains_key(&declaration.name) {
            return Err(CompileError {
                message: format!("duplicate const `{}`", declaration.name),
            });
        }
        let value = evaluate_const_expr(&declaration.value, declaration.ty, &values)?;
        values.insert(declaration.name.clone(), value);
    }
    Ok(values)
}

fn evaluate_const_expr(
    expr: &Expr,
    ty: TypeName,
    source_consts: &HashMap<String, ConstValue>,
) -> Result<ConstValue, CompileError> {
    match expr {
        Expr::Int(value) => const_from_i64(*value, ty),
        Expr::IntU8(value) => const_from_u8_literal(*value, ty),
        Expr::Local(name) => {
            if let Some(value) = source_consts.get(name).copied() {
                return const_from_const_value(value, ty, name);
            }
            Err(CompileError {
                message: format!("unknown const initializer identifier `{name}`"),
            })
        }
        Expr::Path(path) => {
            let name = path_name(path);
            if let Some(value) = source_consts.get(&name).copied() {
                return const_from_const_value(value, ty, &name);
            }
            Err(CompileError {
                message: format!("unknown const initializer identifier `{name}`"),
            })
        }
        Expr::IntU32(value) => const_from_u32_literal(*value, ty),
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

fn const_from_i64(value: i64, ty: TypeName) -> Result<ConstValue, CompileError> {
    match ty {
        TypeName::I32 => i32::try_from(value)
            .map(ConstValue::I32)
            .map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `i32`"),
            }),
        TypeName::U32 => u32::try_from(value)
            .map(ConstValue::U32)
            .map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `u32`"),
            }),
        TypeName::U8 => u8::try_from(value)
            .map(ConstValue::U8)
            .map_err(|_| CompileError {
                message: format!("integer literal `{value}` does not fit `u8`"),
            }),
        _ => unreachable!("const parser rejects non-numeric const types"),
    }
}

fn const_from_u32_literal(value: i64, ty: TypeName) -> Result<ConstValue, CompileError> {
    let value = u32::try_from(value).map_err(|_| CompileError {
        message: format!("u32 literal `{value}` does not fit `u32`"),
    })?;
    match ty {
        TypeName::U32 => Ok(ConstValue::U32(value)),
        TypeName::I32 => Err(CompileError {
            message: format!("u32 literal `{value}` cannot initialize an i32 const without a cast"),
        }),
        TypeName::U8 => Err(CompileError {
            message: format!("u32 literal `{value}` cannot initialize a u8 const without a cast"),
        }),
        _ => unreachable!("const parser rejects non-numeric const types"),
    }
}

fn const_from_u8_literal(value: i64, ty: TypeName) -> Result<ConstValue, CompileError> {
    let value = u8::try_from(value).map_err(|_| CompileError {
        message: format!("u8 literal `{value}` does not fit `u8`"),
    })?;
    match ty {
        TypeName::I32 => Ok(ConstValue::I32(i32::from(value))),
        TypeName::U32 => Ok(ConstValue::U32(u32::from(value))),
        TypeName::U8 => Ok(ConstValue::U8(value)),
        _ => unreachable!("const parser rejects non-numeric const types"),
    }
}

fn const_from_const_value(
    value: ConstValue,
    ty: TypeName,
    name: &str,
) -> Result<ConstValue, CompileError> {
    match ty {
        TypeName::I32 => value.as_i32(name).map(ConstValue::I32),
        TypeName::U32 => value.as_u32(name).map(ConstValue::U32),
        TypeName::U8 => value.as_u8(name).map(ConstValue::U8),
        _ => unreachable!("const parser rejects non-numeric const types"),
    }
}

fn path_name(path: &[String]) -> String {
    path.join("::")
}

fn scratch_register_excluding(register: u8) -> u8 {
    if register == rux16_asm::SCRATCH_REGISTER {
        rux16_asm::SECONDARY_SCRATCH_REGISTER
    } else {
        rux16_asm::SCRATCH_REGISTER
    }
}

fn first_callee_local_register(parameter_count: usize) -> u8 {
    if parameter_count >= rux16_asm::ARGUMENT_REGISTERS.len() {
        rux16_asm::ARGUMENT_REGISTERS[rux16_asm::ARGUMENT_REGISTERS.len() - 1] + 1
    } else {
        3
    }
}

fn is_call_abi_value_type(ty: TypeName) -> bool {
    matches!(ty, TypeName::I32 | TypeName::U32 | TypeName::U8)
}

fn statements_return_on_all_paths(statements: &[Statement]) -> bool {
    statements
        .iter()
        .any(|statement| statement_returns_on_all_paths(statement))
}

fn statement_returns_on_all_paths(statement: &Statement) -> bool {
    match statement {
        Statement::Return(_) => true,
        Statement::Unsafe(statements) => statements_return_on_all_paths(statements),
        Statement::If {
            then_branch,
            else_branch: Some(else_branch),
            ..
        } => {
            statements_return_on_all_paths(then_branch)
                && statements_return_on_all_paths(else_branch)
        }
        Statement::If {
            else_branch: None, ..
        }
        | Statement::Expr(_)
        | Statement::Let { .. }
        | Statement::Assign { .. }
        | Statement::AssignOp { .. }
        | Statement::IndexAssign { .. }
        | Statement::DerefAssign { .. }
        | Statement::While { .. }
        | Statement::Break
        | Statement::Continue => false,
    }
}

fn return_type_to_type_name(return_type: ReturnType) -> Option<TypeName> {
    match return_type {
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
