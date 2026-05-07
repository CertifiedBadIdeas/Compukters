use crate::abi::{Instruction, Module};
use crate::value::VmValue;
use std::collections::HashMap;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum VmError {
    #[error("native VM is waiting for resume")]
    WaitingForResume,
    #[error("native VM is not waiting for resume")]
    NotWaitingForResume,
    #[error("native VM is halted")]
    Halted,
}

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

pub struct VmInstance {
    module: Module,
    frames: Vec<Frame>,
    instruction_budget: usize,
    instructions_since_pause: usize,
    state: VmState,
    heap: HashMap<u32, VmObject>,
    next_object_id: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum VmState {
    Ready,
    WaitingForResume,
    Halted,
}

struct Frame {
    function_index: usize,
    instruction_pointer: usize,
    locals: Vec<VmValue>,
    stack: Vec<VmValue>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum VmObject {
    Class {
        class_name: String,
        fields: Vec<(String, VmValue)>,
    },
    Array {
        elements: Vec<VmValue>,
    },
    List {
        elements: Vec<VmValue>,
    },
    Map {
        entries: Vec<(VmValue, VmValue)>,
    },
}

impl VmInstance {
    pub fn new(module: Module, instruction_budget: usize) -> Self {
        let entry = module.entry_function_index as usize;
        let entry_frame = create_frame(&module, entry, vec![]);
        Self {
            module,
            frames: vec![entry_frame],
            instruction_budget: instruction_budget.max(1),
            instructions_since_pause: 0,
            state: VmState::Ready,
            heap: HashMap::new(),
            next_object_id: 1,
        }
    }

