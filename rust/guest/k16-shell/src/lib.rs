#![no_std]

extern crate alloc;

use alloc::vec::Vec;

pub const MAX_PATH_BYTES: usize = k16_abi::syscall::MAX_STAT_PATH_BYTES;
pub const MAX_COMMAND_ARGS: usize = k16_abi::syscall::MAX_RUN_ARGS;
const BIN_PREFIX: &[u8] = b"/bin/";
const PROGRAM_SUFFIX: &[u8] = b".kx";
const ALLOC_ALIAS: &[u8] = b"alloc";
const ALLOC_PROGRAM: &[u8] = b"alloc-test";
const EMPTY_ARG: &[u8] = b"";

#[derive(Debug, Eq, PartialEq)]
pub enum PathError {
    Invalid,
    TooLong,
}

pub struct PathBuffer {
    bytes: [u8; MAX_PATH_BYTES],
    len: usize,
}

impl PathBuffer {
    pub const fn new() -> Self {
        Self {
            bytes: [0; MAX_PATH_BYTES],
            len: 0,
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.len]
    }

    pub fn as_str(&self) -> Result<&str, PathError> {
        core::str::from_utf8(self.as_bytes()).map_err(|_| PathError::Invalid)
    }

    pub fn replace_with_parts(
        &mut self,
        prefix: &[u8],
        middle: &[u8],
        suffix: &[u8],
    ) -> Result<(), PathError> {
        if middle.is_empty() {
            return Err(PathError::Invalid);
        }
        let end = prefix
            .len()
            .checked_add(middle.len())
            .and_then(|value| value.checked_add(suffix.len()))
            .ok_or(PathError::TooLong)?;
        if end > self.bytes.len() {
            return Err(PathError::TooLong);
        }
        let mut cursor = 0;
        self.bytes[cursor..cursor + prefix.len()].copy_from_slice(prefix);
        cursor += prefix.len();
        self.bytes[cursor..cursor + middle.len()].copy_from_slice(middle);
        cursor += middle.len();
        self.bytes[cursor..cursor + suffix.len()].copy_from_slice(suffix);
        self.len = end;
        Ok(())
    }

    fn clear(&mut self) {
        self.len = 0;
    }

    fn push_root(&mut self) {
        self.bytes[0] = b'/';
        self.len = 1;
    }

    fn copy_from(&mut self, bytes: &[u8]) -> Result<(), PathError> {
        if bytes.is_empty() || bytes.len() > self.bytes.len() {
            return Err(PathError::TooLong);
        }
        self.bytes[..bytes.len()].copy_from_slice(bytes);
        self.len = bytes.len();
        Ok(())
    }

    fn push_component(&mut self, component: &[u8]) -> Result<(), PathError> {
        if component.is_empty() {
            return Err(PathError::Invalid);
        }
        let separator_len = if self.as_bytes() == b"/" { 0 } else { 1 };
        let end = self
            .len
            .checked_add(separator_len)
            .and_then(|value| value.checked_add(component.len()))
            .ok_or(PathError::TooLong)?;
        if end > self.bytes.len() {
            return Err(PathError::TooLong);
        }
        if separator_len == 1 {
            self.bytes[self.len] = b'/';
            self.len += 1;
        }
        self.bytes[self.len..end].copy_from_slice(component);
        self.len = end;
        Ok(())
    }

    fn pop_component(&mut self) {
        if self.as_bytes() == b"/" {
            return;
        }
        let mut index = self.len;
        while index > 0 {
            index -= 1;
            if self.bytes[index] == b'/' {
                self.len = if index == 0 { 1 } else { index };
                return;
            }
        }
        self.push_root();
    }
}

impl Default for PathBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct WorkingDirectory {
    path: PathBuffer,
}

impl WorkingDirectory {
    pub fn new() -> Self {
        let mut path = PathBuffer::new();
        path.bytes[0] = b'/';
        path.len = 1;
        Self { path }
    }

    pub fn as_bytes(&self) -> &[u8] {
        self.path.as_bytes()
    }

