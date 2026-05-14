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
                TypeName::Bool => ReturnType::Bool,
            }
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
            self.expect(TokenKind::I32)?;
            self.expect(TokenKind::Greater)?;
            self.expect(TokenKind::LeftParen)?;
            let address = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(Expr::Mmio(Box::new(address)));
        }
        if self.consume(TokenKind::Ptr) {
            self.expect(TokenKind::Less)?;
            self.expect(TokenKind::I32)?;
            self.expect(TokenKind::Greater)?;
            self.expect(TokenKind::LeftParen)?;
            let address = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(Expr::Ptr(Box::new(address)));
        }
        if self.consume(TokenKind::LeftParen) {
            let expr = self.parse_expr()?;
            self.expect(TokenKind::RightParen)?;
            return Ok(expr);
        }
        Err(self.error(format!("expected expression, found {:?}", self.peek())))
    }

    fn parse_type(&mut self) -> Result<TypeName, CompileError> {
        if self.consume(TokenKind::I32) {
            return Ok(TypeName::I32);
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
