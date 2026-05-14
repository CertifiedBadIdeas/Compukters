#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Program {
    pub(crate) consts: Vec<ConstDecl>,
    pub(crate) functions: Vec<FunctionDecl>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ConstDecl {
    pub(crate) name: String,
    pub(crate) value: Expr,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct FunctionDecl {
    pub(crate) name: String,
    pub(crate) parameters: Vec<Parameter>,
    pub(crate) return_type: ReturnType,
    pub(crate) statements: Vec<Statement>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Parameter {
    pub(crate) name: String,
    pub(crate) ty: TypeName,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ReturnType {
    Unit,
    I32,
    U32,
    U8,
    Bool,
    PtrI32,
    PtrU32,
    PtrU8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TypeName {
    I32,
    U32,
    U8,
    Bool,
    PtrI32,
    PtrU32,
    PtrU8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Statement {
    Let {
        name: String,
        ty: TypeName,
        initializer: Expr,
    },
    Assign {
        name: String,
        value: Expr,
    },
    AssignOp {
        name: String,
        op: BinaryOp,
        value: Expr,
    },
    IndexAssign {
        target: Expr,
        index: Expr,
        value: Expr,
    },
    If {
        condition: Expr,
        then_branch: Vec<Statement>,
        else_branch: Option<Vec<Statement>>,
    },
    While {
        condition: Expr,
        body: Vec<Statement>,
    },
    Break,
    Continue,
    Return(Option<Expr>),
    Unsafe(Vec<Statement>),
    Expr(Expr),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Expr {
    Int(i64),
    IntU32(i64),
    IntU8(i64),
    ByteString(Vec<u8>),
    Bool(bool),
    Local(String),
    Call {
        name: String,
        args: Vec<Expr>,
    },
    Mmio {
        ty: TypeName,
        address: Box<Expr>,
    },
    Ptr {
        ty: TypeName,
        address: Box<Expr>,
    },
    MethodCall {
        receiver: Box<Expr>,
        method: String,
        args: Vec<Expr>,
    },
    Index {
        target: Box<Expr>,
        index: Box<Expr>,
    },
    Cast {
        expr: Box<Expr>,
        target: TypeName,
    },
    Unary {
        op: UnaryOp,
        expr: Box<Expr>,
    },
    Logical {
        op: LogicalOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
    Binary {
        op: BinaryOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
    Compare {
        op: CompareOp,
        lhs: Box<Expr>,
        rhs: Box<Expr>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum UnaryOp {
    Not,
    Neg,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LogicalOp {
    And,
    Or,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
    BitAnd,
    BitOr,
    BitXor,
    Shl,
    Shr,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CompareOp {
    Lt,
    Eq,
    Ne,
    Gt,
    Le,
    Ge,
}