    pub fn resolve_into(&self, input: &[u8], out: &mut PathBuffer) -> Result<(), PathError> {
        if input.is_empty() {
            return Err(PathError::Invalid);
        }
        out.clear();
        if input[0] == b'/' {
            out.push_root();
        } else {
            out.copy_from(self.as_bytes())?;
        }

        let mut cursor = 0;
        while cursor < input.len() {
            while cursor < input.len() && input[cursor] == b'/' {
                cursor += 1;
            }
            let start = cursor;
            while cursor < input.len() && input[cursor] != b'/' {
                cursor += 1;
            }
            if start == cursor {
                continue;
            }
            let component = &input[start..cursor];
            if component == b"." {
                continue;
            }
            if component == b".." {
                out.pop_component();
            } else {
                out.push_component(component)?;
            }
        }
        Ok(())
    }

    pub fn set_from_resolved(&mut self, path: &PathBuffer) -> Result<(), PathError> {
        let bytes = path.as_bytes();
        if bytes.is_empty() || bytes[0] != b'/' {
            return Err(PathError::Invalid);
        }
        self.path.copy_from(bytes)
    }
}

impl Default for WorkingDirectory {
    fn default() -> Self {
        Self::new()
    }
}

#[inline(always)]
pub fn resolve_executable_path(
    cwd: &WorkingDirectory,
    name: &[u8],
    out: &mut PathBuffer,
) -> Result<(), PathError> {
    if has_path_separator(name) {
        return cwd.resolve_into(name, out);
    }
    let name = if name == ALLOC_ALIAS {
        ALLOC_PROGRAM
    } else {
        name
    };
    out.replace_with_parts(BIN_PREFIX, name, PROGRAM_SUFFIX)
}

fn has_path_separator(bytes: &[u8]) -> bool {
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'/' {
            return true;
        }
        index += 1;
    }
    false
}

