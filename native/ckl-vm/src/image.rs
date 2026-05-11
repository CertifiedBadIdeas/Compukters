use thiserror::Error;

pub const VERSION: u8 = 3;

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
    #[error("unknown typed register tag {0}")]
    UnknownRegisterTag(u8),
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
    pub i32_register_count: usize,
    pub i64_register_count: usize,
    pub bool_register_count: usize,
    pub ref_register_count: usize,
    pub parameters: Vec<TypedRegister>,
    pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TypedRegister {
    I32(u16),
    I64(u16),
    Bool(u16),
    Ref(u16),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
    I32Const {
        dst: u16,
        constant_index: usize,
    },
    I64Const {
        dst: u16,
        constant_index: usize,
    },
    BoolConst {
        dst: u16,
        value: bool,
    },
    RefConst {
        dst: u16,
        constant_index: usize,
    },
    LoadUnit {
        dst: u16,
    },
    LoadNull {
        dst: u16,
    },
    I32Move {
        dst: u16,
        src: u16,
    },
    I64Move {
        dst: u16,
        src: u16,
    },
    BoolMove {
        dst: u16,
        src: u16,
    },
    RefMove {
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
        return_register: Option<TypedRegister>,
        function_index: usize,
        arguments: Vec<TypedRegister>,
    },
    Return {
        src: TypedRegister,
    },
    ReturnUnit,
    CallHost {
        return_register: Option<TypedRegister>,
        import_id: i32,
        arguments: Vec<TypedRegister>,
    },
    Yield {
        dst: u16,
    },
    Sleep {
        dst: u16,
        ticks: TypedRegister,
    },
    ConstructRecord {
        dst: u16,
        type_name_constant_index: usize,
        field_name_constant_indices: Vec<usize>,
        field_values: Vec<TypedRegister>,
    },
    GetField {
        dst: TypedRegister,
        receiver: u16,
        field_name_constant_index: usize,
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
        i32_register_count: usize::from(reader.u16()?),
        i64_register_count: usize::from(reader.u16()?),
        bool_register_count: usize::from(reader.u16()?),
        ref_register_count: usize::from(reader.u16()?),
        parameters: reader.typed_register_list()?,
        instructions: reader.list(read_instruction)?,
    })
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, ImageError> {
    match reader.u8()? {
        1 => Ok(Instruction::I32Const {
            dst: reader.u16()?,
            constant_index: reader.index("constant")?,
        }),
        2 => Ok(Instruction::I64Const {
            dst: reader.u16()?,
            constant_index: reader.index("constant")?,
        }),
        3 => Ok(Instruction::BoolConst {
            dst: reader.u16()?,
            value: reader.bool()?,
        }),
        4 => Ok(Instruction::RefConst {
            dst: reader.u16()?,
            constant_index: reader.index("constant")?,
        }),
        5 => Ok(Instruction::LoadUnit { dst: reader.u16()? }),
        6 => Ok(Instruction::LoadNull { dst: reader.u16()? }),
        7 => Ok(Instruction::I32Move {
            dst: reader.u16()?,
            src: reader.u16()?,
        }),
        8 => Ok(Instruction::I64Move {
            dst: reader.u16()?,
            src: reader.u16()?,
        }),
        9 => Ok(Instruction::BoolMove {
            dst: reader.u16()?,
            src: reader.u16()?,
        }),
        10 => Ok(Instruction::RefMove {
            dst: reader.u16()?,
            src: reader.u16()?,
        }),
        11 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Add {
            dst,
            lhs,
            rhs,
        }),
        12 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Sub {
            dst,
            lhs,
            rhs,
        }),
        13 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Mul {
            dst,
            lhs,
            rhs,
        }),
        14 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Div {
            dst,
            lhs,
            rhs,
        }),
        15 => read_unary(reader, |dst, src| Instruction::I32Neg { dst, src }),
        16 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitAnd {
            dst,
            lhs,
            rhs,
        }),
        17 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitOr {
            dst,
            lhs,
            rhs,
        }),
        18 => read_binary(reader, |dst, lhs, rhs| Instruction::I32BitXor {
            dst,
            lhs,
            rhs,
        }),
        19 => read_unary(reader, |dst, src| Instruction::I32BitNot { dst, src }),
        20 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shl {
            dst,
            lhs,
            rhs,
        }),
        21 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Shr {
            dst,
            lhs,
            rhs,
        }),
        22 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Eq { dst, lhs, rhs }),
        23 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Ne { dst, lhs, rhs }),
        24 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Lt { dst, lhs, rhs }),
        25 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Le { dst, lhs, rhs }),
        26 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Gt { dst, lhs, rhs }),
        27 => read_binary(reader, |dst, lhs, rhs| Instruction::I32Ge { dst, lhs, rhs }),
        28 => read_unary(reader, |dst, src| Instruction::BoolNot { dst, src }),
        29 => read_binary(reader, |dst, lhs, rhs| Instruction::BoolAnd {
            dst,
            lhs,
            rhs,
        }),
        30 => read_binary(reader, |dst, lhs, rhs| Instruction::BoolOr {
            dst,
            lhs,
            rhs,
        }),
        31 => Ok(Instruction::Jump {
            target: reader.index("jump target")?,
        }),
        32 => Ok(Instruction::JumpIfFalse {
            cond: reader.u16()?,
            target: reader.index("jump target")?,
        }),
        33 => Ok(Instruction::JumpIfTrue {
            cond: reader.u16()?,
            target: reader.index("jump target")?,
        }),
        34 => Ok(Instruction::CallStatic {
            return_register: reader.optional_typed_register()?,
            function_index: reader.index("function")?,
            arguments: reader.typed_register_list()?,
        }),
        35 => Ok(Instruction::Return {
            src: reader.typed_register()?,
        }),
        36 => Ok(Instruction::ReturnUnit),
        37 => Ok(Instruction::CallHost {
            return_register: reader.optional_typed_register()?,
            import_id: reader.i32()?,
            arguments: reader.typed_register_list()?,
        }),
        38 => Ok(Instruction::Yield { dst: reader.u16()? }),
        39 => Ok(Instruction::Sleep {
            dst: reader.u16()?,
            ticks: reader.typed_register()?,
        }),
        40 => {
            let dst = reader.u16()?;
            let type_name_constant_index = reader.index("record type name constant")?;
            let field_count = reader.length()?;
            let mut field_name_constant_indices = Vec::with_capacity(field_count);
            for _ in 0..field_count {
                field_name_constant_indices.push(reader.index("record field name constant")?);
            }
            Ok(Instruction::ConstructRecord {
                dst,
                type_name_constant_index,
                field_name_constant_indices,
                field_values: reader.typed_register_list()?,
            })
        }
        41 => Ok(Instruction::GetField {
            dst: reader.typed_register()?,
            receiver: reader.u16()?,
            field_name_constant_index: reader.index("field name constant")?,
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

    fn typed_register(&mut self) -> Result<TypedRegister, ImageError> {
        let tag = self.u8()?;
        let index = self.u16()?;
        match tag {
            1 => Ok(TypedRegister::I32(index)),
            2 => Ok(TypedRegister::I64(index)),
            3 => Ok(TypedRegister::Bool(index)),
            4 => Ok(TypedRegister::Ref(index)),
            other => Err(ImageError::UnknownRegisterTag(other)),
        }
    }

    fn optional_typed_register(&mut self) -> Result<Option<TypedRegister>, ImageError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(self.typed_register()?)),
            other => Err(ImageError::InvalidBool(other)),
        }
    }

    fn typed_register_list(&mut self) -> Result<Vec<TypedRegister>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(self.typed_register()?);
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
