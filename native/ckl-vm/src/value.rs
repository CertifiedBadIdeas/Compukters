#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VmValue {
    Unit,
    Null,
    Bool(bool),
    Int(i32),
    Long(i64),
    String(String),
    ObjectRef(u32),
}
