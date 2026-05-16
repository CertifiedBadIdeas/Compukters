use crate::low_image::{
    decode_image, Image, ImageError, Instruction, IMAGE_FORMAT_VERSION, IMAGE_MAGIC,
};
use std::fmt::Write;

pub fn disassemble_bytes(bytes: &[u8]) -> Result<String, ImageError> {
    decode_image(bytes).map(|image| disassemble_image(&image))
}

pub fn disassemble_image(image: &Image) -> String {
    let mut out = String::new();
    let magic = String::from_utf8_lossy(IMAGE_MAGIC);
    writeln!(&mut out, "; {magic} v{IMAGE_FORMAT_VERSION}").expect("write to string");
    writeln!(&mut out, "memory_size {}", image.memory_size).expect("write to string");
    writeln!(&mut out, "rodata_size {}", image.rodata.len()).expect("write to string");
    writeln!(&mut out, "data_size {}", image.data.len()).expect("write to string");
    writeln!(&mut out, "bss_size {}", image.bss_size).expect("write to string");
    writeln!(&mut out, "entry fn{}", image.entry_function_index).expect("write to string");
    writeln!(&mut out).expect("write to string");

    for (function_index, function) in image.functions.iter().enumerate() {
        writeln!(
            &mut out,
            "fn {function_index} {}(regs={}, params={}) {{",
            function.name,
            function.register_count,
            format_register_list(&function.parameters),
        )
        .expect("write to string");
        for (instruction_index, instruction) in function.instructions.iter().enumerate() {
            writeln!(
                &mut out,
                "  {instruction_index:04}: {}",
                format_instruction(instruction),
            )
            .expect("write to string");
        }
        writeln!(&mut out, "}}").expect("write to string");
        if function_index + 1 != image.functions.len() {
            writeln!(&mut out).expect("write to string");
        }
    }

    out
}

