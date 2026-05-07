use crate::value::VmValue;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmSignal {
    Halt(VmValue),
    Pause,
    Yield,
    Sleep(i64),
    WaitEvent(Option<String>),
    HostCall {
        module_name: String,
        function_name: String,
        arguments: Vec<VmValue>,
    },
}

const SIGNAL_HALT: u8 = 0;
const SIGNAL_PAUSE: u8 = 1;
const SIGNAL_YIELD: u8 = 2;
const SIGNAL_SLEEP: u8 = 3;
const SIGNAL_HOST_CALL: u8 = 4;
const SIGNAL_WAIT_EVENT: u8 = 5;
const SIGNAL_ERROR: u8 = 255;

const VALUE_UNIT: u8 = 0;
const VALUE_NULL: u8 = 1;
const VALUE_BOOL: u8 = 2;
const VALUE_INT: u8 = 3;
const VALUE_LONG: u8 = 4;
const VALUE_STRING: u8 = 5;
const VALUE_RECORD: u8 = 6;

pub fn encode_signal(signal: &VmSignal) -> Vec<u8> {
    let mut writer = Writer::default();
    match signal {
        VmSignal::Halt(value) => {
            writer.u8(SIGNAL_HALT);
            writer.value(value);
        }
        VmSignal::Pause => writer.u8(SIGNAL_PAUSE),
        VmSignal::Yield => writer.u8(SIGNAL_YIELD),
        VmSignal::Sleep(ticks) => {
            writer.u8(SIGNAL_SLEEP);
            writer.i64(*ticks);
        }
        VmSignal::WaitEvent(filter) => {
            writer.u8(SIGNAL_WAIT_EVENT);
            match filter {
                Some(filter) => {
                    writer.u8(1);
                    writer.string(filter);
                }
                None => writer.u8(0),
            }
        }
        VmSignal::HostCall {
            module_name,
            function_name,
            arguments,
        } => {
            writer.u8(SIGNAL_HOST_CALL);
            writer.string(module_name);
            writer.string(function_name);
            writer.i32(arguments.len() as i32);
            for argument in arguments {
                writer.value(argument);
            }
        }
    }
    writer.finish()
}

pub fn encode_error(message: impl AsRef<str>) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.u8(SIGNAL_ERROR);
    writer.string(message.as_ref());
    writer.finish()
}

pub fn encode_value(value: &VmValue) -> Vec<u8> {
    let mut writer = Writer::default();
    writer.value(value);
    writer.finish()
}

pub fn decode_value(bytes: &[u8]) -> Result<VmValue, String> {
    let mut reader = Reader { bytes, offset: 0 };
    let value = reader.value()?;
    if reader.offset != bytes.len() {
        return Err("trailing bytes after native VM value".to_string());
    }
    Ok(value)
}

#[derive(Default)]
struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    fn finish(self) -> Vec<u8> {
        self.bytes
    }

    fn u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn i32(&mut self, value: i32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn i64(&mut self, value: i64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn string(&mut self, value: &str) {
        self.i32(value.len() as i32);
        self.bytes.extend_from_slice(value.as_bytes());
    }

    fn value(&mut self, value: &VmValue) {
        match value {
            VmValue::Unit => self.u8(VALUE_UNIT),
            VmValue::Null => self.u8(VALUE_NULL),
            VmValue::Bool(value) => {
                self.u8(VALUE_BOOL);
                self.u8(u8::from(*value));
            }
            VmValue::Int(value) => {
                self.u8(VALUE_INT);
                self.i32(*value);
            }
            VmValue::Long(value) => {
                self.u8(VALUE_LONG);
                self.i64(*value);
            }
            VmValue::String(value) => {
                self.u8(VALUE_STRING);
                self.string(value);
            }
            VmValue::Record { type_name, fields } => {
                self.u8(VALUE_RECORD);
                self.string(type_name);
                self.i32(fields.len() as i32);
                for (name, value) in fields {
                    self.string(name);
                    self.value(value);
                }
            }
            VmValue::ObjectRef(value) => {
                self.u8(VALUE_STRING);
                self.string(&format!("object#{value}"));
            }
        }
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl Reader<'_> {
    fn u8(&mut self) -> Result<u8, String> {
        let value = self
            .bytes
            .get(self.offset)
            .copied()
            .ok_or_else(|| "unexpected end of native VM value".to_string())?;
        self.offset += 1;
        Ok(value)
    }

    fn take(&mut self, count: usize) -> Result<&[u8], String> {
        let end = self
            .offset
            .checked_add(count)
            .ok_or_else(|| "unexpected end of native VM value".to_string())?;
        let slice = self
            .bytes
            .get(self.offset..end)
            .ok_or_else(|| "unexpected end of native VM value".to_string())?;
        self.offset = end;
        Ok(slice)
    }

    fn i32(&mut self) -> Result<i32, String> {
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(self.take(4)?);
        Ok(i32::from_le_bytes(bytes))
    }

    fn i64(&mut self) -> Result<i64, String> {
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(self.take(8)?);
        Ok(i64::from_le_bytes(bytes))
    }

    fn string(&mut self) -> Result<String, String> {
        let length = self.i32()?;
        if length < 0 {
            return Err(format!("negative native VM string length {length}"));
        }
        String::from_utf8(self.take(length as usize)?.to_vec())
            .map_err(|_| "invalid native VM string utf-8".to_string())
    }

    fn value(&mut self) -> Result<VmValue, String> {
        match self.u8()? {
            VALUE_UNIT => Ok(VmValue::Unit),
            VALUE_NULL => Ok(VmValue::Null),
            VALUE_BOOL => Ok(VmValue::Bool(self.u8()? != 0)),
            VALUE_INT => Ok(VmValue::Int(self.i32()?)),
            VALUE_LONG => Ok(VmValue::Long(self.i64()?)),
            VALUE_STRING => Ok(VmValue::String(self.string()?)),
            VALUE_RECORD => {
                let type_name = self.string()?;
                let field_count = self.i32()?;
                if field_count < 0 {
                    return Err(format!(
                        "negative native VM record field count {field_count}"
                    ));
                }
                let mut fields = Vec::with_capacity(field_count as usize);
                for _ in 0..field_count {
                    let name = self.string()?;
                    let value = self.value()?;
                    fields.push((name, value));
                }
                Ok(VmValue::Record { type_name, fields })
            }
            other => Err(format!("unknown native VM value tag {other}")),
        }
    }
}
