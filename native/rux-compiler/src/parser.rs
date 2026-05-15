use crate::ast::*;
use crate::error::CompileError;
use crate::lexer::{Token, TokenKind};

pub(crate) fn parse(tokens: Vec<Token>) -> Result<Program, CompileError> {
    Parser::new(tokens).parse_program()
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
        let mut uses = Vec::new();
        let mut consts = Vec::new();
        let mut functions = Vec::new();
        while self.peek() != &TokenKind::Eof {
            if self.consume(TokenKind::Use) {
                uses.extend(self.parse_use_declaration()?);
            } else if self.consume(TokenKind::Const) {
                consts.push(self.parse_const_declaration()?);
            } else if self.peek() == &TokenKind::Fn || self.peek() == &TokenKind::Pub {
                functions.push(self.parse_function()?);
            } else {
                return Err(self.error(format!("expected top-level item, found {:?}", self.peek())));
            }
        }
        self.expect(TokenKind::Eof)?;
        Ok(Program {
            uses,
            consts,
            functions,
        })
    }

    fn parse_use_declaration(&mut self) -> Result<Vec<UseDecl>, CompileError> {
        let mut path = vec![self.take_ident()?];
        while self.consume(TokenKind::DoubleColon) {
            if self.consume(TokenKind::LeftBrace) {
                let mut declarations = Vec::new();
                loop {
                    let mut item_path = path.clone();
                    item_path.push(self.take_ident()?);
                    declarations.push(UseDecl { path: item_path });
                    if self.consume(TokenKind::RightBrace) {
                        break;
                    }
                    self.expect(TokenKind::Comma)?;
                }
                self.expect(TokenKind::Semicolon)?;
                return Ok(declarations);
            }
            path.push(self.take_ident()?);
        }
        self.expect(TokenKind::Semicolon)?;
        Ok(vec![UseDecl { path }])
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
        let visibility = if self.consume(TokenKind::Pub) {
            Visibility::Public
        } else {
            Visibility::Private
        };
        self.expect(TokenKind::Fn)?;
        let name = self.take_ident()?;
        self.expect(TokenKind::LeftParen)?;
        let mut parameters = Vec::new();
        if !self.consume(TokenKind::RightParen) {
            loop {
                let parameter_name = self.take_ident()?;
                self.expect(TokenKind::Colon)?;
                let ty = self.parse_type()?;
                parameters.push(Parameter {
                    name: parameter_name,
                    ty,
                });
                if self.consume(TokenKind::RightParen) {
                    break;
                }
                self.expect(TokenKind::Comma)?;
            }
        }
        let return_type = if self.consume(TokenKind::Arrow) {
            match self.parse_type()? {
                TypeName::I32 => ReturnType::I32,
                TypeName::U32 => ReturnType::U32,
                TypeName::U8 => ReturnType::U8,
                TypeName::Bool => ReturnType::Bool,
                TypeName::PtrI32 => ReturnType::PtrI32,
                TypeName::PtrU32 => ReturnType::PtrU32,
                TypeName::PtrU8 => ReturnType::PtrU8,
                TypeName::RefMutU32 => {
                    return Err(
                        self.error("reference return types are not supported yet".to_string())
                    )
                }
            }
        } else {
            ReturnType::Unit
        };
        let statements = self.parse_block()?;
        Ok(FunctionDecl {
            visibility,
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
            let ty = self.parse_type()?;
            self.expect(TokenKind::Equal)?;
            let initializer = self.parse_expr()?;
            self.expect(TokenKind::Semicolon)?;
            return Ok(Statement::Let {
                name,
                ty,
                initializer,
            });
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
        if self.consume(TokenKind::Break) {
            self.expect(TokenKind::Semicolon)?;
            return Ok(Statement::Break);
        }
        if self.consume(TokenKind::Continue) {
            self.expect(TokenKind::Semicolon)?;
            return Ok(Statement::Continue);
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
            if let Some(op) = self.compound_assignment_op(self.peek_next()) {
                self.offset += 2;
                let value = self.parse_expr()?;
                self.expect(TokenKind::Semicolon)?;
                return Ok(Statement::AssignOp { name, op, value });
            }
            if self.peek_next() == &TokenKind::Equal {
                self.offset += 1;
                self.expect(TokenKind::Equal)?;
                let value = self.parse_expr()?;
                self.expect(TokenKind::Semicolon)?;
                return Ok(Statement::Assign { name, value });
            }
        }

        let expr = self.parse_expr()?;
        if self.consume(TokenKind::Equal) {
            let value = self.parse_expr()?;
            self.expect(TokenKind::Semicolon)?;
            return match expr {
                Expr::Index { target, index } => Ok(Statement::IndexAssign {
                    target: *target,
                    index: *index,
                    value,
                }),
                Expr::Deref(target) => Ok(Statement::DerefAssign {
                    target: *target,
                    value,
                }),
                _ => Err(self
                    .error("assignment target must be a local, index, or dereference".to_string())),
            };
        }
        self.expect(TokenKind::Semicolon)?;
        Ok(Statement::Expr(expr))
    }

    fn parse_expr(&mut self) -> Result<Expr, CompileError> {
        self.parse_cast()
    }

    fn parse_cast(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_logical_or()?;
        while self.consume(TokenKind::As) {
            let target = self.parse_type()?;
            expr = Expr::Cast {
                expr: Box::new(expr),
                target,
            };
        }
        Ok(expr)
    }

    fn parse_logical_or(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_logical_and()?;
        while self.consume(TokenKind::OrOr) {
            let rhs = self.parse_logical_and()?;
            expr = Expr::Logical {
                op: LogicalOp::Or,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
        Ok(expr)
    }

    fn parse_logical_and(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_comparison()?;
        while self.consume(TokenKind::AndAnd) {
            let rhs = self.parse_comparison()?;
            expr = Expr::Logical {
                op: LogicalOp::And,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
        Ok(expr)
    }

    fn parse_comparison(&mut self) -> Result<Expr, CompileError> {
        let lhs = self.parse_bit_or()?;
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
            let rhs = self.parse_bit_or()?;
            Ok(Expr::Compare {
                op,
                lhs: Box::new(lhs),
                rhs: Box::new(rhs),
            })
        } else {
            Ok(lhs)
        }
    }

    fn parse_bit_or(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_bit_xor()?;
        while self.consume(TokenKind::Pipe) {
            let rhs = self.parse_bit_xor()?;
            expr = Expr::Binary {
                op: BinaryOp::BitOr,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
        Ok(expr)
    }

    fn parse_bit_xor(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_bit_and()?;
        while self.consume(TokenKind::Caret) {
            let rhs = self.parse_bit_and()?;
            expr = Expr::Binary {
                op: BinaryOp::BitXor,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
        Ok(expr)
    }

    fn parse_bit_and(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_shift()?;
        while self.consume(TokenKind::Ampersand) {
            let rhs = self.parse_shift()?;
            expr = Expr::Binary {
                op: BinaryOp::BitAnd,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
        Ok(expr)
    }

    fn parse_shift(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_add_sub()?;
        loop {
            let op = if self.consume(TokenKind::Shl) {
                BinaryOp::Shl
            } else if self.consume(TokenKind::Shr) {
                BinaryOp::Shr
            } else {
                return Ok(expr);
            };
            let rhs = self.parse_add_sub()?;
            expr = Expr::Binary {
                op,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
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
        let mut expr = self.parse_unary()?;
        loop {
            let op = if self.consume(TokenKind::Star) {
                BinaryOp::Mul
            } else if self.consume(TokenKind::Slash) {
                BinaryOp::Div
            } else {
                return Ok(expr);
            };
            let rhs = self.parse_unary()?;
            expr = Expr::Binary {
                op,
                lhs: Box::new(expr),
                rhs: Box::new(rhs),
            };
        }
    }

    fn parse_unary(&mut self) -> Result<Expr, CompileError> {
        if self.consume(TokenKind::Bang) {
            let expr = self.parse_unary()?;
            return Ok(Expr::Unary {
                op: UnaryOp::Not,
                expr: Box::new(expr),
            });
        }
        if self.consume(TokenKind::Minus) {
            let expr = self.parse_unary()?;
            return Ok(Expr::Unary {
                op: UnaryOp::Neg,
                expr: Box::new(expr),
            });
        }
        if self.consume(TokenKind::Ampersand) {
            self.expect(TokenKind::Mut)?;
            let expr = self.parse_unary()?;
            return Ok(Expr::AddressOfMut(Box::new(expr)));
        }
        if self.consume(TokenKind::Star) {
            let expr = self.parse_unary()?;
            return Ok(Expr::Deref(Box::new(expr)));
        }
        self.parse_postfix()
    }

    fn parse_postfix(&mut self) -> Result<Expr, CompileError> {
        let mut expr = self.parse_primary()?;
        loop {
            if self.consume(TokenKind::Dot) {
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
                continue;
            }
            if self.consume(TokenKind::LeftBracket) {
                let index = self.parse_expr()?;
                self.expect(TokenKind::RightBracket)?;
                expr = Expr::Index {
                    target: Box::new(expr),
                    index: Box::new(index),
                };
                continue;
            }
            return Ok(expr);
        }
    }

    fn parse_primary(&mut self) -> Result<Expr, CompileError> {
        if let Some(value) = self.take_int() {
            return Ok(Expr::Int(value));
        }
        if let Some(value) = self.take_int_u32() {
            return Ok(Expr::IntU32(value));
        }
        if let Some(value) = self.take_int_u8() {
            return Ok(Expr::IntU8(value));
        }
        if let Some(value) = self.take_byte_string() {
            return Ok(Expr::ByteString(value));
        }
        if self.consume(TokenKind::True) {
            return Ok(Expr::Bool(true));
        }
        if self.consume(TokenKind::False) {
            return Ok(Expr::Bool(false));
        }
        if let TokenKind::Ident(name) = self.peek().clone() {
            if self.peek_next() == &TokenKind::LeftParen {
                self.offset += 1;
                self.expect(TokenKind::LeftParen)?;
                let args = self.parse_argument_list()?;
                return Ok(Expr::Call { name, args });
            }
        }
        if let Some(name) = self.take_ident_if_present() {
            return Ok(Expr::Local(name));
        }
        if self.consume(TokenKind::Mmio) {
            self.expect(TokenKind::Less)?;
            let ty = self.parse_type()?;
            self.expect(TokenKind::Greater)?;
            self.expect(TokenKind::LeftParen)?;
            let address = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(Expr::Mmio {
                ty,
                address: Box::new(address),
            });
        }
        if self.consume(TokenKind::Ptr) {
            self.expect(TokenKind::Less)?;
            let ty = self.parse_type()?;
            self.expect(TokenKind::Greater)?;
            self.expect(TokenKind::LeftParen)?;
            let address = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(Expr::Ptr {
                ty,
                address: Box::new(address),
            });
        }
        if self.consume(TokenKind::LeftParen) {
            let expr = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(expr);
        }
        Err(self.error(format!("expected expression, found {:?}", self.peek())))
    }

    fn parse_type(&mut self) -> Result<TypeName, CompileError> {
        if self.consume(TokenKind::Ampersand) {
            self.expect(TokenKind::Mut)?;
            let element_type = self.parse_type()?;
            return match element_type {
                TypeName::U32 => Ok(TypeName::RefMutU32),
                _ => Err(self.error("only `&mut u32` references are supported yet".to_string())),
            };
        }
        if self.consume(TokenKind::Ptr) {
            self.expect(TokenKind::Less)?;
            let element_type = self.parse_type()?;
            self.expect(TokenKind::Greater)?;
            return match element_type {
                TypeName::I32 => Ok(TypeName::PtrI32),
                TypeName::U32 => Ok(TypeName::PtrU32),
                TypeName::U8 => Ok(TypeName::PtrU8),
                TypeName::Bool => {
                    Err(self
                        .error("pointer element type must be `i32`, `u32`, or `u8`".to_string()))
                }
                TypeName::PtrI32 | TypeName::PtrU32 | TypeName::PtrU8 => {
                    Err(self.error("nested pointer types are not supported yet".to_string()))
                }
                TypeName::RefMutU32 => {
                    Err(self.error("pointers to references are not supported yet".to_string()))
                }
            };
        }
        if self.consume(TokenKind::I32) {
            return Ok(TypeName::I32);
        }
        if self.consume(TokenKind::U32) {
            return Ok(TypeName::U32);
        }
        if self.consume(TokenKind::U8) {
            return Ok(TypeName::U8);
        }
        if self.consume(TokenKind::Bool) {
            return Ok(TypeName::Bool);
        }
        Err(self.error(format!("expected type, found {:?}", self.peek())))
    }

    fn parse_argument_list(&mut self) -> Result<Vec<Expr>, CompileError> {
        let mut args = Vec::new();
        if self.consume(TokenKind::RightParen) {
            return Ok(args);
        }
        loop {
            args.push(self.parse_expr()?);
            if self.consume(TokenKind::RightParen) {
                return Ok(args);
            }
            self.expect(TokenKind::Comma)?;
        }
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

    fn take_int_u32(&mut self) -> Option<i64> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::IntU32(value),
                ..
            }) => {
                self.offset += 1;
                Some(*value)
            }
            _ => None,
        }
    }

    fn take_int_u8(&mut self) -> Option<i64> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::IntU8(value),
                ..
            }) => {
                self.offset += 1;
                Some(*value)
            }
            _ => None,
        }
    }

    fn take_byte_string(&mut self) -> Option<Vec<u8>> {
        match self.tokens.get(self.offset) {
            Some(Token {
                kind: TokenKind::ByteString(value),
                ..
            }) => {
                self.offset += 1;
                Some(value.clone())
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

    fn compound_assignment_op(&self, token: &TokenKind) -> Option<BinaryOp> {
        match token {
            TokenKind::PlusEqual => Some(BinaryOp::Add),
            TokenKind::MinusEqual => Some(BinaryOp::Sub),
            TokenKind::StarEqual => Some(BinaryOp::Mul),
            TokenKind::SlashEqual => Some(BinaryOp::Div),
            TokenKind::AmpersandEqual => Some(BinaryOp::BitAnd),
            TokenKind::PipeEqual => Some(BinaryOp::BitOr),
            TokenKind::CaretEqual => Some(BinaryOp::BitXor),
            TokenKind::ShlEqual => Some(BinaryOp::Shl),
            TokenKind::ShrEqual => Some(BinaryOp::Shr),
            _ => None,
        }
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
