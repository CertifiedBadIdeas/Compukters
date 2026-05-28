use crate::frontend::ast::{ConstDecl, FunctionDecl, Program, Visibility};
use crate::frontend::error::CompileError;
use crate::frontend::{lexer, parser};
use crate::runtime::stdlib;
use std::collections::HashSet;

pub(crate) fn resolve(program: Program) -> Result<Program, CompileError> {
    if program.uses.is_empty() {
        return Ok(program);
    }

    let mut imported_names = HashSet::new();
    let mut imported_consts = Vec::new();
    let mut imported_functions = Vec::new();
    for use_decl in &program.uses {
        match resolve_import(&use_decl.path)? {
            ImportedItem::Const(constant) => {
                check_import_conflict(&program, &mut imported_names, &constant.name)?;
                imported_consts.push(constant);
            }
            ImportedItem::Function(function) => {
                check_import_conflict(&program, &mut imported_names, &function.name)?;
                imported_functions.push(function);
            }
            ImportedItem::Namespace { alias, consts } => {
                check_import_conflict(&program, &mut imported_names, &alias)?;
                imported_consts.extend(consts);
            }
        }
    }

    let mut consts = imported_consts;
    consts.extend(program.consts);
    let mut functions = imported_functions;
    functions.extend(program.functions);
    Ok(Program {
        uses: Vec::new(),
        consts,
        functions,
    })
}

enum ImportedItem {
    Const(ConstDecl),
    Function(FunctionDecl),
    Namespace {
        alias: String,
        consts: Vec<ConstDecl>,
    },
}

fn check_import_conflict(
    program: &Program,
    imported_names: &mut HashSet<String>,
    name: &str,
) -> Result<(), CompileError> {
    if program.consts.iter().any(|constant| constant.name == name) {
        return Err(CompileError {
            message: format!("import `{name}` conflicts with const"),
        });
    }
    if program
        .functions
        .iter()
        .any(|existing| existing.name == name)
    {
        return Err(CompileError {
            message: format!("import `{name}` conflicts with function"),
        });
    }
    if !imported_names.insert(name.to_string()) {
        return Err(CompileError {
            message: format!("duplicate import `{name}`"),
        });
    }
    Ok(())
}

fn resolve_import(path: &[String]) -> Result<ImportedItem, CompileError> {
    let display_path = path.join("::");
    if path.len() < 3 {
        return Err(CompileError {
            message: format!("unknown import `{display_path}`"),
        });
    }

    let item_name = path.last().expect("checked path length");
    let module_path = &path[..path.len() - 1];
    let source = stdlib::source_for_path(module_path).ok_or_else(|| CompileError {
        message: format!("unknown import `{display_path}`"),
    })?;
    let module_program = parser::parse(lexer::lex(source)?)?;

    if let Some(constant) = module_program
        .consts
        .iter()
        .find(|constant| constant.name == *item_name)
    {
        if constant.visibility != Visibility::Public {
            return Err(CompileError {
                message: format!("import `{display_path}` is private"),
            });
        }
        return Ok(ImportedItem::Const(constant.clone()));
    }

    if let Some(function) = module_program
        .functions
        .into_iter()
        .find(|function| function.name == *item_name)
    {
        if function.visibility != Visibility::Public {
            return Err(CompileError {
                message: format!("import `{display_path}` is private"),
            });
        }
        return Ok(ImportedItem::Function(function));
    }

    if let Some(source) = stdlib::source_for_path(path) {
        let namespace_program = parser::parse(lexer::lex(source)?)?;
        let mut consts = Vec::new();
        for mut constant in namespace_program.consts {
            if constant.visibility != Visibility::Public {
                continue;
            }
            constant.name = format!("{item_name}::{}", constant.name);
            consts.push(constant);
        }
        return Ok(ImportedItem::Namespace {
            alias: item_name.clone(),
            consts,
        });
    }

    Err(CompileError {
        message: format!("unknown import `{display_path}`"),
    })
}