    pub fn run_until_signal(&mut self) -> Result<VmSignal, VmError> {
        match self.state {
            VmState::Ready => {}
            VmState::WaitingForResume => return Err(VmError::WaitingForResume),
            VmState::Halted => return Err(VmError::Halted),
        }

        loop {
            let instruction = match self.next_instruction() {
                Some(instruction) => instruction,
                None => return self.handle_return(VmValue::Unit),
            };
            self.instructions_since_pause += 1;

            match instruction {
                Instruction::PushInt(value) => {
                    self.current_frame_mut().stack.push(VmValue::Int(value))
                }
                Instruction::PushLong(value) => {
                    self.current_frame_mut().stack.push(VmValue::Long(value))
                }
                Instruction::PushString(value) => {
                    self.current_frame_mut().stack.push(VmValue::String(value))
                }
                Instruction::PushBool(value) => {
                    self.current_frame_mut().stack.push(VmValue::Bool(value))
                }
                Instruction::PushUnit => self.current_frame_mut().stack.push(VmValue::Unit),
                Instruction::PushNull => self.current_frame_mut().stack.push(VmValue::Null),
                Instruction::LoadLocal(slot) => {
                    let value = self.current_frame_mut().locals[slot as usize].clone();
                    self.current_frame_mut().stack.push(value);
                }
                Instruction::StoreLocal(slot) => {
                    let value = self.pop();
                    let slot = slot as usize;
                    let frame = self.current_frame_mut();
                    while frame.locals.len() <= slot {
                        frame.locals.push(VmValue::Unit);
                    }
                    frame.locals[slot] = value;
                }
                Instruction::Pop => {
                    self.pop();
                }
                Instruction::Jump(target) => {
                    self.current_frame_mut().instruction_pointer = target as usize;
                }
                Instruction::JumpIfFalse(target) => {
                    if !as_bool(&self.pop()) {
                        self.current_frame_mut().instruction_pointer = target as usize;
                    }
                }
                Instruction::JumpIfTrue(target) => {
                    if as_bool(&self.pop()) {
                        self.current_frame_mut().instruction_pointer = target as usize;
                    }
                }
                Instruction::Binary(operator) => {
                    let right = self.pop();
                    let left = self.pop();
                    self.current_frame_mut()
                        .stack
                        .push(apply_binary(operator, left, right));
                }
                Instruction::Unary(operator) => {
                    let value = self.pop();
                    self.current_frame_mut()
                        .stack
                        .push(apply_unary(operator, value));
                }
                Instruction::CallFunction {
                    function_index,
                    argument_count,
                } => {
                    let arguments = self.pop_many(argument_count as usize);
                    let frame = create_frame(&self.module, function_index as usize, arguments);
                    self.frames.push(frame);
                }
                Instruction::CallMethod {
                    method_name,
                    argument_count,
                } => {
                    let arguments = self.pop_many(argument_count as usize);
                    let receiver = self.pop();
                    let object_id = object_id(&receiver);
                    let class_name = match self.heap.get(&object_id).expect("object exists") {
                        VmObject::Class { class_name, .. } => class_name.clone(),
                        _ => panic!("method receiver is not a class instance"),
                    };
                    let class_info = self
                        .module
                        .classes
                        .iter()
                        .find(|class| class.name == class_name)
                        .expect("class exists");
                    let function_index = class_info
                        .instance_methods
                        .iter()
                        .find(|method| method.name == method_name)
                        .expect("method exists")
                        .function_index;
                    self.frames.push(create_frame(
                        &self.module,
                        function_index as usize,
                        [vec![receiver], arguments].concat(),
                    ));
                }
                Instruction::CallStaticMethod {
                    class_name,
                    method_name,
                    argument_count,
                } => {
                    let arguments = self.pop_many(argument_count as usize);
                    let class_info = self
                        .module
                        .classes
                        .iter()
                        .find(|class| class.name == class_name)
                        .expect("class exists");
                    let function_index = class_info
                        .static_methods
                        .iter()
                        .find(|method| method.name == method_name)
                        .expect("static method exists")
                        .function_index;
                    self.frames.push(create_frame(
                        &self.module,
                        function_index as usize,
                        arguments,
                    ));
                }
                Instruction::Return => {
                    let result = self
                        .current_frame_mut()
                        .stack
                        .pop()
                        .unwrap_or(VmValue::Unit);
                    return self.handle_return(result);
                }
                Instruction::GetField(field_name) => {
                    let receiver = self.pop();
                    let value = self
                        .field_value(&receiver, &field_name)
                        .cloned()
                        .expect("field exists");
                    self.current_frame_mut().stack.push(value);
                }
                Instruction::SetField(field_name) => {
                    let value = self.pop();
                    let receiver = self.pop();
                    let object_id = object_id(&receiver);
                    match self.heap.get_mut(&object_id).expect("object exists") {
                        VmObject::Class { fields, .. } => {
                            set_named_value(fields, &field_name, value)
                        }
                        _ => panic!("field assignment receiver is not a class instance"),
                    }
                    self.current_frame_mut().stack.push(VmValue::Unit);
                }
                Instruction::ConstructRecord {
                    type_name,
                    field_names,
                } => {
                    let values = self.pop_many(field_names.len());
                    self.current_frame_mut().stack.push(VmValue::Record {
                        type_name,
                        fields: field_names.into_iter().zip(values).collect(),
                    });
                }
                Instruction::ConstructClass {
                    class_name,
                    field_names,
                } => {
                    let values = self.pop_many(field_names.len());
                    let fields = field_names.into_iter().zip(values).collect();
                    let object = self.allocate(VmObject::Class { class_name, fields });
                    self.current_frame_mut().stack.push(object);
                }
                Instruction::ConstructArray => {
                    let default = self.pop();
                    let size = as_i64(&self.pop()) as usize;
                    let object = self.allocate(VmObject::Array {
                        elements: vec![default; size],
                    });
                    self.current_frame_mut().stack.push(object);
                }
                Instruction::ConstructList(element_count) => {
                    let elements = self.pop_many(element_count as usize);
                    let object = self.allocate(VmObject::List { elements });
                    self.current_frame_mut().stack.push(object);
                }
                Instruction::ConstructMap(entry_count) => {
                    let values = self.pop_many(entry_count as usize * 2);
                    let entries = values
                        .chunks(2)
                        .map(|entry| (entry[0].clone(), entry[1].clone()))
                        .collect();
                    let object = self.allocate(VmObject::Map { entries });
                    self.current_frame_mut().stack.push(object);
                }
                Instruction::IndexGet => {
                    let index = self.pop();
                    let receiver = self.pop();
                    let value = self.index_get(receiver, index);
                    self.current_frame_mut().stack.push(value);
                }
                Instruction::IndexSet => {
                    let value = self.pop();
                    let index = self.pop();
                    let receiver = self.pop();
                    self.index_set(receiver, index, value);
                    self.current_frame_mut().stack.push(VmValue::Unit);
                }
                Instruction::CallCollectionMethod {
                    method_name,
                    argument_count,
                } => {
                    let arguments = self.pop_many(argument_count as usize);
                    let receiver = self.pop();
                    let value = self.call_collection_method(receiver, &method_name, arguments);
                    self.current_frame_mut().stack.push(value);
                }
                Instruction::CallBuiltin {
                    module_name,
                    function_name,
                    argument_count,
                } => {
                    let mut arguments = Vec::with_capacity(argument_count as usize);
                    for _ in 0..argument_count {
                        arguments.push(self.pop());
                    }
                    arguments.reverse();
                    if module_name.is_empty() && function_name == "yield" {
                        self.state = VmState::WaitingForResume;
                        return Ok(VmSignal::Yield);
                    }
                    if module_name.is_empty() && function_name == "sleep" {
                        self.state = VmState::WaitingForResume;
                        return Ok(VmSignal::Sleep(as_i64(
                            arguments.first().unwrap_or(&VmValue::Long(0)),
                        )));
                    }
                    if module_name == "events" && function_name == "pull" {
                        self.state = VmState::WaitingForResume;
                        return Ok(VmSignal::WaitEvent(arguments.first().map(as_string)));
                    }
                    self.state = VmState::WaitingForResume;
                    return Ok(VmSignal::HostCall {
                        module_name,
                        function_name,
                        arguments,
                    });
                }
            }

            if self.instructions_since_pause >= self.instruction_budget {
                self.instructions_since_pause = 0;
                return Ok(VmSignal::Pause);
            }
        }
    }

