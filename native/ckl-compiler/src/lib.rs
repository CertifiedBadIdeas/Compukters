use ckl_vm::low_image::Image;
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
    I32,
    Ident(String),
    Int(i64),
    Arrow,
    LeftParen,
    RightParen,
    LeftBrace,
    RightBrace,
    Less,
    Greater,
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
            b'<' => TokenKind::Less,
            b'>' => TokenKind::Greater,
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
    let trimmed = source.trim_start();
    if !trimmed.starts_with("fn") {
        return Err(CompileError {
            message: "expected `fn`".to_string(),
        });
    }
    Err(CompileError {
        message: "compiler seed is not implemented yet".to_string(),
    })
}
