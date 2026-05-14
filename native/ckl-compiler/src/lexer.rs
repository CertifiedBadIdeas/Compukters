use crate::error::CompileError;

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
    Ptr,
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
    Ampersand,
    Pipe,
    Caret,
    Shl,
    Shr,
    Eof,
}

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

        if byte == b'/' && offset + 1 < bytes.len() && bytes[offset + 1] == b'/' {
            offset += 2;
            while offset < bytes.len() && !matches!(bytes[offset], b'\n' | b'\r') {
                offset += 1;
            }
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
                "ptr" => TokenKind::Ptr,
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
            b'<' if offset + 1 < bytes.len() && bytes[offset + 1] == b'<' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::Shl,
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
            b'>' if offset + 1 < bytes.len() && bytes[offset + 1] == b'>' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::Shr,
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
            b'&' => TokenKind::Ampersand,
            b'|' => TokenKind::Pipe,
            b'^' => TokenKind::Caret,
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

impl TokenKind {
    pub(crate) fn name(&self) -> &'static str {
        match self {
            TokenKind::Fn => "fn",
            TokenKind::Return => "return",
            TokenKind::Unsafe => "unsafe",
            TokenKind::Mmio => "mmio",
            TokenKind::Ptr => "ptr",
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
            TokenKind::Ampersand => "&",
            TokenKind::Pipe => "|",
            TokenKind::Caret => "^",
            TokenKind::Shl => "<<",
            TokenKind::Shr => ">>",
            TokenKind::Eof => "end of file",
        }
    }
}