    pub fn resume_with(&mut self, value: VmValue) -> Result<(), VmError> {
        if self.state != VmState::WaitingForResume {
            return Err(VmError::NotWaitingForResume);
        }
        self.current_frame_mut().stack.push(value);
        self.state = VmState::Ready;
        Ok(())
    }

    fn next_instruction(&mut self) -> Option<Instruction> {
        let frame = self.frames.last_mut()?;
        let function = &self.module.functions[frame.function_index];
        let instruction = function
            .instructions
            .get(frame.instruction_pointer)
            .cloned();
        if instruction.is_some() {
            frame.instruction_pointer += 1;
        }
        instruction
    }

    fn current_frame_mut(&mut self) -> &mut Frame {
        self.frames.last_mut().expect("VM has an active frame")
    }

    fn pop(&mut self) -> VmValue {
        self.current_frame_mut()
            .stack
            .pop()
            .expect("VM stack value")
    }

    fn pop_many(&mut self, count: usize) -> Vec<VmValue> {
        let mut values = Vec::with_capacity(count);
        for _ in 0..count {
            values.push(self.pop());
        }
        values.reverse();
        values
    }

    fn handle_return(&mut self, result: VmValue) -> Result<VmSignal, VmError> {
        self.frames.pop();
        if let Some(caller) = self.frames.last_mut() {
            caller.stack.push(result);
            self.run_until_signal()
        } else {
            self.state = VmState::Halted;
            Ok(VmSignal::Halt(result))
        }
    }

    fn allocate(&mut self, object: VmObject) -> VmValue {
        let id = self.next_object_id;
        self.next_object_id += 1;
        self.heap.insert(id, object);
        VmValue::ObjectRef(id)
    }

    fn field_value<'a>(&'a self, receiver: &'a VmValue, field_name: &str) -> Option<&'a VmValue> {
        match receiver {
            VmValue::Record { fields, .. } => named_value(fields, field_name),
            VmValue::ObjectRef(id) => match self.heap.get(id)? {
                VmObject::Class { fields, .. } => named_value(fields, field_name),
                _ => None,
            },
            _ => None,
        }
    }

