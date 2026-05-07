use thiserror::Error;

pub const VERSION: u8 = 1;

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
    #[error("unknown constant tag {0}")]
    UnknownConstantTag(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Image {
    pub language_version: String,
    pub target_abi_version: u16,
    pub capabilities: Vec<String>,
    pub constants: Vec<Constant>,
    pub host_imports: Vec<HostImport>,
    pub entry_function_index: i32,
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
    pub frame_size: i32,
    pub code: Vec<u8>,
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
    let target_abi_version = reader.u16()?;
    let capabilities = reader.list(read_string)?;
    let constants = reader.list(read_constant)?;
    let host_imports = reader.list(read_host_import)?;
    let entry_function_index = reader.i32()?;
    let functions = reader.list(read_function)?;
    Ok(Image { language_version, target_abi_version, capabilities, constants, host_imports, entry_function_index, functions })
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
        frame_size: reader.i32()?,
        code: reader.byte_vec()?,
    })
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], ImageError> {
        let end = self.offset.checked_add(count).ok_or(ImageError::UnexpectedEnd)?;
        let slice = self.bytes.get(self.offset..end).ok_or(ImageError::UnexpectedEnd)?;
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

    fn byte_vec(&mut self) -> Result<Vec<u8>, ImageError> {
        let length = self.length()?;
        Ok(self.take(length)?.to_vec())
    }

    fn list<T>(&mut self, read: fn(&mut Reader<'a>) -> Result<T, ImageError>) -> Result<Vec<T>, ImageError> {
        let length = self.length()?;
        let mut values = Vec::with_capacity(length);
        for _ in 0..length {
            values.push(read(self)?);
        }
        Ok(values)
    }

    fn length(&mut self) -> Result<usize, ImageError> {
        let length = self.i32()?;
        if length < 0 {
            return Err(ImageError::NegativeLength(length));
        }
        Ok(length as usize)
    }
}