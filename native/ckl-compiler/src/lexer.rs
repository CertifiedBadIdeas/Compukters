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
    Break,
    Continue,
    I32,
    U32,
    U8,
    Bool,
    True,
    False,
    As,
    Ident(String),
    Int(i64),
    IntU32(i64),
    IntU8(i64),
    ByteString(Vec<u8>),
    Arrow,
    LeftParen,
    RightParen,
    LeftBrace,
    RightBrace,
    LeftBracket,
    RightBracket,
    Less,
    LessEqual,
    Greater,
    GreaterEqual,
    Equal,
    EqualEqual,
    Bang,
    BangEqual,
    Colon,
    Dot,
    Semicolon,
    Comma,
    Plus,
    PlusEqual,
    Minus,
    MinusEqual,
    Star,
    StarEqual,
    Slash,
    SlashEqual,
    Ampersand,
    AmpersandEqual,
    AndAnd,
    Pipe,
    PipeEqual,
    OrOr,
    Caret,
    CaretEqual,
    Shl,
    ShlEqual,
    Shr,
    ShrEqual,
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

        if byte == b'b' && offset + 1 < bytes.len() && bytes[offset + 1] == b'"' {
            let start = offset;
            offset += 2;
            let mut value = Vec::new();
            while offset < bytes.len() {
                match bytes[offset] {
                    b'"' => {
                        offset += 1;
                        tokens.push(Token {
                            kind: TokenKind::ByteString(value),
                            offset: start,
                        });
                        break;
                    }
                    b'\\' => {
                        offset += 1;
                        if offset >= bytes.len() {
                            return Err(CompileError {
                                message: format!("unterminated byte string escape at byte {start}"),
                            });
                        }
                        let escaped = match bytes[offset] {
                            b'n' => b'\n',
                            b'r' => b'\r',
                            b't' => b'\t',
                            b'0' => 0,
                            b'\\' => b'\\',
                            b'"' => b'"',
                            other => {
                                return Err(CompileError {
                                    message: format!(
                                        "unsupported byte string escape `\\{}` at byte {}",
                                        other as char, offset
                                    ),
                                });
                            }
                        };
                        value.push(escaped);
                        offset += 1;
                    }
                    byte if byte.is_ascii() => {
                        value.push(byte);
                        offset += 1;
                    }
                    _ => {
                        return Err(CompileError {
                            message: format!(
                                "byte string contains non-ascii byte at byte {offset}"
                            ),
                        });
                    }
                }
            }
            if matches!(
                tokens.last(),
                Some(Token {
                    kind: TokenKind::ByteString(_),
                    offset: token_offset
                }) if *token_offset == start
            ) {
                continue;
            }
            return Err(CompileError {
                message: format!("unterminated byte string at byte {start}"),
            });
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
                "break" => TokenKind::Break,
                "continue" => TokenKind::Continue,
                "i32" => TokenKind::I32,
                "u32" => TokenKind::U32,
                "u8" => TokenKind::U8,
                "bool" => TokenKind::Bool,
                "true" => TokenKind::True,
                "false" => TokenKind::False,
                "as" => TokenKind::As,
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
            let suffix_start = offset;
            let has_u32_suffix = has_integer_suffix(source, suffix_start, "u32");
            let has_u8_suffix = has_integer_suffix(source, suffix_start, "u8");
            if has_u32_suffix {
                offset += 3;
            } else if has_u8_suffix {
                offset += 2;
            }
            tokens.push(Token {
                kind: if has_u32_suffix {
                    TokenKind::IntU32(value)
                } else if has_u8_suffix {
                    TokenKind::IntU8(value)
                } else {
                    TokenKind::Int(value)
                },
                offset: start,
            });
            continue;
        }

        let kind = match byte {
            b'(' => TokenKind::LeftParen,
            b')' => TokenKind::RightParen,
            b'{' => TokenKind::LeftBrace,
            b'}' => TokenKind::RightBrace,
            b'[' => TokenKind::LeftBracket,
            b']' => TokenKind::RightBracket,
            b'<' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::LessEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'<' if offset + 2 < bytes.len()
                && bytes[offset + 1] == b'<'
                && bytes[offset + 2] == b'=' =>
            {
                offset += 3;
                tokens.push(Token {
                    kind: TokenKind::ShlEqual,
                    offset: offset - 3,
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
            b'>' if offset + 2 < bytes.len()
                && bytes[offset + 1] == b'>'
                && bytes[offset + 2] == b'=' =>
            {
                offset += 3;
                tokens.push(Token {
                    kind: TokenKind::ShrEqual,
                    offset: offset - 3,
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
            b'!' => TokenKind::Bang,
            b':' => TokenKind::Colon,
            b'.' => TokenKind::Dot,
            b';' => TokenKind::Semicolon,
            b',' => TokenKind::Comma,
            b'+' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::PlusEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'+' => TokenKind::Plus,
            b'*' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::StarEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'*' => TokenKind::Star,
            b'/' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::SlashEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'/' => TokenKind::Slash,
            b'&' if offset + 1 < bytes.len() && bytes[offset + 1] == b'&' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::AndAnd,
                    offset: offset - 2,
                });
                continue;
            }
            b'&' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::AmpersandEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'&' => TokenKind::Ampersand,
            b'|' if offset + 1 < bytes.len() && bytes[offset + 1] == b'|' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::OrOr,
                    offset: offset - 2,
                });
                continue;
            }
            b'|' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::PipeEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'|' => TokenKind::Pipe,
            b'^' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::CaretEqual,
                    offset: offset - 2,
                });
                continue;
            }
            b'^' => TokenKind::Caret,
            b'-' if offset + 1 < bytes.len() && bytes[offset + 1] == b'>' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::Arrow,
                    offset: offset - 2,
                });
                continue;
            }
            b'-' if offset + 1 < bytes.len() && bytes[offset + 1] == b'=' => {
                offset += 2;
                tokens.push(Token {
                    kind: TokenKind::MinusEqual,
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

fn has_integer_suffix(source: &str, offset: usize, suffix: &str) -> bool {
    source[offset..].starts_with(suffix)
        && source[offset + suffix.len()..]
            .as_bytes()
            .first()
            .is_none_or(|byte| !byte.is_ascii_alphanumeric() && *byte != b'_')
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
            TokenKind::Break => "break",
            TokenKind::Continue => "continue",
            TokenKind::I32 => "i32",
            TokenKind::U32 => "u32",
            TokenKind::U8 => "u8",
            TokenKind::Bool => "bool",
            TokenKind::True => "true",
            TokenKind::False => "false",
            TokenKind::As => "as",
            TokenKind::Ident(_) => "identifier",
            TokenKind::Int(_) => "integer",
            TokenKind::IntU32(_) => "u32 integer",
            TokenKind::IntU8(_) => "u8 integer",
            TokenKind::ByteString(_) => "byte string",
            TokenKind::Arrow => "->",
            TokenKind::LeftParen => "(",
            TokenKind::RightParen => ")",
            TokenKind::LeftBrace => "{",
            TokenKind::RightBrace => "}",
            TokenKind::LeftBracket => "[",
            TokenKind::RightBracket => "]",
            TokenKind::Less => "<",
            TokenKind::LessEqual => "<=",
            TokenKind::Greater => ">",
            TokenKind::GreaterEqual => ">=",
            TokenKind::Equal => "=",
            TokenKind::EqualEqual => "==",
            TokenKind::Bang => "!",
            TokenKind::BangEqual => "!=",
            TokenKind::Colon => ":",
            TokenKind::Dot => ".",
            TokenKind::Semicolon => ";",
            TokenKind::Comma => ",",
            TokenKind::Plus => "+",
            TokenKind::PlusEqual => "+=",
            TokenKind::Minus => "-",
            TokenKind::MinusEqual => "-=",
            TokenKind::Star => "*",
            TokenKind::StarEqual => "*=",
            TokenKind::Slash => "/",
            TokenKind::SlashEqual => "/=",
            TokenKind::Ampersand => "&",
            TokenKind::AmpersandEqual => "&=",
            TokenKind::AndAnd => "&&",
            TokenKind::Pipe => "|",
            TokenKind::PipeEqual => "|=",
            TokenKind::OrOr => "||",
            TokenKind::Caret => "^",
            TokenKind::CaretEqual => "^=",
            TokenKind::Shl => "<<",
            TokenKind::ShlEqual => "<<=",
            TokenKind::Shr => ">>",
            TokenKind::ShrEqual => ">>=",
            TokenKind::Eof => "end of file",
        }
    }
}