    fn object(&self, receiver: VmValue) -> &VmObject {
        let id = object_id(&receiver);
        self.heap.get(&id).expect("object exists")
    }

    fn object_mut(&mut self, receiver: VmValue) -> &mut VmObject {
        let id = object_id(&receiver);
        self.heap.get_mut(&id).expect("object exists")
    }

    fn index_get(&self, receiver: VmValue, index: VmValue) -> VmValue {
        match self.object(receiver) {
            VmObject::Array { elements } | VmObject::List { elements } => {
                elements[checked_index(as_i64(&index) as usize, elements.len())].clone()
            }
            VmObject::Map { entries } => entries
                .iter()
                .find(|(key, _)| key == &index)
                .map(|(_, value)| value.clone())
                .unwrap_or(VmValue::Null),
            VmObject::Class { .. } => panic!("class instance is not indexable"),
        }
    }

    fn index_set(&mut self, receiver: VmValue, index: VmValue, value: VmValue) {
        match self.object_mut(receiver) {
            VmObject::Array { elements } | VmObject::List { elements } => {
                let index = checked_index(as_i64(&index) as usize, elements.len());
                elements[index] = value;
            }
            VmObject::Map { entries } => set_map_entry(entries, index, value),
            VmObject::Class { .. } => panic!("class instance is not index-assignable"),
        }
    }

    fn call_collection_method(
        &mut self,
        receiver: VmValue,
        method_name: &str,
        arguments: Vec<VmValue>,
    ) -> VmValue {
        let receiver_id = object_id(&receiver);
        if method_name == "keys" || method_name == "values" {
            let elements = match self.heap.get(&receiver_id).expect("object exists") {
                VmObject::Map { entries } if method_name == "keys" => {
                    entries.iter().map(|(key, _)| key.clone()).collect()
                }
                VmObject::Map { entries } => {
                    entries.iter().map(|(_, value)| value.clone()).collect()
                }
                _ => panic!("{method_name} is only available on maps"),
            };
            return self.allocate(VmObject::List { elements });
        }
        match self.heap.get_mut(&receiver_id).expect("object exists") {
            VmObject::Array { elements } => apply_array_method(elements, method_name, arguments),
            VmObject::List { elements } => apply_list_method(elements, method_name, arguments),
            VmObject::Map { entries } => apply_map_method(entries, method_name, arguments),
            VmObject::Class { .. } => {
                panic!("class instance has no collection method {method_name}")
            }
        }
    }
}

fn named_value<'a>(fields: &'a [(String, VmValue)], field_name: &str) -> Option<&'a VmValue> {
    fields
        .iter()
        .find(|(name, _)| name == field_name)
        .map(|(_, value)| value)
}

fn set_named_value(fields: &mut [(String, VmValue)], field_name: &str, value: VmValue) {
    let slot = fields
        .iter_mut()
        .find(|(name, _)| name == field_name)
        .expect("field exists");
    slot.1 = value;
}

fn object_id(value: &VmValue) -> u32 {
    match value {
        VmValue::ObjectRef(id) => *id,
        _ => panic!("expected object reference"),
    }
}

fn checked_index(index: usize, length: usize) -> usize {
    if index >= length {
        panic!("index {index} out of bounds for length {length}");
    }
    index
}

fn set_map_entry(entries: &mut Vec<(VmValue, VmValue)>, key: VmValue, value: VmValue) {
    if let Some((_, existing)) = entries.iter_mut().find(|(entry_key, _)| entry_key == &key) {
        *existing = value;
    } else {
        entries.push((key, value));
    }
}

fn remove_map_entry(entries: &mut Vec<(VmValue, VmValue)>, key: &VmValue) -> VmValue {
    if let Some(index) = entries.iter().position(|(entry_key, _)| entry_key == key) {
        entries.remove(index).1
    } else {
        VmValue::Null
    }
}

