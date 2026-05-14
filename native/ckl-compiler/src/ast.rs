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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ReturnType {
    Unit,
    I32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Statement {
    Let {
        name: String,
        initializer: Expr,
    },
    Assign {
        name: String,
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
    Return(Option<Expr>),
    Unsafe(Vec<Statement>),
    Expr(Expr),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Expr {
    Int(i64),
    Local(String),
    Call {
        name: String,
        args: Vec<Expr>,
    },
    Mmio(Box<Expr>),
    Ptr(Box<Expr>),
    MethodCall {
        receiver: Box<Expr>,
        method: String,
        args: Vec<Expr>,
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
