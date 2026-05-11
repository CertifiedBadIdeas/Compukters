use thiserror::Error;

pub const VERSION: u8 = 2;

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
    #[error("unknown constant tag {0}")]
    UnknownConstantTag(u8),
    #[error("unknown instruction tag {0}")]
    UnknownInstructionTag(u8),
    #[error("invalid bool byte {0}")]
    InvalidBool(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub language_version: String,
    pub constants: Vec<Constant>,
    pub host_imports: Vec<HostImport>,
    pub entry_function_index: usize,
    pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Constant {
    String(String),
    Int(i32),
    Long(i64),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostImport {
    pub id: i32,
    pub module_name: String,
    pub function_name: String,
    pub parameter_types: Vec<String>,
    pub return_type: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
    pub name: String,
    pub register_count: usize,
    pub parameter_count: usize,
    pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
    LoadConst {
        dst: u16,
        constant_index: usize,
    },
    LoadUnit {
        dst: u16,
    },
    LoadNull {
        dst: u16,
    },
    LoadBool {
        dst: u16,
        value: bool,
    },
    Move {
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
    I32Neg {
        dst: u16,
        src: u16,
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
    I32BitNot {
        dst: u16,
        src: u16,
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
    I32Eq {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Ne {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Lt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Le {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Gt {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    I32Ge {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    BoolNot {
        dst: u16,
        src: u16,
    },
    BoolAnd {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    BoolOr {
        dst: u16,
        lhs: u16,
        rhs: u16,
    },
    Jump {
        target: usize,
    },
    JumpIfFalse {
        cond: u16,
        target: usize,
    },
    JumpIfTrue {
        cond: u16,
        target: usize,
    },
    CallStatic {
        return_register: Option<u16>,
        function_index: usize,
        arguments: Vec<u16>,
    },
    Return {
        src: u16,
    },
    ReturnUnit,
    CallHost {
        return_register: Option<u16>,
        import_id: i32,
        arguments: Vec<u16>,
    },
    Yield {
        dst: u16,
    },
    Sleep {
        dst: u16,
        ticks: u16,
    },
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
    let language_version = reader.string()?;
    let constants = reader.list(read_constant)?;
    let host_imports = reader.list(read_host_import)?;
    let entry_function_index = reader.index("entry function")?;
    let functions = reader.list(read_function)?;
    Ok(Image {
        language_version,
        constants,
        host_imports,
        entry_function_index,
        functions,
    })
}

fn read_string(reader: &mut Reader<'_>) -> Result<String, ImageError> {
    reader.string()
}

fn read_constant(reader: &mut Reader<'_>) -> Result<Constant, ImageError> {
    match reader.u8()? {
        1 => Ok(Constant::String(reader.string()?)),
        2 => Ok(Constant::Int(reader.i32()?)),
        3 => Ok(Constant::Long(reader.i64()?)),
        other => Err(ImageError::UnknownConstantTag(other)),
    }
}

fn read_host_import(reader: &mut Reader<'_>) -> Result<HostImport, ImageError> {
    Ok(HostImport {
        id: reader.i32()?,
        module_name: reader.string()?,
        function_name: reader.string()?,
        parameter_types: reader.list(read_string)?,
        return_type: reader.string()?,
    })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, ImageError> {
    Ok(Function {
        name: reader.string()?,
        register_count: usize::from(reader.u16()?),
        parameter_count: usize::from(reader.u16()?),
        instructions: reader.list(read_instruction)?,
    })
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, ImageError> {
    match reader.u8()? {
        1 => Ok(Instruction::LoadConst {
            dst: reader.u16()?,
            constant_index: reader.index("constant")?,
        }),
        2 => Ok(Instruction::LoadUnit { dst: reader.u16()? }),
        3 => Ok(Instruction::LoadNull { dst: reader.u16()? }),
        4 => Ok(Instruction::LoadBool {
            dst: reader.u16()?,
            value: reader.bool()?,
        }),
        5 => Ok(Instruction::Move {
            dst: reader.u16()?,
            src: reader.u16()?,
        }),
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
        10 => read_unary(reader, |dst, src| Instruction::I32Neg { dst, src }),
        11 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitAnd {
            dst,
            lhs,
            rhs,
        }),
        12 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitOr {
            dst,
            lhs,
            rhs,
        }),
        13 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitXor {
            dst,
            lhs,
            rhs,
        }),
        14 => read_unary(reader, |dst, src| Instruction::I32BitNot { dst, src }),
        15 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shl {
            dst,
            lhs,
            rhs,
        }),
        16 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shr {
            dst,
            lhs,
            rhs,
        }),
        17 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Eq { dst, lhs, rhs }),
        18 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Ne { dst, lhs, rhs }),
        19 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Lt { dst, lhs, rhs }),
        20 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Le { dst, lhs, rhs }),
        21 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Gt { dst, lhs, rhs }),
        22 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Ge { dst, lhs, rhs }),
        23 => read_unary(reader, |dst, src| Instruction::BoolNot { dst, src }),
        24 => read_binary(reader, |dst, lhs, rhs| Instruction::BoolAnd {
            dst,
            lhs,
            rhs,
        }),
        25 => read_binary(reader, |dst, lhs, rhs| Instruction::BoolOr {
            dst,
            lhs,
            rhs,
        }),
        26 => Ok(Instruction::Jump {
            target: reader.index("jump target")?,
        }),
        27 => Ok(Instruction::JumpIfFalse {
            cond: reader.u16()?,
            target: reader.index("jump target")?,
        }),
        28 => Ok(Instruction::JumpIfTrue {
            cond: reader.u16()?,
            target: reader.index("jump target")?,
        }),
        29 => Ok(Instruction::CallStatic {
            return_register: reader.optional_register()?,
            function_index: reader.index("function")?,
            arguments: reader.register_list()?,
        }),
        30 => Ok(Instruction::Return { src: reader.u16()? }),
        31 => Ok(Instruction::ReturnUnit),
        32 => Ok(Instruction::CallHost {
            return_register: reader.optional_register()?,
            import_id: reader.i32()?,
            arguments: reader.register_list()?,
        }),
        33 => Ok(Instruction::Yield { dst: reader.u16()? }),
        34 => Ok(Instruction::Sleep {
            dst: reader.u16()?,
            ticks: reader.u16()?,
        }),
        other => Err(ImageError::UnknownInstructionTag(other)),
    }
}

fn read_binary(
    reader: &mut Reader<'_>,
    create: fn(u16, u16, u16) -> Instruction,
) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?, reader.u16()?))
}

fn read_unary(
    reader: &mut Reader<'_>,
    create: fn(u16, u16) -> Instruction,
) -> Result<Instruction, ImageError> {
    Ok(create(reader.u16()?, reader.u16()?))
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
        let mut bytes = [0u8; 2];
        bytes.copy_from_slice(self.take(2)?);
        Ok(u16::from_le_bytes(bytes))
    }

    fn i32(&mut self) -> Result<i32, ImageError> {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, ImageError> {
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, ImageError> {
        let length = self.length()?;
        String::from_utf8(self.take(length)?.to_vec()).map_err(|_| ImageError::InvalidUtf8)
    }

    fn bool(&mut self) -> Result<bool, ImageError> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            other => Err(ImageError::InvalidBool(other)),
        }
    }

    fn optional_register(&mut self) -> Result<Option<u16>, ImageError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.u16()?)),
            other => Err(ImageError::InvalidBool(other)),
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
        let length = self.i32()?;
        if length < 0 {
            return Err(ImageError::NegativeLength(length));
        }
        Ok(length as usize)
    }
}
