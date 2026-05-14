use ckl_vm::computer_abi;
use ckl_vm::low_image::{Function, Image, Instruction};
use std::collections::HashMap;
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Token {
    pub kind: TokenKind,
    pub offset: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TokenKind {
    Fn,
    Return,
    Unsafe,
    Mmio,
    Let,
    Mut,
    Const,
    If,
    Else,
    While,
    I32,
    Ident(String),
    Int(i64),
    Arrow,
    LeftParen,
    RightParen,
    LeftBrace,
    RightBrace,
    Less,
    LessEqual,
    Greater,
    GreaterEqual,
    Equal,
    EqualEqual,
    BangEqual,
    Colon,
    Dot,
    Semicolon,
    Comma,
    Plus,
    Minus,
    Star,
    Slash,
    Eof,
}

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

pub fn lex(source: &str) -> Result<Vec<Token>, CompileError> {
    let bytes = source.as_bytes();
    let mut offset = 0;
    let mut tokens = Vec::new();

    while offset < bytes.len() {
        let byte = bytes[offset];
        if byte.is_ascii_whitespace() {
            offset += 1;
            continue;
        }

        if byte.is_ascii_alphabetic() || byte == b'_' {
            let start = offset;
            offset += 1;
            while offset < bytes.len()
                && (bytes[offset].is_ascii_alphanumeric() || bytes[offset] == b'_')
            {
                offset += 1;
            }
            let text = &source[start..offset];
            let kind = match text {
                "fn" => TokenKind::Fn,
                "return" => TokenKind::Return,
                "unsafe" => TokenKind::Unsafe,
                "mmio" => TokenKind::Mmio,
                "let" => TokenKind::Let,
                "mut" => TokenKind::Mut,
                "const" => TokenKind::Const,
                "if" => TokenKind::If,
                "else" => TokenKind::Else,
                "while" => TokenKind::While,
                "i32" => TokenKind::I32,
                _ => TokenKind::Ident(text.to_string()),
            };
            tokens.push(Token {
                kind,
                offset: start,
            });
            continue;
        }

        if byte.is_ascii_digit() {
            let start = offset;
            let value = if byte == b'0'
                && offset + 1 < bytes.len()
                && matches!(bytes[offset + 1], b'x' | b'X')
            {
                offset += 2;
                let digits_start = offset;
                while offset < bytes.len() && bytes[offset].is_ascii_hexdigit() {
                    offset += 1;
                }
                if digits_start == offset {
                    return Err(CompileError {
                        message: format!("expected hex digits after `0x` at byte {start}"),
                    });
                }
                i64::from_str_radix(&source[digits_start..offset], 16).map_err(|_| {
                    CompileError {
                        message: format!("integer literal is too large at byte {start}"),
                    }
                })?
            } else {
                offset += 1;
                while offset < bytes.len() && bytes[offset].is_ascii_digit() {
                    offset += 1;
                }
                source[start..offset]
                    .parse::<i64>()
                    .map_err(|_| CompileError {
                        message: format!("integer literal is too large at byte {start}"),
                    })?
            };
            tokens.push(Token {
                kind: TokenKind::Int(value),
                offset: start,
            });
            continue;
        }

        let kind = match byte {
            b'(' => TokenKind::LeftParen,
            b')' => TokenKind::RightParen,
            b'{' => TokenKind::LeftBrace,
            b'}' => TokenKind::RightBrace,
            b'<' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::LessEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'<' => TokenKind::Less,
            b'>' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::GreaterEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'>' => TokenKind::Greater,
            b'=' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::EqualEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'=' => TokenKind::Equal,
            b'!' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::BangEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b':' => TokenKind::Colon,
            b'.' => TokenKind::Dot,
            b';' => TokenKind::Semicolon,
            b',' => TokenKind::Comma,
            b'+' => TokenKind::Plus,
            b'*' => TokenKind::Star,
            b'/' => TokenKind::Slash,
            b'-' if offset + 1 < bytes.len() && bytes[offset + 1] == b'>' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::Arrow,
                    offset: offset - 2,
                });
                continue;
            }
            b'-' => TokenKind::Minus,
            _ => {
                return Err(CompileError {
                    message: format!("unexpected character `{}` at byte {offset}", byte as char),
                });
            }
        };
        tokens.push(Token { kind, offset });
        offset += 1;
    }

    tokens.push(Token {
        kind: TokenKind::Eof,
        offset: source.len(),
    });
    Ok(tokens)
}

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = Parser::new(tokens).parse_program()?;
    Codegen::compile(program)
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Program {
    consts: Vec<ConstDecl>,
    functions: Vec<FunctionDecl>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ConstDecl {
    name: String,
    value: Expr,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct FunctionDecl {
    name: String,
    parameters: Vec<Parameter>,
    return_type: ReturnType,
    statements: Vec<Statement>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Parameter {
    name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReturnType {
    Unit,
    I32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Statement {
    Let {
        name: String,
        initializer: Expr,
    },
    Assign {
        name: String,
        value: Expr,
    },
    If {
        condition: Expr,
        then_branch: Vec<Statement>,
        else_branch: Option<Vec<Statement>>,
    },
    While {
        condition: Expr,
        body: Vec<Statement>,
    },
    Return(Option<Expr>),
    Unsafe(Vec<Statement>),
    Expr(Expr),
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Expr {
    Int(i64),
    Local(String),
    Mmio(Box<Expr>),
    MethodCall {
        receiver: Box<Expr>,
        method: String,
        args: Vec<Expr>,
    },
    Binary {
        op: BinaryOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
    Compare {
        op: CompareOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CompareOp {
    Lt,
    Eq,
    Ne,
    Gt,
    Le,
    Ge,
}

struct Parser {
    tokens: Vec<Token>,
    offset: usize,
}

impl Parser {
    fn new(tokens: Vec<Token>) -> Self {
        Self { tokens, offset: 0 }
    }

    fn parse_program(&mut self) -> Result<Program, CompileError> {
        let mut consts = Vec::new();
        let mut functions = Vec::new();
        while self.peek() != &TokenKind::Eof {
            if self.consume(TokenKind::Const) {
                consts.push(self.parse_const_declaration()?);
            } else if self.peek() == &TokenKind::Fn {
                functions.push(self.parse_function()?);
            } else {
                return Err(self.error(format!("expected top-level item, found {:?}", self.peek())));
            }
        }
        self.expect(TokenKind::Eof)?;
        Ok(Program { consts, functions })
    }

    fn parse_const_declaration(&mut self) -> Result<ConstDecl, CompileError> {
        let name = self.take_ident()?;
        self.expect(TokenKind::Colon)?;
        self.expect(TokenKind::I32)?;
        self.expect(TokenKind::Equal)?;
        let value = self.parse_expr()?;
        self.expect(TokenKind::Semicolon)?;
        Ok(ConstDecl { name, value })
    }

    fn parse_function(&mut self) -> Result<FunctionDecl, CompileError> {
        self.expect(TokenKind::Fn)?;
        let name = self.take_ident()?;
        self.expect(TokenKind::LeftParen)?;
        let mut parameters = Vec::new();
        if !self.consume(TokenKind::RightParen) {
            loop {
                let parameter_name = self.take_ident()?;
                self.expect(TokenKind::Colon)?;
                self.expect(TokenKind::I32)?;
                parameters.push(Parameter {
                    name: parameter_name,
                });
                if self.consume(TokenKind::RightParen) {
                    break;
                }
                self.expect(TokenKind::Comma)?;
            }
        }
        let return_type = if self.consume(TokenKind::Arrow) {
            self.expect(TokenKind::I32)?;
            ReturnType::I32
        } else {
            ReturnType::Unit
        };
        let statements = self.parse_block()?;
        Ok(FunctionDecl {
            name,
            parameters,
            return_type,
            statements,
        })
    }

    fn parse_block(&mut self) -> Result<Vec<Statement>, CompileError> {
        self.expect(TokenKind::LeftBrace)?;
        let mut statements = Vec::new();
        while !self.consume(TokenKind::RightBrace) {
            statements.push(self.parse_statement()?);
        }
        Ok(statements)
    }

    fn parse_statement(&mut self) -> Result<Statement, CompileError> {
        if self.consume(TokenKind::Let) {
            self.expect(TokenKind::Mut)?;
            let name = self.take_ident()?;
            self.expect(TokenKind::Colon)?;
            self.expect(TokenKind::I32)?;
            self.expect(TokenKind::Equal)?;
            let initializer = self.parse_expr()?;
            self.expect(TokenKind::Semicolon)?;
            return Ok(Statement::Let { name, initializer });
        }
        if self.consume(TokenKind::If) {
            let condition = self.parse_expr()?;
            let then_branch = self.parse_block()?;
            let else_branch = if self.consume(TokenKind::Else) {
                Some(self.parse_block()?)
            } else {
                None
            };
            return Ok(Statement::If {
                condition,
                then_branch,
                else_branch,
            });
        }
        if self.consume(TokenKind::While) {
            let condition = self.parse_expr()?;
            let body = self.parse_block()?;
            return Ok(Statement::While { condition, body });
        }
        if self.consume(TokenKind::Return) {
            if self.consume(TokenKind::Semicolon) {
                return Ok(Statement::Return(None));
            }
            let expr = self.parse_expr()?;
            self.expect(TokenKind::Semicolon)?;
            return Ok(Statement::Return(Some(expr)));
        }
        if self.consume(TokenKind::Unsafe) {
            return Ok(Statement::Unsafe(self.parse_block()?));
        }
        if let TokenKind::Ident(name) = self.peek().clone() {
            if self.peek_next() == &TokenKind::Equal {
                self.offset += 1;
                self.expect(TokenKind::Equal)?;
                let value = self.parse_expr()?;
                self.expect(TokenKind::Semicolon)?;
                return Ok(Statement::Assign { name, value });
            }
        }

        let expr = self.parse_expr()?;
        self.expect(TokenKind::Semicolon)?;
        Ok(Statement::Expr(expr))
    }

    fn parse_expr(&mut self) -> Result<Expr, CompileError> {
        self.parse_comparison()
    }

    fn parse_comparison(&mut self) -> Result<Expr, CompileError> {
        let lhs = self.parse_add_sub()?;
        let op = if self.consume(TokenKind::Less) {
            Some(CompareOp::Lt)
        } else if self.consume(TokenKind::EqualEqual) {
            Some(CompareOp::Eq)
        } else if self.consume(TokenKind::BangEqual) {
            Some(CompareOp::Ne)
        } else if self.consume(TokenKind::Greater) {
            Some(CompareOp::Gt)
        } else if self.consume(TokenKind::LessEqual) {
            Some(CompareOp::Le)
        } else if self.consume(TokenKind::GreaterEqual) {
            Some(CompareOp::Ge)
        } else {
            None
        };

        if let Some(op) = op {
            let rhs = self.parse_add_sub()?;
            Ok(Expr::Compare {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            })
        } else {
            Ok(lhs)
        }
    }

    fn parse_add_sub(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_mul_div()?;
        loop {
            let op = if self.consume(TokenKind::Plus) {
                BinaryOp::Add
            } else if self.consume(TokenKind::Minus) {
                BinaryOp::Sub
            } else {
                return Ok(expr);
            };
            let rhs = self.parse_mul_div()?;
            expr = Expr::Binary {
                op,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
    }

    fn parse_mul_div(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_postfix()?;
        loop {
            let op = if self.consume(TokenKind::Star) {
                BinaryOp::Mul
            } else if self.consume(TokenKind::Slash) {
                BinaryOp::Div
            } else {
                return Ok(expr);
            };
            let rhs = self.parse_postfix()?;
            expr = Expr::Binary {
                op,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
    }

    fn parse_postfix(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_primary()?;
        while self.consume(TokenKind::Dot) {
            let method = self.take_ident()?;
            self.expect(TokenKind::LeftParen)?;
            let mut args = Vec::new();
            if !self.consume(TokenKind::RightParen) {
                loop {
                    args.push(self.parse_expr()?);
                    if self.consume(TokenKind::RightParen) {
                        break;
                    }
                    self.expect(TokenKind::Comma)?;
                }
            }
            expr = Expr::MethodCall {
                receiver: Box::new(expr),
                method,
                args,
            };
        }
        Ok(expr)
    }

    fn parse_primary(&mut self) -> Result<Expr, CompileError> {
        if let Some(value) = self.take_int() {
            return Ok(Expr::Int(value));
        }
        if let Some(name) = self.take_ident_if_present() {
            return Ok(Expr::Local(name));
        }
        if self.consume(TokenKind::Mmio) {
            self.expect(TokenKind::Less)?;
            self.expect(TokenKind::I32)?;
            self.expect(TokenKind::Greater)?;
            self.expect(TokenKind::LeftParen)?;
            let address = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(Expr::Mmio(Box::new(address)));
        }
        if self.consume(TokenKind::LeftParen) {
            let expr = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(expr);
        }
        Err(self.error(format!("expected expression, found {:?}", self.peek())))
    }

    fn expect(&mut self, expected: TokenKind) -> Result<(), CompileError> {
        if self.consume(expected.clone()) {
            Ok(())
        } else {
            Err(self.error(format!(
                "expected `{}`, found {:?}",
                expected.name(),
                self.peek()
            )))
        }
    }

    fn take_ident(&mut self) -> Result<String, CompileError> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::Ident(value),
                ..
            }) => {
                self.offset += 1;
                Ok(value.clone())
            }
            _ => Err(self.error(format!("expected identifier, found {:?}", self.peek()))),
        }
    }

    fn take_int(&mut self) -> Option<i64> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::Int(value),
                ..
            }) => {
                self.offset += 1;
                Some(*value)
            }
            _ => None,
        }
    }

    fn take_ident_if_present(&mut self) -> Option<String> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::Ident(value),
                ..
            }) => {
                self.offset += 1;
                Some(value.clone())
            }
            _ => None,
        }
    }

    fn consume(&mut self, expected: TokenKind) -> bool {
        if self.peek() == &expected {
            self.offset += 1;
            true
        } else {
            false
        }
    }

    fn peek(&self) -> &TokenKind {
        self.tokens
            .get(self.offset)
            .map(|token| &token.kind)
            .unwrap_or(&TokenKind::Eof)
    }

    fn peek_next(&self) -> &TokenKind {
        self.tokens
            .get(self.offset + 1)
            .map(|token| &token.kind)
            .unwrap_or(&TokenKind::Eof)
    }

    fn error(&self, message: String) -> CompileError {
        let byte_offset = self
            .tokens
            .get(self.offset)
            .map(|token| token.offset)
            .unwrap_or_default();
        CompileError {
            message: format!("{message} at byte {byte_offset}"),
        }
    }
}

impl TokenKind {
    fn name(&self) -> &'static str {
        match self {
            TokenKind::Fn => "fn",
            TokenKind::Return => "return",
            TokenKind::Unsafe => "unsafe",
            TokenKind::Mmio => "mmio",
            TokenKind::Let => "let",
            TokenKind::Mut => "mut",
            TokenKind::Const => "const",
            TokenKind::If => "if",
            TokenKind::Else => "else",
            TokenKind::While => "while",
            TokenKind::I32 => "i32",
            TokenKind::Ident(_) => "identifier",
            TokenKind::Int(_) => "integer",
            TokenKind::Arrow => "->",
            TokenKind::LeftParen => "(",
            TokenKind::RightParen => ")",
            TokenKind::LeftBrace => "{",
            TokenKind::RightBrace => "}",
            TokenKind::Less => "<",
            TokenKind::LessEqual => "<=",
            TokenKind::Greater => ">",
            TokenKind::GreaterEqual => ">=",
            TokenKind::Equal => "=",
            TokenKind::EqualEqual => "==",
            TokenKind::BangEqual => "!=",
            TokenKind::Colon => ":",
            TokenKind::Dot => ".",
            TokenKind::Semicolon => ";",
            TokenKind::Comma => ",",
            TokenKind::Plus => "+",
            TokenKind::Minus => "-",
            TokenKind::Star => "*",
            TokenKind::Slash => "/",
            TokenKind::Eof => "end of file",
        }
    }
}