fn apply_array_method(
    elements: &mut [VmValue],
    method_name: &str,
    arguments: Vec<VmValue>,
) -> VmValue {
    match method_name {
        "size" => VmValue::Int(elements.len() as i32),
        "get" => elements[checked_index(as_i64(&arguments[0]) as usize, elements.len())].clone(),
        "set" => {
            let index = checked_index(as_i64(&arguments[0]) as usize, elements.len());
            elements[index] = arguments[1].clone();
            VmValue::Unit
        }
        "getOrNull" => {
            let index = as_i64(&arguments[0]);
            if index < 0 || index as usize >= elements.len() {
                VmValue::Null
            } else {
                elements[index as usize].clone()
            }
        }
        other => panic!("unknown array method {other}"),
    }
}

fn apply_list_method(
    elements: &mut Vec<VmValue>,
    method_name: &str,
    arguments: Vec<VmValue>,
) -> VmValue {
    match method_name {
        "size" => VmValue::Int(elements.len() as i32),
        "isEmpty" => VmValue::Bool(elements.is_empty()),
        "get" => elements[checked_index(as_i64(&arguments[0]) as usize, elements.len())].clone(),
        "set" => {
            let index = checked_index(as_i64(&arguments[0]) as usize, elements.len());
            elements[index] = arguments[1].clone();
            VmValue::Unit
        }
        "getOrNull" => {
            let index = as_i64(&arguments[0]);
            if index < 0 || index as usize >= elements.len() {
                VmValue::Null
            } else {
                elements[index as usize].clone()
            }
        }
        "add" => {
            elements.push(arguments[0].clone());
            VmValue::Unit
        }
        "insert" => {
            let index = as_i64(&arguments[0]) as usize;
            elements.insert(index, arguments[1].clone());
            VmValue::Unit
        }
        "removeAt" => {
            let index = checked_index(as_i64(&arguments[0]) as usize, elements.len());
            elements.remove(index)
        }
        "clear" => {
            elements.clear();
            VmValue::Unit
        }
        other => panic!("unknown list method {other}"),
    }
}

fn apply_map_method(
    entries: &mut Vec<(VmValue, VmValue)>,
    method_name: &str,
    arguments: Vec<VmValue>,
) -> VmValue {
    match method_name {
        "size" => VmValue::Int(entries.len() as i32),
        "isEmpty" => VmValue::Bool(entries.is_empty()),
        "containsKey" => VmValue::Bool(entries.iter().any(|(key, _)| key == &arguments[0])),
        "get" => entries
            .iter()
            .find(|(key, _)| key == &arguments[0])
            .map(|(_, value)| value.clone())
            .unwrap_or(VmValue::Null),
        "getOrDefault" => entries
            .iter()
            .find(|(key, _)| key == &arguments[0])
            .map(|(_, value)| value.clone())
            .unwrap_or_else(|| arguments[1].clone()),
        "set" => {
            set_map_entry(entries, arguments[0].clone(), arguments[1].clone());
            VmValue::Unit
        }
        "remove" => remove_map_entry(entries, &arguments[0]),
        "clear" => {
            entries.clear();
            VmValue::Unit
        }
        other => panic!("unknown map method {other}"),
    }
}

fn create_frame(module: &Module, function_index: usize, arguments: Vec<VmValue>) -> Frame {
    let function = &module.functions[function_index];
    let local_count = function
        .locals
        .len()
        .max(function.parameters.len())
        .max(arguments.len());
    let mut locals = vec![VmValue::Unit; local_count];
    for (index, argument) in arguments.into_iter().enumerate() {
        locals[index] = argument;
    }
    Frame {
        function_index,
        instruction_pointer: 0,
        locals,
        stack: vec![],
    }
}

