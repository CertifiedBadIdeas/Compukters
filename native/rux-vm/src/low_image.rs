use thiserror::Error;

pub const IMAGE_MAGIC: &[u8; 4] = b"RUXI";
pub const IMAGE_FORMAT_VERSION: u8 = 1;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ImageError {
    #[error("invalid image magic")]
    InvalidMagic,
    #[error("unsupported image version {0}")]
    UnsupportedVersion(u8),
    #[error("unexpected end of image")]
    UnexpectedEnd,
    #[error("invalid utf-8 string")]
    InvalidUtf8,
    #[error("negative length {0}")]
    NegativeLength(i32),
    #[error("negative {name} index {value}")]
    NegativeIndex { name: &'static str, value: i32 },
    #[error("unknown optional register tag {0}")]
    UnknownOptionalRegisterTag(u8),
    #[error("unknown instruction tag {0}")]
    UnknownInstructionTag(u8),
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ImageEncodeError {
    #[error("{name} length {value} exceeds i32::MAX")]
    LengthTooLarge { name: &'static str, value: usize },
    #[error("{name} index {value} exceeds i32::MAX")]
    IndexTooLarge { name: &'static str, value: usize },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub memory_size: u32,
    pub rodata: Vec<u8>,
    pub data: Vec<u8>,
    pub bss_size: u32,
    pub entry_function_index: usize,
    pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
    pub name: String,
    pub register_count: u16,
    pub parameters: Vec<u16>,
    pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
    I32Const {
        dst: u16,
        value: i32,
    },
    I64Const {
        dst: u16,
        value: i64,
    },
    U64Const {
        dst: u16,
        value: u64,
    },
    AddrConst {
        dst: u16,
        value: u32,
    },
    I32Move {
        dst: u16,
        src: u16,
    },
    AddrMove {
        dst: u16,
        src: u16,
    },
    I32Add {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Sub {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Mul {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Div {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Rem {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U32Div {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U32Rem {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32BitAnd {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32BitOr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32BitXor {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Shl {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Shr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U32Shl {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U32Shr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Lt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U32Lt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Eq {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    Load32 {
        dst: u16,
        addr: u16,
    },
    Store32 {
        addr: u16,
        src: u16,
    },
    Load8 {
        dst: u16,
        addr: u16,
    },
    Store8 {
        addr: u16,
        src: u16,
    },
    Load16 {
        dst: u16,
        addr: u16,
    },
    Store16 {
        addr: u16,
        src: u16,
    },
    Load64 {
        dst: u16,
        addr: u16,
    },
    Store64 {
        addr: u16,
        src: u16,
    },
    I64Add {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Sub {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Mul {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Div {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Rem {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U64Div {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U64Rem {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64BitAnd {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64BitOr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64BitXor {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Shl {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Shr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U64Shr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Eq {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I64Lt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    U64Lt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32ToI64 {
        dst: u16,
        src: u16,
    },
    U32ToU64 {
        dst: u16,
        src: u16,
    },
    I64ToI32 {
        dst: u16,
        src: u16,
    },
    AddrAdd {
        dst: u16,
        base: u16,
        offset: u16,
    },
    Jump {
        target: usize,
    },
    JumpIfFalse {
        cond: u16,
        target: usize,
    },
    CallStatic {
        return_register: Option<u16>,
        function_index: usize,
        arguments: Vec<u16>,
    },
    ReturnI32 {
        src: u16,
    },
    ReturnI64 {
        src: u16,
    },
    ReturnAddr {
        src: u16,
    },
    ReturnBool {
        src: u16,
    },
    ReturnUnit,
}

pub fn decode_image(bytes: &[u8]) -> Result<Image, ImageError> {
    let mut reader = Reader { bytes, offset: 0 };
    if reader.take(4)? != IMAGE_MAGIC {
        return Err(ImageError::InvalidMagic);
    }
    let version = reader.u8()?;
    if version != IMAGE_FORMAT_VERSION {
        return Err(ImageError::UnsupportedVersion(version));
    }
    Ok(Image {
        memory_size: reader.u32()?,
        rodata: reader.bytes()?,
        data: reader.bytes()?,
        bss_size: reader.u32()?,
        entry_function_index: reader.index("entry function")?,
        functions: reader.list(read_function)?,
    })
}

pub fn encode_image(image: &Image) -> Result<Vec<u8>, ImageEncodeError> {
    let mut writer = Writer { bytes: Vec::new() };
    writer.raw(IMAGE_MAGIC);
    writer.u8(IMAGE_FORMAT_VERSION);
    writer.u32(image.memory_size);
    writer.byte_list("rodata", &image.rodata)?;
    writer.byte_list("data", &image.data)?;
    writer.u32(image.bss_size);
    writer.index("entry function", image.entry_function_index)?;
    writer.functions(&image.functions)?;
    Ok(writer.bytes)
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, ImageError> {
    Ok(Function {
        name: reader.string()?,
        register_count: reader.u16()?,
        parameters: reader.register_list()?,
        instructions: reader.list(read_instruction)?,
    })
}

struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    fn raw(&mut self, bytes: &[u8]) {
        self.bytes.extend_from_slice(bytes);
    }

    fn u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn u16(&mut self, value: u16) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn i32(&mut self, value: i32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u32(&mut self, value: u32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn i64(&mut self, value: i64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u64(&mut self, value: u64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn byte_list(&mut self, name: &'static str, value: &[u8]) -> Result<(), ImageEncodeError> {
        self.length(name, value.len())?;
        self.raw(value);
        Ok(())
    }

    fn string(&mut self, name: &'static str, value: &str) -> Result<(), ImageEncodeError> {
        self.byte_list(name, value.as_bytes())
    }

    fn functions(&mut self, functions: &[Function]) -> Result<(), ImageEncodeError> {
        self.length("functions", functions.len())?;
        for function in functions {
            self.function(function)?;
        }
        Ok(())
    }

    fn function(&mut self, function: &Function) -> Result<(), ImageEncodeError> {
        self.string("function name", &function.name)?;
        self.u16(function.register_count);
        self.register_list("parameters", &function.parameters)?;
        self.length("instructions", function.instructions.len())?;
        for instruction in &function.instructions {
            self.instruction(instruction)?;
        }
        Ok(())
    }

    fn instruction(&mut self, instruction: &Instruction) -> Result<(), ImageEncodeError> {
        match instruction {
            Instruction::I32Const { dst, value } => {
                self.u8(1);
                self.u16(*dst);
                self.i32(*value);
            }
            Instruction::I64Const { dst, value } => {
                self.u8(2);
                self.u16(*dst);
                self.i64(*value);
            }
            Instruction::U64Const { dst, value } => {
                self.u8(38);
                self.u16(*dst);
                self.u64(*value);
            }
            Instruction::AddrConst { dst, value } => {
                self.u8(3);
                self.u16(*dst);
                self.u32(*value);
            }
            Instruction::I32Move { dst, src } => self.move_instruction(4, *dst, *src),
            Instruction::AddrMove { dst, src } => self.move_instruction(5, *dst, *src),
            Instruction::I32Add { dst, lhs, rhs } => self.binary(6, *dst, *lhs, *rhs),
            Instruction::I32Sub { dst, lhs, rhs } => self.binary(7, *dst, *lhs, *rhs),
            Instruction::I32Mul { dst, lhs, rhs } => self.binary(8, *dst, *lhs, *rhs),
            Instruction::I32Div { dst, lhs, rhs } => self.binary(9, *dst, *lhs, *rhs),
            Instruction::I32BitXor { dst, lhs, rhs } => self.binary(10, *dst, *lhs, *rhs),
            Instruction::I32Shl { dst, lhs, rhs } => self.binary(11, *dst, *lhs, *rhs),
            Instruction::I32Shr { dst, lhs, rhs } => self.binary(12, *dst, *lhs, *rhs),
            Instruction::I32Lt { dst, lhs, rhs } => self.binary(13, *dst, *lhs, *rhs),
            Instruction::Load32 { dst, addr } => self.move_instruction(14, *dst, *addr),
            Instruction::Store32 { addr, src } => self.move_instruction(15, *addr, *src),
            Instruction::AddrAdd { dst, base, offset } => self.binary(16, *dst, *base, *offset),
            Instruction::Jump { target } => {
                self.u8(17);
                self.index("jump target", *target)?;
            }
            Instruction::JumpIfFalse { cond, target } => {
                self.u8(18);
                self.u16(*cond);
                self.index("jump target", *target)?;
            }
            Instruction::CallStatic {
                return_register,
                function_index,
                arguments,
            } => {
                self.u8(19);
                self.optional_register(*return_register);
                self.index("function", *function_index)?;
                self.register_list("arguments", arguments)?;
            }
            Instruction::ReturnI32 { src } => self.return_register(20, *src),
            Instruction::ReturnUnit => self.u8(21),
            Instruction::ReturnI64 { src } => self.return_register(22, *src),
            Instruction::ReturnAddr { src } => self.return_register(23, *src),
            Instruction::ReturnBool { src } => self.return_register(24, *src),
            Instruction::I32Eq { dst, lhs, rhs } => self.binary(25, *dst, *lhs, *rhs),
            Instruction::I32BitAnd { dst, lhs, rhs } => self.binary(26, *dst, *lhs, *rhs),
            Instruction::I32BitOr { dst, lhs, rhs } => self.binary(27, *dst, *lhs, *rhs),
            Instruction::U32Lt { dst, lhs, rhs } => self.binary(28, *dst, *lhs, *rhs),
            Instruction::U32Shl { dst, lhs, rhs } => self.binary(29, *dst, *lhs, *rhs),
            Instruction::U32Shr { dst, lhs, rhs } => self.binary(30, *dst, *lhs, *rhs),
            Instruction::Load8 { dst, addr } => self.move_instruction(31, *dst, *addr),
            Instruction::Store8 { addr, src } => self.move_instruction(32, *addr, *src),
            Instruction::I32Rem { dst, lhs, rhs } => self.binary(33, *dst, *lhs, *rhs),
            Instruction::U32Div { dst, lhs, rhs } => self.binary(34, *dst, *lhs, *rhs),
            Instruction::U32Rem { dst, lhs, rhs } => self.binary(35, *dst, *lhs, *rhs),
            Instruction::Load16 { dst, addr } => self.move_instruction(36, *dst, *addr),
            Instruction::Store16 { addr, src } => self.move_instruction(37, *addr, *src),
            Instruction::Load64 { dst, addr } => self.move_instruction(39, *dst, *addr),
            Instruction::Store64 { addr, src } => self.move_instruction(40, *addr, *src),
            Instruction::I64Add { dst, lhs, rhs } => self.binary(41, *dst, *lhs, *rhs),
            Instruction::I64Sub { dst, lhs, rhs } => self.binary(42, *dst, *lhs, *rhs),
            Instruction::I64Mul { dst, lhs, rhs } => self.binary(43, *dst, *lhs, *rhs),
            Instruction::I64Div { dst, lhs, rhs } => self.binary(44, *dst, *lhs, *rhs),
            Instruction::I64Rem { dst, lhs, rhs } => self.binary(45, *dst, *lhs, *rhs),
            Instruction::U64Div { dst, lhs, rhs } => self.binary(46, *dst, *lhs, *rhs),
            Instruction::U64Rem { dst, lhs, rhs } => self.binary(47, *dst, *lhs, *rhs),
            Instruction::I64BitAnd { dst, lhs, rhs } => self.binary(48, *dst, *lhs, *rhs),
            Instruction::I64BitOr { dst, lhs, rhs } => self.binary(49, *dst, *lhs, *rhs),
            Instruction::I64BitXor { dst, lhs, rhs } => self.binary(50, *dst, *lhs, *rhs),
            Instruction::I64Shl { dst, lhs, rhs } => self.binary(51, *dst, *lhs, *rhs),
            Instruction::I64Shr { dst, lhs, rhs } => self.binary(52, *dst, *lhs, *rhs),
            Instruction::U64Shr { dst, lhs, rhs } => self.binary(53, *dst, *lhs, *rhs),
            Instruction::I64Eq { dst, lhs, rhs } => self.binary(54, *dst, *lhs, *rhs),
            Instruction::I64Lt { dst, lhs, rhs } => self.binary(55, *dst, *lhs, *rhs),
            Instruction::U64Lt { dst, lhs, rhs } => self.binary(56, *dst, *lhs, *rhs),
            Instruction::I32ToI64 { dst, src } => self.move_instruction(57, *dst, *src),
            Instruction::U32ToU64 { dst, src } => self.move_instruction(58, *dst, *src),
            Instruction::I64ToI32 { dst, src } => self.move_instruction(59, *dst, *src),
        }
        Ok(())
    }

    fn move_instruction(&mut self, tag: u8, first: u16, second: u16) {
        self.u8(tag);
        self.u16(first);
        self.u16(second);
    }

    fn binary(&mut self, tag: u8, dst: u16, lhs: u16, rhs: u16) {
        self.u8(tag);
        self.u16(dst);
        self.u16(lhs);
        self.u16(rhs);
    }

    fn return_register(&mut self, tag: u8, src: u16) {
        self.u8(tag);
        self.u16(src);
    }

    fn optional_register(&mut self, value: Option<u16>) {
        match value {
            None => self.u8(0),
            Some(register) => {
                self.u8(1);
                self.u16(register);
            }
        }
    }

    fn register_list(
        &mut self,
        name: &'static str,
        registers: &[u16],
    ) -> Result<(), ImageEncodeError> {
        self.length(name, registers.len())?;
        for register in registers {
            self.u16(*register);
        }
        Ok(())
    }

    fn length(&mut self, name: &'static str, value: usize) -> Result<(), ImageEncodeError> {
        let value =
            i32::try_from(value).map_err(|_| ImageEncodeError::LengthTooLarge { name, value })?;
        self.i32(value);
        Ok(())
    }

    fn index(&mut self, name: &'static str, value: usize) -> Result<(), ImageEncodeError> {
        let value =
            i32::try_from(value).map_err(|_| ImageEncodeError::IndexTooLarge { name, value })?;
        self.i32(value);
        Ok(())
    }
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, ImageError> {
    match reader.u8()? {
        1 => Ok(Instruction::I32Const {
            dst: reader.u16()?,
            value: reader.i32()?,
        }),
        2 => Ok(Instruction::I64Const {
            dst: reader.u16()?,
            value: reader.i64()?,
        }),
        38 => Ok(Instruction::U64Const {
            dst: reader.u16()?,
            value: reader.u64()?,
        }),
        3 => Ok(Instruction::AddrConst {
            dst: reader.u16()?,
            value: reader.u32()?,
        }),
        4 => read_move(reader, |dst, src| Instruction::I32Move { dst, src }),
        5 => read_move(reader, |dst, src| Instruction::AddrMove { dst, src }),
        6 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Add {
            dst,
            lhs,
            rhs,
        }),
        7 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Sub {
            dst,
            lhs,
            rhs,
        }),
        8 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Mul {
            dst,
            lhs,
            rhs,
        }),
        9 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Div {
            dst,
            lhs,
            rhs,
        }),
        10 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitXor {
            dst,
            lhs,
            rhs,
        }),
        11 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shl {
            dst,
            lhs,
            rhs,
        }),
        12 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shr {
            dst,
            lhs,
            rhs,
        }),
        13 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Lt { dst, lhs, rhs }),
        14 => read_move(reader, |dst, addr| Instruction::Load32 { dst, addr }),
        15 => read_move(reader, |addr, src| Instruction::Store32 { addr, src }),
        16 => read_binary(reader, |dst, base, offset| Instruction::AddrAdd {
            dst,
            base,
            offset,
        }),
        17 => Ok(Instruction::Jump {
            target: reader.index("jump target")?,
        }),
        18 => Ok(Instruction::JumpIfFalse {
            cond: reader.u16()?,
            target: reader.index("jump target")?,
        }),
        19 => Ok(Instruction::CallStatic {
            return_register: reader.optional_register()?,
            function_index: reader.index("function")?,
            arguments: reader.register_list()?,
        }),
        20 => Ok(Instruction::ReturnI32 { src: reader.u16()? }),
        21 => Ok(Instruction::ReturnUnit),
        22 => Ok(Instruction::ReturnI64 { src: reader.u16()? }),
        23 => Ok(Instruction::ReturnAddr { src: reader.u16()? }),
        24 => Ok(Instruction::ReturnBool { src: reader.u16()? }),
        25 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Eq { dst, lhs, rhs }),
        26 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitAnd {
            dst,
            lhs,
            rhs,
        }),
        27 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitOr {
            dst,
            lhs,
            rhs,
        }),
        28 => read_binary(reader, |dst, lhs, rhs| Instruction::U32Lt { dst, lhs, rhs }),
        29 => read_binary(reader, |dst, lhs, rhs| Instruction::U32Shl {
            dst,
            lhs,
            rhs,
        }),
        30 => read_binary(reader, |dst, lhs, rhs| Instruction::U32Shr {
            dst,
            lhs,
            rhs,
        }),
        31 => read_move(reader, |dst, addr| Instruction::Load8 { dst, addr }),
        32 => read_move(reader, |addr, src| Instruction::Store8 { addr, src }),
        33 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Rem {
            dst,
            lhs,
            rhs,
        }),
        34 => read_binary(reader, |dst, lhs, rhs| Instruction::U32Div {
            dst,
            lhs,
            rhs,
        }),
        35 => read_binary(reader, |dst, lhs, rhs| Instruction::U32Rem {
            dst,
            lhs,
            rhs,
        }),
        36 => read_move(reader, |dst, addr| Instruction::Load16 { dst, addr }),
        37 => read_move(reader, |addr, src| Instruction::Store16 { addr, src }),
        39 => read_move(reader, |dst, addr| Instruction::Load64 { dst, addr }),
        40 => read_move(reader, |addr, src| Instruction::Store64 { addr, src }),
        41 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Add {
            dst,
            lhs,
            rhs,
        }),
        42 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Sub {
            dst,
            lhs,
            rhs,
        }),
        43 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Mul {
            dst,
            lhs,
            rhs,
        }),
        44 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Div {
            dst,
            lhs,
            rhs,
        }),
        45 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Rem {
            dst,
            lhs,
            rhs,
        }),
        46 => read_binary(reader, |dst, lhs, rhs| Instruction::U64Div {
            dst,
            lhs,
            rhs,
        }),
        47 => read_binary(reader, |dst, lhs, rhs| Instruction::U64Rem {
            dst,
            lhs,
            rhs,
        }),
        48 => read_binary(reader, |dst, lhs, rhs| Instruction::I64BitAnd {
            dst,
            lhs,
            rhs,
        }),
        49 => read_binary(reader, |dst, lhs, rhs| Instruction::I64BitOr {
            dst,
            lhs,
            rhs,
        }),
        50 => read_binary(reader, |dst, lhs, rhs| Instruction::I64BitXor {
            dst,
            lhs,
            rhs,
        }),
        51 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Shl {
            dst,
            lhs,
            rhs,
        }),
        52 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Shr {
            dst,
            lhs,
            rhs,
        }),
        53 => read_binary(reader, |dst, lhs, rhs| Instruction::U64Shr {
            dst,
            lhs,
            rhs,
        }),
        54 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Eq { dst, lhs, rhs }),
        55 => read_binary(reader, |dst, lhs, rhs| Instruction::I64Lt { dst, lhs, rhs }),
        56 => read_binary(reader, |dst, lhs, rhs| Instruction::U64Lt { dst, lhs, rhs }),
        57 => read_move(reader, |dst, src| Instruction::I32ToI64 { dst, src }),
        58 => read_move(reader, |dst, src| Instruction::U32ToU64 { dst, src }),
        59 => read_move(reader, |dst, src| Instruction::I64ToI32 { dst, src }),
        other => Err(ImageError::UnknownInstructionTag(other)),
    }
}

fn read_move(
    reader: &mut Reader<'_>,
    create: fn(u16, u16) -> Instruction,
) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?))
}

fn read_binary(
    reader: &mut Reader<'_>,
    create: fn(u16, u16, u16) -> Instruction,
) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?, reader.u16()?))
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], ImageError> {
        let end = self
            .offset
            .checked_add(count)
            .ok_or(ImageError::UnexpectedEnd)?;
        let slice = self
            .bytes
            .get(self.offset..end)
            .ok_or(ImageError::UnexpectedEnd)?;
        self.offset = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8, ImageError> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, ImageError> {
        let mut bytes = [0_u8; 2];
        bytes.copy_from_slice(self.take(2)?);
        Ok(u16::from_le_bytes(bytes))
    }

    fn i32(&mut self) -> Result<i32, ImageError> {
        let mut bytes = [0_u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn u32(&mut self) -> Result<u32, ImageError> {
        let mut bytes = [0_u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(u32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, ImageError> {
        let mut bytes = [0_u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn u64(&mut self) -> Result<u64, ImageError> {
        let mut bytes = [0_u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(u64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, ImageError> {
        String::from_utf8(self.bytes()?).map_err(|_| ImageError::InvalidUtf8)
    }

    fn bytes(&mut self) -> Result<Vec<u8>, ImageError> {
        let length = self.length()?;
        Ok(self.take(length)?.to_vec())
    }

    fn optional_register(&mut self) -> Result<Option<u16>, ImageError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.u16()?)),
            other => Err(ImageError::UnknownOptionalRegisterTag(other)),
        }
    }

    fn register_list(&mut self) -> Result<Vec<u16>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(self.u16()?);
        }
        Ok(values)
    }

    fn list<T>(
        &mut self,
        read: fn(&mut Reader<'a>) -> Result<T, ImageError>,
    ) -> Result<Vec<T>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(read(self)?);
        }
        Ok(values)
    }

    fn index(&mut self, name: &'static str) -> Result<usize, ImageError> {
        let value = self.i32()?;
        if value < 0 {
            return Err(ImageError::NegativeIndex { name, value });
        }
        Ok(value as usize)
    }

    fn length(&mut self) -> Result<usize, ImageError> {
        let value = self.i32()?;
        if value < 0 {
            return Err(ImageError::NegativeLength(value));
        }
        Ok(value as usize)
    }
}
