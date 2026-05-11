use thiserror::Error;

pub const VERSION: u8 = 5;

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub language_version: String,
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
    pub register_count: usize,
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
    I32Lt {
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
    if reader.take(4)? != b"CKIM" {
        return Err(ImageError::InvalidMagic);
    }
    let version = reader.u8()?;
    if version != VERSION {
        return Err(ImageError::UnsupportedVersion(version));
    }
    Ok(Image {
        language_version: reader.string()?,
        memory_size: reader.u32()?,
        rodata: reader.bytes()?,
        data: reader.bytes()?,
        bss_size: reader.u32()?,
        entry_function_index: reader.index("entry function")?,
        functions: reader.list(read_function)?,
    })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, ImageError> {
    Ok(Function {
        name: reader.string()?,
        register_count: usize::from(reader.u16()?),
        parameters: reader.register_list()?,
        instructions: reader.list(read_instruction)?,
    })
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
