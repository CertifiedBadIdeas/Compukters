use crate::ast::{FunctionDecl, Program, Visibility};
use crate::error::CompileError;
use crate::{lexer, parser, stdlib};
use std::collections::HashSet;

pub(crate) fn resolve(program: Program) -> Result<Program, CompileError> {
    if program.uses.is_empty() {
        return Ok(program);
    }

    let mut imported_names = HashSet::new();
    let mut imported_functions = Vec::new();
    for use_decl in &program.uses {
        let function = resolve_std_function(&use_decl.path)?;
        if program
            .consts
            .iter()
            .any(|constant| constant.name == function.name)
        {
            return Err(CompileError {
                message: format!("import `{}` conflicts with const", function.name),
            });
        }
        if program
            .functions
            .iter()
            .any(|existing| existing.name == function.name)
        {
            return Err(CompileError {
                message: format!("import `{}` conflicts with function", function.name),
            });
        }
        if !imported_names.insert(function.name.clone()) {
            return Err(CompileError {
                message: format!("duplicate import `{}`", function.name),
            });
        }
        imported_functions.push(function);
    }

    let mut functions = imported_functions;
    functions.extend(program.functions);
    Ok(Program {
        uses: Vec::new(),
        consts: program.consts,
        functions,
    })
}

fn resolve_std_function(path: &[String]) -> Result<FunctionDecl, CompileError> {
    let display_path = path.join("::");
    if path.len() != 3 || path[0] != "std" {
        return Err(CompileError {
            message: format!("unknown std import `{display_path}`"),
        });
    }

    let module = &path[1];
    let function_name = &path[2];
    let source = stdlib::module_source(module).ok_or_else(|| CompileError {
        message: format!("unknown std import `{display_path}`"),
    })?;
    let std_program = parser::parse(lexer::lex(source)?)?;
    let function = std_program
        .functions
        .into_iter()
        .find(|function| function.name == *function_name)
        .ok_or_else(|| CompileError {
            message: format!("unknown std import `{display_path}`"),
        })?;
    if function.visibility != Visibility::Public {
        return Err(CompileError {
            message: format!("std import `{display_path}` is private"),
        });
    }
    Ok(function)
}