#[derive(Clone, Copy)]
enum ExprValue {
    I32(u16),
    Addr(u16),
    Unit,
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
            }
            .ok_or_else(|| CompileError {
                message: "const initializer arithmetic overflow".to_string(),
            })
        }
        Expr::Compare { .. } | Expr::Mmio(_) | Expr::MethodCall { .. } => Err(CompileError {
            message: "const initializer is not compile-time evaluable".to_string(),
        }),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ValueType {
    I32,
}

#[derive(Debug, Clone, Copy)]
struct Local {
    register: u16,
    ty: ValueType,
    mutable: bool,
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
}

impl Codegen {
    fn compile(program: Program) -> Result<Image, CompileError> {
        let source_consts = evaluate_consts(&program.consts)?;
        let main = program
            .functions
            .iter()
            .find(|function| function.name == "main")
            .ok_or_else(|| CompileError {
                message: "missing `main` function".to_string(),
            })?;
        if !main.parameters.is_empty() {
            return Err(CompileError {
                message: "`main` cannot have parameters".to_string(),
            });
        }

        let mut codegen = Self {
            instructions: Vec::new(),
            next_register: 0,
            return_type: main.return_type,
            unsafe_depth: 0,
            locals: HashMap::new(),
            source_consts,
        };

        let outcome = codegen.compile_statements(&main.statements)?;
        if outcome == BlockOutcome::FallsThrough {
            match codegen.return_type {
                ReturnType::Unit => codegen.instructions.push(Instruction::ReturnUnit),
                ReturnType::I32 => {
                    return Err(CompileError {
                        message: "missing return in `i32` function".to_string(),
                    });
                }
            }
        }

        Ok(Image {
            language_version: "ckm-seed-0".to_string(),
            memory_size: 64 * 1024,
            rodata: Vec::new(),
            data: Vec::new(),
            bss_size: 0,
            entry_function_index: 0,
            functions: vec![Function {
                name: "main".to_string(),
                register_count: usize::from(codegen.next_register),
                parameters: Vec::new(),
                instructions: codegen.instructions,
            }],
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
            Statement::Let { name, initializer } => {
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
                let src = self.compile_i32_expr(initializer)?;
                self.instructions.push(Instruction::I32Move { dst, src });
                self.locals.insert(
                    name.clone(),
                    Local {
                        register: dst,
                        ty: ValueType::I32,
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
                if local.ty != ValueType::I32 {
                    return Err(CompileError {
                        message: format!("assignment to non-i32 local `{name}`"),
                    });
                }
                let src = self.compile_i32_expr(value)?;
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
                let cond = self.compile_i32_expr(condition)?;
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
                let cond = self.compile_i32_expr(condition)?;
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
            },
            Statement::Return(Some(expr)) => {
                if self.return_type == ReturnType::Unit {
                    return Err(CompileError {
                        message: "unit function cannot return a value".to_string(),
                    });
                }
                let src = self.compile_i32_expr(expr)?;
                self.instructions.push(Instruction::ReturnI32 { src });
                Ok(BlockOutcome::AlwaysReturns)
            }
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
        match self.compile_expr(expr)? {
            ExprValue::I32(register) => Ok(register),
            ExprValue::Addr(_) => Err(CompileError {
                message: "expected `i32`, found address".to_string(),
            }),
            ExprValue::Unit => Err(CompileError {
                message: "expected `i32`, found unit".to_string(),
            }),
        }
    }

    fn compile_addr_expr(&mut self, expr: &Expr) -> Result<u16, CompileError> {
        match expr {
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
                    message: "MMIO address must be an address expression".to_string(),
                }),
                ExprValue::Unit => Err(CompileError {
                    message: "MMIO address cannot be unit".to_string(),
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
            Expr::Local(name) => {
                if let Some(local) = self.locals.get(name) {
                    return match local.ty {
                        ValueType::I32 => Ok(ExprValue::I32(local.register)),
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
            Expr::Mmio(address) => Ok(ExprValue::Addr(self.compile_addr_expr(address)?)),
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

    fn compile_method_call(
        &mut self,
        receiver: &Expr,
        method: &str,
        args: &[Expr],
    ) -> Result<ExprValue, CompileError> {
        if self.unsafe_depth == 0 {
            return Err(CompileError {
                message: "MMIO access requires `unsafe`".to_string(),
            });
        }
        let ExprValue::Addr(addr) = self.compile_expr(receiver)? else {
            return Err(CompileError {
                message: "MMIO method receiver must be an address".to_string(),
            });
        };

        match method {
            "store" => {
                if args.len() != 1 {
                    return Err(CompileError {
                        message: "mmio<i32>.store expects one argument".to_string(),
                    });
                }
                let src = self.compile_i32_expr(&args[0])?;
                self.instructions.push(Instruction::Store32 { addr, src });
                Ok(ExprValue::Unit)
            }
            "load" => {
                if !args.is_empty() {
                    return Err(CompileError {
                        message: "mmio<i32>.load expects no arguments".to_string(),
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
                Ok(ExprValue::I32(dst))
            }
            CompareOp::Eq => {
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::I32Eq { dst, lhs, rhs });
                Ok(ExprValue::I32(dst))
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
                Ok(ExprValue::I32(dst))
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
        Ok(ExprValue::I32(dst))
    }
}