fn apply_binary(operator: u8, left: VmValue, right: VmValue) -> VmValue {
    match operator {
        0 => add_values(left, right),
        1 => numeric_values(
            left,
            right,
            |left, right| left - right,
            |left, right| left - right,
        ),
        2 => numeric_values(
            left,
            right,
            |left, right| left * right,
            |left, right| left * right,
        ),
        3 => numeric_values(
            left,
            right,
            |left, right| left / right,
            |left, right| left / right,
        ),
        4 => VmValue::Bool(left == right),
        5 => VmValue::Bool(left != right),
        6 => VmValue::Bool(as_i64(&left) < as_i64(&right)),
        7 => VmValue::Bool(as_i64(&left) <= as_i64(&right)),
        8 => VmValue::Bool(as_i64(&left) > as_i64(&right)),
        9 => VmValue::Bool(as_i64(&left) >= as_i64(&right)),
        10 => VmValue::Bool(as_bool(&left) && as_bool(&right)),
        11 => VmValue::Bool(as_bool(&left) || as_bool(&right)),
        12 => numeric_values(
            left,
            right,
            |left, right| left & right,
            |left, right| left & right,
        ),
        13 => numeric_values(
            left,
            right,
            |left, right| left | right,
            |left, right| left | right,
        ),
        14 => numeric_values(
            left,
            right,
            |left, right| left ^ right,
            |left, right| left ^ right,
        ),
        15 => shift_value(
            left,
            right,
            |left, right| left << right,
            |left, right| left << right,
        ),
        16 => shift_value(
            left,
            right,
            |left, right| left >> right,
            |left, right| left >> right,
        ),
        other => panic!("unknown binary operator tag {other}"),
    }
}

fn apply_unary(operator: u8, value: VmValue) -> VmValue {
    match operator {
        0 => match value {
            VmValue::Int(value) => VmValue::Int(-value),
            value => VmValue::Long(-as_i64(&value)),
        },
        1 => VmValue::Bool(!as_bool(&value)),
        2 => match value {
            VmValue::Int(value) => VmValue::Int(!value),
            value => VmValue::Long(!as_i64(&value)),
        },
        other => panic!("unknown unary operator tag {other}"),
    }
}

fn add_values(left: VmValue, right: VmValue) -> VmValue {
    match (&left, &right) {
        (VmValue::String(left), right) => VmValue::String(format!("{left}{}", render_value(right))),
        (left, VmValue::String(right)) => VmValue::String(format!("{}{right}", render_value(left))),
        _ => numeric_values(
            left,
            right,
            |left, right| left + right,
            |left, right| left + right,
        ),
    }
}

fn numeric_values(
    left: VmValue,
    right: VmValue,
    int_op: impl FnOnce(i32, i32) -> i32,
    long_op: impl FnOnce(i64, i64) -> i64,
) -> VmValue {
    match (left, right) {
        (VmValue::Int(left), VmValue::Int(right)) => VmValue::Int(int_op(left, right)),
        (left, right) => VmValue::Long(long_op(as_i64(&left), as_i64(&right))),
    }
}

fn shift_value(
    left: VmValue,
    right: VmValue,
    int_op: impl FnOnce(i32, i32) -> i32,
    long_op: impl FnOnce(i64, i32) -> i64,
) -> VmValue {
    let amount = as_i64(&right) as i32;
    match left {
        VmValue::Int(left) => VmValue::Int(int_op(left, amount)),
        left => VmValue::Long(long_op(as_i64(&left), amount)),
    }
}

fn as_bool(value: &VmValue) -> bool {
    match value {
        VmValue::Bool(value) => *value,
        _ => panic!("expected Bool value"),
    }
}

fn as_i64(value: &VmValue) -> i64 {
    match value {
        VmValue::Int(value) => *value as i64,
        VmValue::Long(value) => *value,
        _ => panic!("expected numeric value"),
    }
}

fn as_string(value: &VmValue) -> String {
    match value {
        VmValue::String(value) => value.clone(),
        _ => panic!("expected String value"),
    }
}

fn render_value(value: &VmValue) -> String {
    match value {
        VmValue::Unit => "unit".to_string(),
        VmValue::Null => "null".to_string(),
        VmValue::Bool(value) => value.to_string(),
        VmValue::Int(value) => value.to_string(),
        VmValue::Long(value) => value.to_string(),
        VmValue::String(value) => value.clone(),
        VmValue::Record { type_name, .. } => format!("{type_name}(...)"),
        VmValue::ObjectRef(value) => format!("object#{value}"),
    }
}
