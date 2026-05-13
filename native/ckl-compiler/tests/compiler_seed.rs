use ckl_compiler::{compile, lex, TokenKind};
use ckl_vm::computer_machine::ComputerMachine;
use ckl_vm::low_image::Instruction;
use ckl_vm::low_image_runner::LowImageSignal;

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
fn lexer_recognizes_locals_control_flow_and_comparison_tokens() {
    let tokens =
        lex("let mut i: i32 = 0; while i <= 3 { if i != 2 { i = i + 1; } else { i = i + 1; } }")
            .unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Let,
            TokenKind::Mut,
            TokenKind::Ident("i".to_string()),
            TokenKind::Colon,
            TokenKind::I32,
            TokenKind::Equal,
            TokenKind::Int(0),
            TokenKind::Semicolon,
            TokenKind::While,
            TokenKind::Ident("i".to_string()),
            TokenKind::LessEqual,
            TokenKind::Int(3),
            TokenKind::LeftBrace,
            TokenKind::If,
            TokenKind::Ident("i".to_string()),
            TokenKind::BangEqual,
            TokenKind::Int(2),
            TokenKind::LeftBrace,
            TokenKind::Ident("i".to_string()),
            TokenKind::Equal,
            TokenKind::Ident("i".to_string()),
            TokenKind::Plus,
            TokenKind::Int(1),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Else,
            TokenKind::LeftBrace,
            TokenKind::Ident("i".to_string()),
            TokenKind::Equal,
            TokenKind::Ident("i".to_string()),
            TokenKind::Plus,
            TokenKind::Int(1),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
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
fn compile_lowers_local_declaration_and_return() {
    let image = compile("fn main() -> i32 { let mut i: i32 = 7; return i; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 1, value: 7 },
            Instruction::I32Move { dst: 0, src: 1 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_lowers_assignment_to_local() {
    let image = compile("fn main() -> i32 { let mut i: i32 = 1; i = i + 2; return i; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 1, value: 1 },
            Instruction::I32Move { dst: 0, src: 1 },
            Instruction::I32Const { dst: 2, value: 2 },
            Instruction::I32Add {
                dst: 3,
                lhs: 0,
                rhs: 2,
            },
            Instruction::I32Move { dst: 0, src: 3 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_lowers_if_else_with_i32_equality() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            if i == 0 {
                return 1;
            } else {
                return 2;
            }
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(matches!(
        instructions[2],
        Instruction::I32Const { value: 0, .. }
    ));
    assert!(matches!(instructions[3], Instruction::I32Eq { .. }));
    assert!(matches!(instructions[4], Instruction::JumpIfFalse { .. }));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Jump { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::ReturnI32 { .. })));
}

#[test]
fn compile_lowers_while_with_i32_less_than() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 3 {
                i = i + 1;
            }
            return i;
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32Lt { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::JumpIfFalse { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Jump { target: 2 })));
    assert!(matches!(
        instructions.last(),
        Some(Instruction::ReturnI32 { .. })
    ));
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

#[test]
fn compile_rejects_undeclared_local_read() {
    let error = compile("fn main() -> i32 { return missing; }").unwrap_err();

    assert!(
        error.message.contains("use of undeclared local `missing`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_duplicate_local_declaration() {
    let error = compile("fn main() { let mut i: i32 = 0; let mut i: i32 = 1; }").unwrap_err();

    assert!(error.message.contains("duplicate local `i`"), "{error:?}");
}

#[test]
fn compile_rejects_assignment_to_undeclared_local() {
    let error = compile("fn main() { i = 1; }").unwrap_err();

    assert!(
        error.message.contains("assignment to undeclared local `i`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_missing_return_after_if_without_else() {
    let error = compile("fn main() -> i32 { if 1 { return 1; } }").unwrap_err();

    assert!(
        error.message.contains("missing return in `i32` function"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unreachable_statement_after_return() {
    let error = compile("fn main() -> i32 { return 1; let mut i: i32 = 2; }").unwrap_err();

    assert!(
        error.message.contains("unreachable statement after return"),
        "{error:?}"
    );
}

#[test]
fn compiled_seed_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(0x10000000).store(1);
                mmio<i32>(0x10000100).store(79);
                mmio<i32>(0x10000100).store(75);
                mmio<i32>(0x10000000).store(2);
            }
            return 0;
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(0)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
    assert_eq!(machine.panic_code(), 0);
    assert_eq!(machine.debug_output_string(), "OK");
}
