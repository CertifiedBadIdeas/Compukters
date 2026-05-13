use ckl_compiler::{compile, lex, TokenKind};
use ckl_vm::low_image::Instruction;

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

#[test]
fn compile_lowers_i32_main_return_arithmetic() {
    let image = compile("fn main() -> i32 { return 7 + 3 * 2; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(image.language_version, "ckm-seed-0");
    assert_eq!(image.entry_function_index, 0);
    assert_eq!(function.name, "main");
    assert_eq!(function.register_count, 5);
    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 0, value: 7 },
            Instruction::I32Const { dst: 1, value: 3 },
            Instruction::I32Const { dst: 2, value: 2 },
            Instruction::I32Mul {
                dst: 3,
                lhs: 1,
                rhs: 2
            },
            Instruction::I32Add {
                dst: 4,
                lhs: 0,
                rhs: 3
            },
            Instruction::ReturnI32 { src: 4 },
        ]
    );
}

#[test]
fn compile_lowers_unit_main_with_implicit_return() {
    let image = compile("fn main() { }").unwrap();
    let function = &image.functions[0];

    assert_eq!(function.register_count, 0);
    assert_eq!(function.instructions, vec![Instruction::ReturnUnit]);
}

#[test]
fn compile_lowers_unsafe_mmio_store() {
    let image = compile("fn main() { unsafe { mmio<i32>(0x1000).store(42); } }").unwrap();
    let function = &image.functions[0];

    assert_eq!(function.register_count, 2);
    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: 0x1000
            },
            Instruction::I32Const { dst: 1, value: 42 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_unsafe_mmio_load_return() {
    let image =
        compile("fn main() -> i32 { unsafe { return mmio<i32>(0x1000).load(); } }").unwrap();
    let function = &image.functions[0];

    assert_eq!(function.register_count, 2);
    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: 0x1000
            },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::ReturnI32 { src: 1 },
        ]
    );
}

#[test]
fn compile_rejects_void_return_type() {
    let error = compile("fn main() -> void { }").unwrap_err();

    assert!(error.message.contains("expected `i32`"), "{error:?}");
}

#[test]
fn compile_rejects_mmio_outside_unsafe() {
    let error = compile("fn main() { mmio<i32>(0x1000).store(1); }").unwrap_err();

    assert!(
        error.message.contains("MMIO access requires `unsafe`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_missing_i32_return() {
    let error = compile("fn main() -> i32 { }").unwrap_err();

    assert!(
        error.message.contains("missing return in `i32` function"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_empty_return_in_i32_function() {
    let error = compile("fn main() -> i32 { return; }").unwrap_err();

    assert!(error.message.contains("cannot use `return;`"), "{error:?}");
}
