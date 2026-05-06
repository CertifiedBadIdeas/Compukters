use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum AbiError {
	#[error("invalid magic")]
	InvalidMagic,
	#[error("unsupported ABI version {0}")]
	UnsupportedVersion(u8),
	#[error("unexpected end of input")]
	UnexpectedEnd,
	#[error("invalid utf-8 string")]
	InvalidUtf8,
	#[error("negative length {0}")]
	NegativeLength(i32),
	#[error("unknown instruction tag {0}")]
	UnknownInstruction(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Module {
	pub name: String,
	pub entry_function_index: i32,
	pub records: Vec<Record>,
	pub classes: Vec<Class>,
	pub functions: Vec<Function>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Record {
	pub name: String,
	pub fields: Vec<Field>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Field {
	pub name: String,
	pub type_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Class {
	pub name: String,
	pub fields: Vec<ClassField>,
	pub init_function_index: Option<i32>,
	pub instance_methods: Vec<MethodRef>,
	pub static_methods: Vec<MethodRef>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClassField {
	pub name: String,
	pub type_name: String,
	pub mutable: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MethodRef {
	pub name: String,
	pub function_index: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Function {
	pub name: String,
	pub parameters: Vec<Local>,
	pub locals: Vec<Local>,
	pub return_type: String,
	pub instructions: Vec<Instruction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Local {
	pub name: String,
	pub type_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Instruction {
	PushInt(i32),
	PushLong(i64),
	PushString(String),
	PushBool(bool),
	PushUnit,
	PushNull,
	LoadLocal(i32),
	StoreLocal(i32),
	Pop,
	Jump(i32),
	JumpIfFalse(i32),
	JumpIfTrue(i32),
	CallFunction { function_index: i32, argument_count: i32 },
	CallBuiltin { module_name: String, function_name: String, argument_count: i32 },
	GetField(String),
	SetField(String),
	ConstructRecord { type_name: String, field_names: Vec<String> },
	ConstructClass { class_name: String, field_names: Vec<String> },
	ConstructArray,
	ConstructList(i32),
	ConstructMap(i32),
	IndexGet,
	IndexSet,
	CallCollectionMethod { method_name: String, argument_count: i32 },
	CallMethod { method_name: String, argument_count: i32 },
	CallStaticMethod { class_name: String, method_name: String, argument_count: i32 },
	Binary(u8),
	Unary(u8),
	Return,
}

pub fn decode_module(bytes: &[u8]) -> Result<Module, AbiError> {
	let mut reader = Reader { bytes, offset: 0 };
	if reader.take(4)? != b"CKVM" {
		return Err(AbiError::InvalidMagic);
	}
	let version = reader.u8()?;
	if version != 1 {
		return Err(AbiError::UnsupportedVersion(version));
	}
	let name = reader.string()?;
	let entry_function_index = reader.i32()?;
	let records = reader.list(read_record)?;
	let classes = reader.list(read_class)?;
	let functions = reader.list(read_function)?;
	Ok(Module { name, entry_function_index, records, classes, functions })
}

fn read_record(reader: &mut Reader<'_>) -> Result<Record, AbiError> {
	Ok(Record { name: reader.string()?, fields: reader.list(read_field)? })
}

fn read_field(reader: &mut Reader<'_>) -> Result<Field, AbiError> {
	Ok(Field { name: reader.string()?, type_name: reader.string()? })
}

fn read_class(reader: &mut Reader<'_>) -> Result<Class, AbiError> {
	let name = reader.string()?;
	let fields = reader.list(read_class_field)?;
	let init = reader.i32()?;
	let instance_methods = reader.list(read_method_ref)?;
	let static_methods = reader.list(read_method_ref)?;
	Ok(Class {
		name,
		fields,
		init_function_index: if init >= 0 { Some(init) } else { None },
		instance_methods,
		static_methods,
	})
}

fn read_class_field(reader: &mut Reader<'_>) -> Result<ClassField, AbiError> {
	Ok(ClassField { name: reader.string()?, type_name: reader.string()?, mutable: reader.u8()? != 0 })
}

fn read_method_ref(reader: &mut Reader<'_>) -> Result<MethodRef, AbiError> {
	Ok(MethodRef { name: reader.string()?, function_index: reader.i32()? })
}

fn read_function(reader: &mut Reader<'_>) -> Result<Function, AbiError> {
	let name = reader.string()?;
	let parameters = reader.list(read_local)?;
	let locals = reader.list(read_local)?;
	let return_type = reader.string()?;
	let instructions = reader.list(read_instruction)?;
	Ok(Function { name, parameters, locals, return_type, instructions })
}

fn read_local(reader: &mut Reader<'_>) -> Result<Local, AbiError> {
	Ok(Local { name: reader.string()?, type_name: reader.string()? })
}

fn read_instruction(reader: &mut Reader<'_>) -> Result<Instruction, AbiError> {
	let tag = reader.u8()?;
	match tag {
		1 => Ok(Instruction::PushInt(reader.i32()?)),
		2 => Ok(Instruction::PushLong(reader.i64()?)),
		3 => Ok(Instruction::PushString(reader.string()?)),
		4 => Ok(Instruction::PushBool(reader.u8()? != 0)),
		5 => Ok(Instruction::PushUnit),
		6 => Ok(Instruction::PushNull),
		7 => Ok(Instruction::LoadLocal(reader.i32()?)),
		8 => Ok(Instruction::StoreLocal(reader.i32()?)),
		9 => Ok(Instruction::Pop),
		10 => Ok(Instruction::Jump(reader.i32()?)),
		11 => Ok(Instruction::JumpIfFalse(reader.i32()?)),
		12 => Ok(Instruction::JumpIfTrue(reader.i32()?)),
		13 => Ok(Instruction::CallFunction { function_index: reader.i32()?, argument_count: reader.i32()? }),
		14 => Ok(Instruction::CallBuiltin {
			module_name: reader.string()?,
			function_name: reader.string()?,
			argument_count: reader.i32()?,
		}),
		15 => Ok(Instruction::GetField(reader.string()?)),
		16 => Ok(Instruction::SetField(reader.string()?)),
		17 => Ok(Instruction::ConstructRecord { type_name: reader.string()?, field_names: reader.list(read_string)? }),
		18 => Ok(Instruction::ConstructClass { class_name: reader.string()?, field_names: reader.list(read_string)? }),
		19 => Ok(Instruction::ConstructArray),
		20 => Ok(Instruction::ConstructList(reader.i32()?)),
		21 => Ok(Instruction::ConstructMap(reader.i32()?)),
		22 => Ok(Instruction::IndexGet),
		23 => Ok(Instruction::IndexSet),
		24 => Ok(Instruction::CallCollectionMethod { method_name: reader.string()?, argument_count: reader.i32()? }),
		25 => Ok(Instruction::CallMethod { method_name: reader.string()?, argument_count: reader.i32()? }),
		26 => Ok(Instruction::CallStaticMethod {
			class_name: reader.string()?,
			method_name: reader.string()?,
			argument_count: reader.i32()?,
		}),
		27 => Ok(Instruction::Binary(reader.u8()?)),
		28 => Ok(Instruction::Unary(reader.u8()?)),
		29 => Ok(Instruction::Return),
		other => Err(AbiError::UnknownInstruction(other)),
	}
}

fn read_string(reader: &mut Reader<'_>) -> Result<String, AbiError> {
	reader.string()
}

struct Reader<'a> {
	bytes: &'a [u8],
	offset: usize,
}

impl<'a> Reader<'a> {
	fn take(&mut self, count: usize) -> Result<&'a [u8], AbiError> {
		let end = self.offset.checked_add(count).ok_or(AbiError::UnexpectedEnd)?;
		let slice = self.bytes.get(self.offset..end).ok_or(AbiError::UnexpectedEnd)?;
		self.offset = end;
		Ok(slice)
	}

	fn u8(&mut self) -> Result<u8, AbiError> {
		Ok(self.take(1)?[0])
	}

	fn i32(&mut self) -> Result<i32, AbiError> {
		let mut bytes = [0u8; 4];
		bytes.copy_from_slice(self.take(4)?);
		Ok(i32::from_le_bytes(bytes))
	}

	fn i64(&mut self) -> Result<i64, AbiError> {
		let mut bytes = [0u8; 8];
		bytes.copy_from_slice(self.take(8)?);
		Ok(i64::from_le_bytes(bytes))
	}

	fn string(&mut self) -> Result<String, AbiError> {
		let len = self.i32()?;
		if len < 0 {
			return Err(AbiError::NegativeLength(len));
		}
		String::from_utf8(self.take(len as usize)?.to_vec()).map_err(|_| AbiError::InvalidUtf8)
	}

	fn list<T>(&mut self, mut read: impl FnMut(&mut Reader<'a>) -> Result<T, AbiError>) -> Result<Vec<T>, AbiError> {
		let len = self.i32()?;
		if len < 0 {
			return Err(AbiError::NegativeLength(len));
		}
		let mut values = Vec::with_capacity(len as usize);
		for _ in 0..len {
			values.push(read(self)?);
		}
		Ok(values)
	}
}
