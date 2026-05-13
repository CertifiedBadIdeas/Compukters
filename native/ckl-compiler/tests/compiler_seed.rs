use ckl_compiler::{compile, lex, TokenKind};

#[test]
fn compiler_exposes_public_compile_api() {
    let error = compile("").unwrap_err();

    assert!(error.message.contains("expected `fn`"), "{error:?}");
}

#[test]
fn lexer_recognizes_seed_language_tokens() {
    let tokens =
        lex("fn main() -> i32 { unsafe { mmio<i32>(0x1000).store(7 + 3); } return 0; }").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Fn,
            TokenKind::Ident("main".to_string()),
            TokenKind::LeftParen,
            TokenKind::RightParen,
            TokenKind::Arrow,
            TokenKind::I32,
            TokenKind::LeftBrace,
            TokenKind::Unsafe,
            TokenKind::LeftBrace,
            TokenKind::Mmio,
            TokenKind::Less,
            TokenKind::I32,
            TokenKind::Greater,
            TokenKind::LeftParen,
            TokenKind::Int(0x1000),
            TokenKind::RightParen,
            TokenKind::Dot,
            TokenKind::Ident("store".to_string()),
            TokenKind::LeftParen,
            TokenKind::Int(7),
            TokenKind::Plus,
            TokenKind::Int(3),
            TokenKind::RightParen,
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Return,
            TokenKind::Int(0),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Eof,
        ]
    );
}
