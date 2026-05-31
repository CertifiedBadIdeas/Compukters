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
    let mut diagnostics = find_nested_if_advice(source, &tokens);
    diagnostics.extend(find_bool_comparison_advice(source, &tokens));
    diagnostics.sort_by_key(|diagnostic| (diagnostic.line, diagnostic.column));
    Ok(diagnostics)
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

fn find_bool_comparison_advice(source: &str, tokens: &[Token]) -> Vec<AdviceDiagnostic> {
    let mut diagnostics = Vec::new();
    for index in 0..tokens.len() {
        if tokens[index].kind != TokenKind::If {
            continue;
        }
        let Some(if_tokens) = parse_if_tokens(tokens, index) else {
            continue;
        };
        let condition_start = index + 1;
        let condition_end = if_tokens.open_brace;
        if condition_end <= condition_start {
            continue;
        }

        let condition_tokens = &tokens[condition_start..condition_end];
        let condition_end_offset = tokens[if_tokens.open_brace].offset;
        let Some((expression_text, direct_condition)) =
            bool_comparison_suggestion(source, condition_tokens, condition_end_offset)
        else {
            continue;
        };

        let (line, column) = line_column(source, tokens[index].offset);
        let help = if direct_condition {
            format!("if {expression_text} {{ ... }}")
        } else {
            format!("if !{expression_text} {{ ... }}")
        };
        diagnostics.push(AdviceDiagnostic {
            line,
            column,
            message: "bool comparison can be simplified".to_string(),
            help,
        });
    }
    diagnostics
}

fn bool_comparison_suggestion(
    source: &str,
    tokens: &[Token],
    condition_end_offset: usize,
) -> Option<(String, bool)> {
    if tokens.len() != 3 {
        return None;
    }
    let compare_token = &tokens[1];
    let equals = match compare_token.kind {
        TokenKind::EqualEqual => true,
        TokenKind::BangEqual => false,
        _ => return None,
    };

    let rhs_bool = match tokens[2].kind {
        TokenKind::True => Some(true),
        TokenKind::False => Some(false),
        _ => None,
    };
    if let Some(bool_value) = rhs_bool {
        let expression_text = token_text(source, &tokens[0], compare_token.offset);
        return Some((expression_text, equals == bool_value));
    }

    let lhs_bool = match tokens[0].kind {
        TokenKind::True => Some(true),
        TokenKind::False => Some(false),
        _ => None,
    }?;
    let expression_text = token_text(source, &tokens[2], condition_end_offset);
    Some((expression_text, equals == lhs_bool))
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

fn token_text(source: &str, token: &Token, end_offset: usize) -> String {
    source[token.offset..end_offset].trim().to_string()
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