#[derive(Debug, Eq, PartialEq)]
pub enum Command<'a> {
    Empty,
    Invalid,
    Help,
    Clear,
    Pwd,
    Cd(Option<&'a [u8]>),
    Ticks,
    Status,
    Echo(&'a [u8]),
    Exec {
        name: &'a [u8],
        args: CommandArgs<'a>,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CommandArgs<'a> {
    args: [&'a [u8]; MAX_COMMAND_ARGS],
    len: usize,
}

impl<'a> CommandArgs<'a> {
    pub const fn empty() -> Self {
        Self {
            args: [EMPTY_ARG; MAX_COMMAND_ARGS],
            len: 0,
        }
    }

    pub fn from_slice(args: &[&'a [u8]]) -> Option<Self> {
        let mut out = Self::empty();
        let mut index = 0;
        while index < args.len() {
            if out.push(args[index]).is_err() {
                return None;
            }
            index += 1;
        }
        Some(out)
    }

    pub const fn len(&self) -> usize {
        self.len
    }

    pub const fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn as_slice(&self) -> &[&'a [u8]] {
        &self.args[..self.len]
    }

    fn push(&mut self, arg: &'a [u8]) -> Result<(), ()> {
        if self.len >= self.args.len() {
            return Err(());
        }
        self.args[self.len] = arg;
        self.len += 1;
        Ok(())
    }
}

pub struct InputLine {
    bytes: Vec<u8>,
}

impl InputLine {
    pub fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    pub fn clear(&mut self) {
        self.bytes.clear();
    }

    pub fn push_printable(&mut self, byte: u8) -> bool {
        if self.bytes.try_reserve(1).is_err() {
            return false;
        }
        self.bytes.push(byte);
        true
    }

    pub fn backspace(&mut self) -> bool {
        self.bytes.pop().is_some()
    }

    pub fn command(&self) -> Command<'_> {
        classify_line(&self.bytes, self.bytes.len())
    }
}

impl Default for InputLine {
    fn default() -> Self {
        Self::new()
    }
}

pub fn classify_line(input: &[u8], line_len: usize) -> Command<'_> {
    if line_len == 0 {
        return Command::Empty;
    }
    if matches_command(input, b"help") {
        Command::Help
    } else if matches_command(input, b"clear") {
        Command::Clear
    } else if matches_command(input, b"pwd") {
        Command::Pwd
    } else if matches_command(input, b"cd") {
        Command::Cd(None)
    } else if is_cd_command(input, line_len) {
        Command::Cd(Some(&input[3..line_len]))
    } else if matches_command(input, b"ticks") {
        Command::Ticks
    } else if matches_command(input, b"status") {
        Command::Status
    } else if is_echo_command(input, line_len) {
        let start = if line_len > 4 { 5 } else { 4 };
        Command::Echo(&input[start..line_len])
    } else {
        classify_exec(input, line_len)
    }
}

fn matches_command(input: &[u8], command: &[u8]) -> bool {
    input == command
}

fn is_echo_command(input: &[u8], line_len: usize) -> bool {
    line_len >= 4
        && input[0] == b'e'
        && input[1] == b'c'
        && input[2] == b'h'
        && input[3] == b'o'
        && (line_len == 4 || input[4] == b' ')
}

fn is_cd_command(input: &[u8], line_len: usize) -> bool {
    line_len > 3 && input[0] == b'c' && input[1] == b'd' && input[2] == b' '
}

fn classify_exec(input: &[u8], line_len: usize) -> Command<'_> {
    let mut cursor = 0;
    while cursor < line_len && input[cursor].is_ascii_whitespace() {
        cursor += 1;
    }
    if cursor == line_len {
        return Command::Empty;
    }
    let name_start = cursor;
    while cursor < line_len && !input[cursor].is_ascii_whitespace() {
        cursor += 1;
    }
    let name = &input[name_start..cursor];
    if name.is_empty() {
        return Command::Invalid;
    }

    let mut args = CommandArgs::empty();
    loop {
        while cursor < line_len && input[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        if cursor == line_len {
            break;
        }
        let start = cursor;
        while cursor < line_len && !input[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        if args.push(&input[start..cursor]).is_err() {
            return Command::Invalid;
        }
    }
    Command::Exec { name, args }
}

#[cfg(test)]
mod tests {
    use super::{Command, CommandArgs, InputLine, PathBuffer, WorkingDirectory};

    #[test]
    fn reused_line_accepts_short_command_after_long_command() {
        let mut line = InputLine::new();
        for byte in b"echo abcdef" {
            assert!(line.push_printable(*byte));
        }
        assert_eq!(line.command(), Command::Echo(b"abcdef"));

        line.clear();
        for byte in b"help" {
            assert!(line.push_printable(*byte));
        }
        assert_eq!(line.command(), Command::Help);
    }

    #[test]
    fn long_echo_line_exceeds_old_fixed_capacity() {
        let mut line = InputLine::new();
        for byte in b"echo " {
            assert!(line.push_printable(*byte));
        }
        for _ in 0..160 {
            assert!(line.push_printable(b'x'));
        }

        let Command::Echo(bytes) = line.command() else {
            panic!("long echo should remain an echo command");
        };
        assert_eq!(bytes.len(), 160);
        assert!(bytes.iter().all(|byte| *byte == b'x'));
    }

    #[test]
    fn long_line_can_be_cleared_and_reused_for_short_command() {
        let mut line = InputLine::new();
        for byte in b"echo " {
            assert!(line.push_printable(*byte));
        }
        for _ in 0..160 {
            assert!(line.push_printable(b'x'));
        }

        line.clear();
        for byte in b"help" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Help);
    }

    #[test]
    fn uname_command_is_recognized_as_process_run_utility() {
        let mut line = InputLine::new();
        for byte in b"uname" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"uname",
                args: CommandArgs::empty()
            }
        );
    }

    #[test]
    fn pwd_command_is_recognized_as_shell_builtin() {
        let mut line = InputLine::new();
        for byte in b"pwd" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Pwd);
    }

    #[test]
    fn status_command_is_recognized_as_shell_builtin() {
        let mut line = InputLine::new();
        for byte in b"status" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Status);
    }

    #[test]
    fn cd_command_without_argument_is_recognized_as_root_change() {
        let mut line = InputLine::new();
        for byte in b"cd" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Cd(None));
    }

    #[test]
    fn cd_command_with_path_is_recognized_as_shell_builtin() {
        let mut line = InputLine::new();
        for byte in b"cd ../bin" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Cd(Some(b"../bin")));
    }

    #[test]
    fn cat_command_is_recognized_as_generic_exec_with_argument() {
        let mut line = InputLine::new();
        for byte in b"cat /etc/motd" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"cat",
                args: CommandArgs::from_slice(&[b"/etc/motd".as_slice()]).unwrap()
            }
        );
    }

    #[test]
    fn ls_command_without_argument_is_recognized_as_generic_exec() {
        let mut line = InputLine::new();
        for byte in b"ls" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"ls",
                args: CommandArgs::empty()
            }
        );
    }

    #[test]
    fn ls_command_with_path_is_recognized_as_generic_exec_with_argument() {
        let mut line = InputLine::new();
        for byte in b"ls /bin" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"ls",
                args: CommandArgs::from_slice(&[b"/bin".as_slice()]).unwrap()
            }
        );
    }

    #[test]
    fn alloc_command_is_recognized_as_generic_exec_alias() {
        let mut line = InputLine::new();
        for byte in b"alloc" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"alloc",
                args: CommandArgs::empty()
            }
        );
    }

    #[test]
    fn unknown_command_is_recognized_as_generic_exec() {
        let mut line = InputLine::new();
        for byte in b"foo bar baz" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(
            line.command(),
            Command::Exec {
                name: b"foo",
                args: CommandArgs::from_slice(&[b"bar".as_slice(), b"baz".as_slice()]).unwrap()
            }
        );
    }

    #[test]
    fn exec_parser_splits_repeated_ascii_whitespace_arguments() {
        let command = super::classify_line(b"foo   bar\tbaz", 13);

        assert_eq!(
            command,
            Command::Exec {
                name: b"foo",
                args: CommandArgs::from_slice(&[b"bar".as_slice(), b"baz".as_slice()]).unwrap()
            }
        );
    }

    #[test]
    fn exec_parser_rejects_more_than_max_arguments() {
        let command = super::classify_line(b"foo one two three four five", 27);

        assert_eq!(command, Command::Invalid);
    }

    #[test]
    fn working_directory_starts_at_root() {
        let cwd = WorkingDirectory::new();

        assert_eq!(cwd.as_bytes(), b"/");
    }

    #[test]
    fn path_buffer_resolves_absolute_path_components() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();

        cwd.resolve_into(b"/bin/../etc//motd", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/etc/motd");
    }

    #[test]
    fn path_buffer_resolves_relative_path_from_cwd() {
        let mut cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();
        cwd.resolve_into(b"etc", &mut path).unwrap();
        cwd.set_from_resolved(&path).unwrap();

        cwd.resolve_into(b"../bin/./ls.kx", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/bin/ls.kx");
    }

    #[test]
    fn path_buffer_keeps_parent_of_root_at_root() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();

        cwd.resolve_into(b"../../", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/");
    }

    #[test]
    fn path_buffer_rejects_overlong_paths() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();
        let mut input = [b'a'; super::MAX_PATH_BYTES + 1];
        input[0] = b'/';

        assert!(cwd.resolve_into(&input, &mut path).is_err());
    }

    #[test]
    fn path_buffer_replaces_with_program_path_parts() {
        let mut path = PathBuffer::new();

        path.replace_with_parts(b"/bin/", b"uname", b".kx").unwrap();

        assert_eq!(path.as_bytes(), b"/bin/uname.kx");
    }

    #[test]
    fn executable_path_resolver_keeps_bare_commands_in_bin() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();

        super::resolve_executable_path(&cwd, b"uname", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/bin/uname.kx");
    }

    #[test]
    fn executable_path_resolver_keeps_alloc_alias_in_bin() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();

        super::resolve_executable_path(&cwd, b"alloc", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/bin/alloc-test.kx");
    }

    #[test]
    fn executable_path_resolver_accepts_absolute_program_path() {
        let cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();

        super::resolve_executable_path(&cwd, b"/bin/uname.kx", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/bin/uname.kx");
    }

    #[test]
    fn executable_path_resolver_accepts_cwd_relative_program_path() {
        let mut cwd = WorkingDirectory::new();
        let mut path = PathBuffer::new();
        cwd.resolve_into(b"/bin", &mut path).unwrap();
        cwd.set_from_resolved(&path).unwrap();

        super::resolve_executable_path(&cwd, b"./uname.kx", &mut path).unwrap();

        assert_eq!(path.as_bytes(), b"/bin/uname.kx");
    }
}
