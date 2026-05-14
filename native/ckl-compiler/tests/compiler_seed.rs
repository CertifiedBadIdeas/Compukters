use ckl_compiler::{compile, lex, TokenKind};
use ckl_vm::computer_machine::ComputerMachine;
use ckl_vm::low_image::Instruction;
use ckl_vm::low_image_runner::LowImageSignal;

#[test]
fn compiler_exposes_public_compile_api() {
    let error = compile("").unwrap_err();

    assert!(
        error.message.contains("missing `main` function"),
        "{error:?}"
    );
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
fn lexer_recognizes_const_keyword() {
    let tokens = lex("const OK: i32 = 79;").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Const,
            TokenKind::Ident("OK".to_string()),
            TokenKind::Colon,
            TokenKind::I32,
            TokenKind::Equal,
            TokenKind::Int(79),
            TokenKind::Semicolon,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn lexer_recognizes_bool_keywords_and_literals() {
    let tokens = lex("fn flag(value: bool) -> bool { return !true && false || value; }").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Fn,
            TokenKind::Ident("flag".to_string()),
            TokenKind::LeftParen,
            TokenKind::Ident("value".to_string()),
            TokenKind::Colon,
            TokenKind::Bool,
            TokenKind::RightParen,
            TokenKind::Arrow,
            TokenKind::Bool,
            TokenKind::LeftBrace,
            TokenKind::Return,
            TokenKind::Bang,
            TokenKind::True,
            TokenKind::AndAnd,
            TokenKind::False,
            TokenKind::OrOr,
            TokenKind::Ident("value".to_string()),
            TokenKind::Semicolon,
            TokenKind::RightBrace,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn lexer_recognizes_ptr_keyword() {
    let tokens = lex("ptr<i32>(RAM_BASE)").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Ptr,
            TokenKind::Less,
            TokenKind::I32,
            TokenKind::Greater,
            TokenKind::LeftParen,
            TokenKind::Ident("RAM_BASE".to_string()),
            TokenKind::RightParen,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn lexer_recognizes_comments_and_bitwise_tokens() {
    let tokens = lex("let mut mask: i32 = 0xff // byte mask\n & 0xf0 | 1 ^ 2 << 3 >> 1;").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::Let,
            TokenKind::Mut,
            TokenKind::Ident("mask".to_string()),
            TokenKind::Colon,
            TokenKind::I32,
            TokenKind::Equal,
            TokenKind::Int(0xff),
            TokenKind::Ampersand,
            TokenKind::Int(0xf0),
            TokenKind::Pipe,
            TokenKind::Int(1),
            TokenKind::Caret,
            TokenKind::Int(2),
            TokenKind::Shl,
            TokenKind::Int(3),
            TokenKind::Shr,
            TokenKind::Int(1),
            TokenKind::Semicolon,
            TokenKind::Eof,
        ]
    );
}

#[test]
fn lexer_recognizes_break_continue_loop_control_keywords() {
    let tokens = lex("while true { continue; break; }").unwrap();
    let kinds: Vec<TokenKind> = tokens.into_iter().map(|token| token.kind).collect();

    assert_eq!(
        kinds,
        vec![
            TokenKind::While,
            TokenKind::True,
            TokenKind::LeftBrace,
            TokenKind::Continue,
            TokenKind::Semicolon,
            TokenKind::Break,
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
fn compile_lowers_i32_bitwise_operations() {
    let image =
        compile("fn main() -> i32 { return (0xff & 0xf0) | (1 ^ (2 << 3 >> 1)); }").unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32BitAnd { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32BitOr { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32BitXor { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32Shl { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::I32Shr { .. })));
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
fn compile_lowers_break_continue_loop_control_in_while() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 10 {
                i = i + 1;
                if i == 2 {
                    continue;
                }
                if i == 4 {
                    break;
                }
            }
            return i;
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

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
    assert!(instructions.iter().all(|instruction| {
        !matches!(
            instruction,
            Instruction::Jump { target: usize::MAX }
                | Instruction::JumpIfFalse {
                    target: usize::MAX,
                    ..
                }
        )
    }));
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
fn compile_lowers_unsafe_ptr_i32_store_and_load() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                ptr<i32>(RAM_BASE + 4).store(42);
                return ptr<i32>(RAM_BASE + 4).load();
            }
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::AddrAdd { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Store32 { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Load32 { .. })));
}

#[test]
fn compile_lowers_debug_write_builtin_address() {
    let image = compile("fn main() { unsafe { mmio<i32>(DEBUG_WRITE).store(79); } }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::DEBUG_WRITE,
            },
            Instruction::I32Const { dst: 1, value: 79 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_control_status_and_status_ready_builtins() {
    let image =
        compile("fn main() { unsafe { mmio<i32>(CONTROL_STATUS).store(STATUS_READY); } }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::CONTROL_STATUS,
            },
            Instruction::I32Const {
                dst: 1,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_status_ready_builtin_i32_return() {
    let image = compile("fn main() -> i32 { return STATUS_READY; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const {
                dst: 0,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_accepts_const_before_main() {
    let image = compile("const OK: i32 = 79; fn main() -> i32 { return OK; }").unwrap();

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Const { dst: 0, value: 79 },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_accepts_bitwise_const_initializers() {
    let image =
        compile("const MASK: i32 = (0xff & 0xf0) | (1 << 2); fn main() -> i32 { return MASK; }")
            .unwrap();

    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Const {
                dst: 0,
                value: 0xf4,
            },
            Instruction::ReturnI32 { src: 0 },
        ]
    );
}

#[test]
fn compile_lowers_unit_helper_function() {
    let image = compile(
        "fn write_ok() {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(79);
            }
        }

        fn main() {
            write_ok();
        }",
    )
    .unwrap();

    assert_eq!(image.entry_function_index, 1);
    assert_eq!(image.functions[0].name, "write_ok");
    assert_eq!(
        image.functions[1].instructions,
        vec![
            Instruction::CallStatic {
                return_register: None,
                function_index: 0,
                arguments: Vec::new(),
            },
            Instruction::ReturnUnit,
        ]
    );
}

#[test]
fn compile_lowers_i32_function_call_with_arguments() {
    let image = compile(
        "fn add(a: i32, b: i32) -> i32 {
            return a + b;
        }

        fn main() -> i32 {
            return add(7, 5);
        }",
    )
    .unwrap();

    assert_eq!(image.entry_function_index, 1);
    assert_eq!(image.functions[0].parameters, vec![0, 1]);
    assert_eq!(
        image.functions[0].instructions,
        vec![
            Instruction::I32Add {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnI32 { src: 2 },
        ]
    );
    assert_eq!(
        image.functions[1].instructions,
        vec![
            Instruction::I32Const { dst: 0, value: 7 },
            Instruction::I32Const { dst: 1, value: 5 },
            Instruction::CallStatic {
                return_register: Some(2),
                function_index: 0,
                arguments: vec![0, 1],
            },
            Instruction::ReturnI32 { src: 2 },
        ]
    );
}

#[test]
fn compile_lowers_bool_return_and_local() {
    let image = compile("fn main() -> bool { let mut ok: bool = true; return ok; }").unwrap();
    let function = &image.functions[0];

    assert_eq!(
        function.instructions,
        vec![
            Instruction::I32Const { dst: 1, value: 1 },
            Instruction::I32Move { dst: 0, src: 1 },
            Instruction::ReturnBool { src: 0 },
        ]
    );
}

#[test]
fn compile_lowers_bool_function_call_with_argument() {
    let image = compile(
        "fn identity(value: bool) -> bool {
            return value;
        }

        fn main() -> bool {
            return identity(false);
        }",
    )
    .unwrap();

    assert_eq!(image.entry_function_index, 1);
    assert_eq!(image.functions[0].parameters, vec![0]);
    assert_eq!(
        image.functions[1].instructions,
        vec![
            Instruction::I32Const { dst: 0, value: 0 },
            Instruction::CallStatic {
                return_register: Some(1),
                function_index: 0,
                arguments: vec![0],
            },
            Instruction::ReturnBool { src: 1 },
        ]
    );
}

#[test]
fn compile_accepts_bool_conditions() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 3 {
                if i == 1 {
                    i = i + 2;
                } else {
                    i = i + 1;
                }
            }
            return i;
        }",
    )
    .unwrap();

    assert!(image.functions[0]
        .instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::JumpIfFalse { .. })));
}

#[test]
fn compile_lowers_boolean_operators_with_jumps() {
    let image = compile(
        "fn main() -> bool {
            return !(false || true && false);
        }",
    )
    .unwrap();
    let instructions = &image.functions[0].instructions;

    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::JumpIfFalse { .. })));
    assert!(instructions
        .iter()
        .any(|instruction| matches!(instruction, Instruction::Jump { .. })));
    assert!(matches!(
        instructions.last(),
        Some(Instruction::ReturnBool { .. })
    ));
}

