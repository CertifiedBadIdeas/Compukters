use ckl_vm::low_image::{Function, Image, Instruction};
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
    return_type: ReturnType,
    statements: Vec<Statement>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReturnType {
    Unit,
    I32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Statement {
    Return(Option<Expr>),
    Unsafe(Vec<Statement>),
    Expr(Expr),
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Expr {
    Int(i64),
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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
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
        self.expect(TokenKind::Fn)?;
        self.expect_ident("main")?;
        self.expect(TokenKind::LeftParen)?;
        self.expect(TokenKind::RightParen)?;
        let return_type = if self.consume(TokenKind::Arrow) {
            self.expect(TokenKind::I32)?;
            ReturnType::I32
        } else {
            ReturnType::Unit
        };
        let statements = self.parse_block()?;
        self.expect(TokenKind::Eof)?;
        Ok(Program {
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

        let expr = self.parse_expr()?;
        self.expect(TokenKind::Semicolon)?;
        Ok(Statement::Expr(expr))
    }

    fn parse_expr(&mut self) -> Result<Expr, CompileError> {
        self.parse_add_sub()
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

    fn expect_ident(&mut self, expected: &str) -> Result<(), CompileError> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::Ident(value),
                ..
            }) if value == expected => {
                self.offset += 1;
                Ok(())
            }
            _ => Err(self.error(format!("expected `{expected}`, found {:?}", self.peek()))),
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

struct Codegen {
    instructions: Vec<Instruction>,
    next_register: u16,
    return_type: ReturnType,
    saw_return: bool,
    unsafe_depth: usize,
}

impl Codegen {
    fn compile(program: Program) -> Result<Image, CompileError> {
        let mut codegen = Self {
            instructions: Vec::new(),
            next_register: 0,
            return_type: program.return_type,
            saw_return: false,
            unsafe_depth: 0,
        };

        codegen.compile_statements(&program.statements)?;
        if !codegen.saw_return {
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

    fn compile_statements(&mut self, statements: &[Statement]) -> Result<(), CompileError> {
        for statement in statements {
            if self.saw_return {
                return Err(CompileError {
                    message: "unreachable statement after return".to_string(),
                });
            }
            self.compile_statement(statement)?;
        }
        Ok(())
    }

    fn compile_statement(&mut self, statement: &Statement) -> Result<(), CompileError> {
        match statement {
            Statement::Return(None) => match self.return_type {
                ReturnType::Unit => {
                    self.instructions.push(Instruction::ReturnUnit);
                    self.saw_return = true;
                    Ok(())
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
                self.saw_return = true;
                Ok(())
            }
            Statement::Unsafe(statements) => {
                self.unsafe_depth += 1;
                let result = self.compile_statements(statements);
                self.unsafe_depth -= 1;
                result
            }
            Statement::Expr(expr) => {
                self.compile_expr(expr)?;
                Ok(())
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
                let dst = self.alloc_register()?;
                self.instructions.push(Instruction::I32Const { dst, value });
                Ok(ExprValue::I32(dst))
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
}
