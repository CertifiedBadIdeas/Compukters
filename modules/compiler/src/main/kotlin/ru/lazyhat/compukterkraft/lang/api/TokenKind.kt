/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.api

enum class TokenKind {
    IDENTIFIER,
    NUMBER,
    STRING,
    TRUE,
    FALSE,
    NULL,
    PUB,
    FUN,
    VAL,
    VAR,
    IF,
    ELSE,
    WHILE,
    WHEN,
    RETURN,
    IMPORT,
    AS,
    STRUCT,
    CLASS,
    STATIC,
    INIT,
    THIS,
    COLON,
    COLON_COLON,
    SEMICOLON,
    COMMA,
    DOT,
    QUESTION,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    BANG,
    EQUAL,
    PLUS_EQUAL,
    MINUS_EQUAL,
    STAR_EQUAL,
    SLASH_EQUAL,
    EQUAL_EQUAL,
    BANG_EQUAL,
    LT,
    LTE,
    GT,
    GTE,
    AMP_AMP,
    PIPE_PIPE,
    ARROW,
    EOF,
}