fn format_instruction(instruction: &Instruction) -> String {
    match instruction {
        Instruction::I32Const { dst, value } => format!("I32Const {}, {value}", reg(*dst)),
        Instruction::I64Const { dst, value } => format!("I64Const {}, {value}", reg(*dst)),
        Instruction::U64Const { dst, value } => format!("U64Const {}, {value}", reg(*dst)),
        Instruction::AddrConst { dst, value } => {
            format!("AddrConst {}, 0x{value:08x}", reg(*dst))
        }
        Instruction::I32Move { dst, src } => format_move("I32Move", *dst, *src),
        Instruction::AddrMove { dst, src } => format_move("AddrMove", *dst, *src),
        Instruction::I32Add { dst, lhs, rhs } => format_binary("I32Add", *dst, *lhs, *rhs),
        Instruction::I32Sub { dst, lhs, rhs } => format_binary("I32Sub", *dst, *lhs, *rhs),
        Instruction::I32Mul { dst, lhs, rhs } => format_binary("I32Mul", *dst, *lhs, *rhs),
        Instruction::I32Div { dst, lhs, rhs } => format_binary("I32Div", *dst, *lhs, *rhs),
        Instruction::I32Rem { dst, lhs, rhs } => format_binary("I32Rem", *dst, *lhs, *rhs),
        Instruction::U32Div { dst, lhs, rhs } => format_binary("U32Div", *dst, *lhs, *rhs),
        Instruction::U32Rem { dst, lhs, rhs } => format_binary("U32Rem", *dst, *lhs, *rhs),
        Instruction::I32BitAnd { dst, lhs, rhs } => format_binary("I32BitAnd", *dst, *lhs, *rhs),
        Instruction::I32BitOr { dst, lhs, rhs } => format_binary("I32BitOr", *dst, *lhs, *rhs),
        Instruction::I32BitXor { dst, lhs, rhs } => format_binary("I32BitXor", *dst, *lhs, *rhs),
        Instruction::I32Shl { dst, lhs, rhs } => format_binary("I32Shl", *dst, *lhs, *rhs),
        Instruction::I32Shr { dst, lhs, rhs } => format_binary("I32Shr", *dst, *lhs, *rhs),
        Instruction::U32Shl { dst, lhs, rhs } => format_binary("U32Shl", *dst, *lhs, *rhs),
        Instruction::U32Shr { dst, lhs, rhs } => format_binary("U32Shr", *dst, *lhs, *rhs),
        Instruction::I32Lt { dst, lhs, rhs } => format_binary("I32Lt", *dst, *lhs, *rhs),
        Instruction::U32Lt { dst, lhs, rhs } => format_binary("U32Lt", *dst, *lhs, *rhs),
        Instruction::I32Eq { dst, lhs, rhs } => format_binary("I32Eq", *dst, *lhs, *rhs),
        Instruction::Load32 { dst, addr } => format_load("Load32", *dst, *addr),
        Instruction::Store32 { addr, src } => format_store("Store32", *addr, *src),
        Instruction::Load8 { dst, addr } => format_load("Load8", *dst, *addr),
        Instruction::Store8 { addr, src } => format_store("Store8", *addr, *src),
        Instruction::Load16 { dst, addr } => format_load("Load16", *dst, *addr),
        Instruction::Store16 { addr, src } => format_store("Store16", *addr, *src),
        Instruction::Load64 { dst, addr } => format_load("Load64", *dst, *addr),
        Instruction::Store64 { addr, src } => format_store("Store64", *addr, *src),
        Instruction::I64Add { dst, lhs, rhs } => format_binary("I64Add", *dst, *lhs, *rhs),
        Instruction::I64Sub { dst, lhs, rhs } => format_binary("I64Sub", *dst, *lhs, *rhs),
        Instruction::I64Mul { dst, lhs, rhs } => format_binary("I64Mul", *dst, *lhs, *rhs),
        Instruction::I64Div { dst, lhs, rhs } => format_binary("I64Div", *dst, *lhs, *rhs),
        Instruction::I64Rem { dst, lhs, rhs } => format_binary("I64Rem", *dst, *lhs, *rhs),
        Instruction::U64Div { dst, lhs, rhs } => format_binary("U64Div", *dst, *lhs, *rhs),
        Instruction::U64Rem { dst, lhs, rhs } => format_binary("U64Rem", *dst, *lhs, *rhs),
        Instruction::I64BitAnd { dst, lhs, rhs } => format_binary("I64BitAnd", *dst, *lhs, *rhs),
        Instruction::I64BitOr { dst, lhs, rhs } => format_binary("I64BitOr", *dst, *lhs, *rhs),
        Instruction::I64BitXor { dst, lhs, rhs } => format_binary("I64BitXor", *dst, *lhs, *rhs),
        Instruction::I64Shl { dst, lhs, rhs } => format_binary("I64Shl", *dst, *lhs, *rhs),
        Instruction::I64Shr { dst, lhs, rhs } => format_binary("I64Shr", *dst, *lhs, *rhs),
        Instruction::U64Shr { dst, lhs, rhs } => format_binary("U64Shr", *dst, *lhs, *rhs),
        Instruction::U64Shl { dst, lhs, rhs } => format_binary("U64Shl", *dst, *lhs, *rhs),
        Instruction::I64Eq { dst, lhs, rhs } => format_binary("I64Eq", *dst, *lhs, *rhs),
        Instruction::I64Lt { dst, lhs, rhs } => format_binary("I64Lt", *dst, *lhs, *rhs),
        Instruction::U64Lt { dst, lhs, rhs } => format_binary("U64Lt", *dst, *lhs, *rhs),
        Instruction::I32ToI64 { dst, src } => format_move("I32ToI64", *dst, *src),
        Instruction::U32ToU64 { dst, src } => format_move("U32ToU64", *dst, *src),
        Instruction::I64ToI32 { dst, src } => format_move("I64ToI32", *dst, *src),
        Instruction::AddrAdd { dst, base, offset } => {
            format!("AddrAdd {}, {}, {}", reg(*dst), reg(*base), reg(*offset))
        }
        Instruction::Jump { target } => format!("Jump @{target}"),
        Instruction::JumpIfFalse { cond, target } => {
            format!("JumpIfFalse {}, @{target}", reg(*cond))
        }
        Instruction::CallStatic {
            return_register,
            function_index,
            arguments,
        } => format!(
            "CallStatic {}, fn{}, {}",
            format_optional_register(*return_register),
            function_index,
            format_register_list(arguments),
        ),
        Instruction::ReturnI32 { src } => format_return("ReturnI32", *src),
        Instruction::ReturnI64 { src } => format_return("ReturnI64", *src),
        Instruction::ReturnAddr { src } => format_return("ReturnAddr", *src),
        Instruction::ReturnBool { src } => format_return("ReturnBool", *src),
        Instruction::ReturnUnit => "ReturnUnit".to_string(),
    }
}

fn format_binary(name: &str, dst: u16, lhs: u16, rhs: u16) -> String {
    format!("{name} {}, {}, {}", reg(dst), reg(lhs), reg(rhs))
}

fn format_move(name: &str, dst: u16, src: u16) -> String {
    format!("{name} {}, {}", reg(dst), reg(src))
}

fn format_load(name: &str, dst: u16, addr: u16) -> String {
    format!("{name} {}, [{}]", reg(dst), reg(addr))
}

fn format_store(name: &str, addr: u16, src: u16) -> String {
    format!("{name} [{}], {}", reg(addr), reg(src))
}

fn format_return(name: &str, src: u16) -> String {
    format!("{name} {}", reg(src))
}

fn format_optional_register(register: Option<u16>) -> String {
    match register {
        Some(register) => reg(register),
        None => "_".to_string(),
    }
}

fn format_register_list(registers: &[u16]) -> String {
    let mut out = String::from("[");
    for (index, register) in registers.iter().enumerate() {
        if index > 0 {
            out.push_str(", ");
        }
        out.push_str(&reg(*register));
    }
    out.push(']');
    out
}

fn reg(register: u16) -> String {
    format!("r{register}")
}