#[test]
fn compile_rejects_void_return_type() {
    let error = compile("fn main() -> void { }").unwrap_err();

    assert!(error.message.contains("expected type"), "{error:?}");
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
fn compile_rejects_ptr_outside_unsafe() {
    let error = compile("fn main() { ptr<i32>(RAM_BASE).store(1); }").unwrap_err();

    assert!(
        error.message.contains("pointer access requires `unsafe`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_const_as_ptr_address() {
    let error = compile("const VALUE: i32 = 4; fn main() { unsafe { ptr<i32>(VALUE).store(1); } }")
        .unwrap_err();

    assert!(
        error
            .message
            .contains("pointer address must be an address expression"),
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
fn compile_rejects_address_builtin_as_i32() {
    let error = compile("fn main() -> i32 { return DEBUG_WRITE; }").unwrap_err();

    assert!(
        error.message.contains("expected `i32`, found address"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_bool_return_from_i32_function() {
    let error = compile("fn main() -> i32 { return true; }").unwrap_err();

    assert!(
        error.message.contains("expected `i32`, found bool"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_return_from_bool_function() {
    let error = compile("fn main() -> bool { return 1; }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_builtin_as_mmio_address() {
    let error = compile("fn main() { unsafe { mmio<i32>(STATUS_READY).store(1); } }").unwrap_err();

    assert!(
        error
            .message
            .contains("MMIO address must be an address expression"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_builtin_shadowing() {
    let error = compile("fn main() { let mut DEBUG_WRITE: i32 = 1; }").unwrap_err();

    assert!(
        error
            .message
            .contains("local `DEBUG_WRITE` cannot shadow built-in ABI constant"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unknown_identifier() {
    let error = compile("fn main() -> i32 { return UNKNOWN_CONSTANT; }").unwrap_err();

    assert!(
        error
            .message
            .contains("use of undeclared local `UNKNOWN_CONSTANT`"),
        "{error:?}"
    );
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
fn compile_rejects_duplicate_function_declaration() {
    let error = compile("fn helper() { } fn helper() { } fn main() { }").unwrap_err();

    assert!(
        error.message.contains("duplicate function `helper`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_function_name_shadowing_source_const() {
    let error = compile("const helper: i32 = 1; fn helper() { } fn main() { }").unwrap_err();

    assert!(
        error
            .message
            .contains("function `helper` cannot shadow const"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_duplicate_function_parameter() {
    let error = compile("fn helper(a: i32, a: i32) { } fn main() { }").unwrap_err();

    assert!(
        error.message.contains("duplicate parameter `a`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unknown_function_call() {
    let error = compile("fn main() { missing(); }").unwrap_err();

    assert!(
        error.message.contains("unknown function `missing`"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_wrong_function_argument_count() {
    let error = compile("fn helper(a: i32) { } fn main() { helper(1, 2); }").unwrap_err();

    assert!(
        error
            .message
            .contains("function `helper` expects 1 arguments but got 2"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_wrong_function_argument_type() {
    let error = compile("fn helper(flag: bool) { } fn main() { helper(1); }").unwrap_err();

    assert!(
        error
            .message
            .contains("function `helper` argument 0 expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_unit_function_used_as_i32_value() {
    let error = compile("fn helper() { } fn main() -> i32 { return helper(); }").unwrap_err();

    assert!(
        error
            .message
            .contains("unit function `helper` used as `i32` value"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_direct_recursion() {
    let error = compile("fn main() { main(); }").unwrap_err();

    assert!(
        error.message.contains("recursive function call `main`"),
        "{error:?}"
    );
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
    let error = compile("fn main() -> i32 { if true { return 1; } }").unwrap_err();

    assert!(
        error.message.contains("missing return in `i32` function"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_condition() {
    let error = compile("fn main() { if 1 { } }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_boolean_not_operand() {
    let error = compile("fn main() -> bool { return !1; }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_logical_and_operand() {
    let error = compile("fn main() -> bool { return true && 1; }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_logical_or_operand() {
    let error = compile("fn main() -> bool { return 1 || false; }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_i32_assignment_to_bool_local() {
    let error = compile("fn main() { let mut flag: bool = true; flag = 1; }").unwrap_err();

    assert!(
        error.message.contains("expected `bool`, found i32"),
        "{error:?}"
    );
}

#[test]
fn compile_rejects_bool_assignment_to_i32_local() {
    let error = compile("fn main() { let mut value: i32 = 1; value = false; }").unwrap_err();

    assert!(
        error.message.contains("expected `i32`, found bool"),
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
fn compile_rejects_break_continue_loop_control_outside_loop() {
    let break_error = compile("fn main() { break; }").unwrap_err();
    let continue_error = compile("fn main() { continue; }").unwrap_err();

    assert!(
        break_error.message.contains("`break` outside loop"),
        "{break_error:?}"
    );
    assert!(
        continue_error.message.contains("`continue` outside loop"),
        "{continue_error:?}"
    );
}

#[test]
fn compile_rejects_unreachable_statement_after_break_continue_loop_control() {
    let break_error =
        compile("fn main() { while true { break; let mut i: i32 = 1; } }").unwrap_err();
    let continue_error =
        compile("fn main() { while true { continue; let mut i: i32 = 1; } }").unwrap_err();

    assert!(
        break_error
            .message
            .contains("unreachable statement after loop control"),
        "{break_error:?}"
    );
    assert!(
        continue_error
            .message
            .contains("unreachable statement after loop control"),
        "{continue_error:?}"
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

#[test]
fn compiled_seed_break_continue_loop_control_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 10 {
                i = i + 1;
                if i == 2 {
                    continue;
                }
                if i == 4 {
                    break;
                }
            }
            return i;
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(4)
    );
    assert_eq!(machine.exit_code(), 4);
}

#[test]
fn compiled_seed_loop_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            let mut i: i32 = 0;
            while i < 2 {
                unsafe {
                    mmio<i32>(0x10000100).store(79 + i);
                }
                i = i + 1;
            }
            return i;
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(2)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 2);
    assert_eq!(machine.debug_output_bytes(), &[79, 80]);
}

#[test]
fn compiled_seed_abi_constants_run_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
                mmio<i32>(DEBUG_WRITE).store(79);
                mmio<i32>(DEBUG_WRITE).store(75);
                mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
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

#[test]
fn compiled_seed_functions_and_consts_run_on_computer_machine() {
    let image = compile(
        "const OK_O: i32 = 79;
         const OK_K: i32 = 75;

         fn write_ok() {
             unsafe {
                 mmio<i32>(DEBUG_WRITE).store(OK_O);
                 mmio<i32>(DEBUG_WRITE).store(OK_K);
             }
         }

         fn main() -> i32 {
             unsafe {
                 mmio<i32>(CONTROL_STATUS).store(STATUS_BOOTING);
             }

             write_ok();

             unsafe {
                 mmio<i32>(CONTROL_STATUS).store(STATUS_READY);
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

#[test]
fn compiled_seed_ptr_i32_ram_program_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            unsafe {
                ptr<i32>(RAM_BASE + 4).store(42);
                return ptr<i32>(RAM_BASE + 4).load();
            }
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(42)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 42);
    assert_eq!(machine.panic_code(), 0);
}

#[test]
fn compiled_seed_bool_program_runs_on_computer_machine() {
    let image = compile(
        "fn less_than_three(value: i32) -> bool {
            return value < 3;
        }

        fn main() -> bool {
            return less_than_three(2);
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltBool(true)
    );
}

#[test]
fn compiled_seed_boolean_operators_run_on_computer_machine() {
    let image = compile(
        "fn main() -> bool {
            return !(false || true && false);
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltBool(true)
    );
    assert_eq!(machine.exit_code(), 0);
}

#[test]
fn compiled_seed_logical_and_short_circuits_rhs() {
    let image = compile(
        "fn write_and_return_true() -> bool {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(88);
            }
            return true;
        }

        fn main() -> bool {
            return false && write_and_return_true();
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltBool(false)
    );
    assert_eq!(machine.debug_output_bytes(), &[]);
}

#[test]
fn compiled_seed_logical_or_short_circuits_rhs() {
    let image = compile(
        "fn write_and_return_false() -> bool {
            unsafe {
                mmio<i32>(DEBUG_WRITE).store(88);
            }
            return false;
        }

        fn main() -> bool {
            return true || write_and_return_false();
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltBool(true)
    );
    assert_eq!(machine.debug_output_bytes(), &[]);
}

#[test]
fn compiled_seed_bitwise_program_runs_on_computer_machine() {
    let image = compile(
        "fn main() -> i32 {
            // Compose a compact status byte with masks and shifts.
            let mut value: i32 = (0xff & 0xf0) | (1 << 2);
            value = value ^ (3 >> 1);
            return value;
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(0xf5)
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0xf5);
    assert_eq!(machine.panic_code(), 0);
}
