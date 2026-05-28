use crate::frontend::{lex, parse, CompileError, Token, TokenKind};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdviceDiagnostic {
    pub line: usize,
    pub column: usize,
    pub message: String,
    pub help: String,
}

pub fn check_source(source: &str) -> Result<Vec<AdviceDiagnostic>, CompileError> {
    let tokens = lex(source)?;
    parse(tokens.clone())?;
    Ok(find_nested_if_advice(source, &tokens))
}

fn find_nested_if_advice(source: &str, tokens: &[Token]) -> Vec<AdviceDiagnostic> {
    let mut diagnostics = Vec::new();
    for index in 0..tokens.len() {
        if tokens[index].kind != TokenKind::If {
            continue;
        }
        let Some(outer_if) = parse_if_tokens(tokens, index) else {
            continue;
        };
        if outer_if.has_else {
            continue;
        }
        let body_start = outer_if.open_brace + 1;
        if body_start >= outer_if.close_brace {
            continue;
        }
        if tokens[body_start].kind != TokenKind::If {
            continue;
        }
        let Some(inner_if) = parse_if_tokens(tokens, body_start) else {
            continue;
        };
        if inner_if.has_else || inner_if.close_brace + 1 != outer_if.close_brace {
            continue;
        }

        let (line, column) = line_column(source, tokens[index].offset);
        let outer_condition = condition_text(
            source,
            tokens[index].offset,
            tokens[outer_if.open_brace].offset,
        );
        let inner_condition = condition_text(
            source,
            tokens[body_start].offset,
            tokens[inner_if.open_brace].offset,
        );
        diagnostics.push(AdviceDiagnostic {
            line,
            column,
            message: "nested if can be combined with &&".to_string(),
            help: format!("if {outer_condition} && {inner_condition} {{ ... }}"),
        });
    }
    diagnostics
}

#[derive(Debug, Clone, Copy)]
struct IfTokens {
    open_brace: usize,
    close_brace: usize,
    has_else: bool,
}

fn parse_if_tokens(tokens: &[Token], if_index: usize) -> Option<IfTokens> {
    let open_brace = first_token(tokens, if_index + 1, TokenKind::LeftBrace)?;
    let close_brace = matching_right_brace(tokens, open_brace)?;
    let has_else = tokens
        .get(close_brace + 1)
        .is_some_and(|token| token.kind == TokenKind::Else);
    Some(IfTokens {
        open_brace,
        close_brace,
        has_else,
    })
}

fn first_token(tokens: &[Token], start: usize, kind: TokenKind) -> Option<usize> {
    (start..tokens.len()).find(|index| tokens[*index].kind == kind)
}

fn matching_right_brace(tokens: &[Token], open_brace: usize) -> Option<usize> {
    let mut depth = 0;
    for (index, token) in tokens.iter().enumerate().skip(open_brace) {
        match token.kind {
            TokenKind::LeftBrace => depth += 1,
            TokenKind::RightBrace => {
                depth -= 1;
                if depth == 0 {
                    return Some(index);
                }
            }
            _ => {}
        }
    }
    None
}

fn condition_text(source: &str, if_offset: usize, open_brace_offset: usize) -> String {
    let start = if_offset + "if".len();
    source[start..open_brace_offset].trim().to_string()
}

fn line_column(source: &str, offset: usize) -> (usize, usize) {
    let mut line = 1;
    let mut column = 1;
    for byte in source[..offset].bytes() {
        if byte == b'\n' {
            line += 1;
            column = 1;
        } else {
            column += 1;
        }
    }
    (line, column)
}
