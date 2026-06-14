#![no_std]

extern crate alloc;

use alloc::vec::Vec;

#[derive(Debug, Eq, PartialEq)]
pub enum Command<'a> {
    Empty,
    Help,
    Clear,
    Ticks,
    Uname,
    Cat(&'a [u8]),
    AllocTest,
    Echo(&'a [u8]),
    Unknown,
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
    } else if matches_command(input, b"ticks") {
        Command::Ticks
    } else if matches_command(input, b"uname") {
        Command::Uname
    } else if is_cat_command(input, line_len) {
        Command::Cat(&input[4..line_len])
    } else if matches_command(input, b"alloc") {
        Command::AllocTest
    } else if is_echo_command(input, line_len) {
        let start = if line_len > 4 { 5 } else { 4 };
        Command::Echo(&input[start..line_len])
    } else {
        Command::Unknown
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

fn is_cat_command(input: &[u8], line_len: usize) -> bool {
    line_len > 4 && input[0] == b'c' && input[1] == b'a' && input[2] == b't' && input[3] == b' '
}

#[cfg(test)]
mod tests {
    use super::{Command, InputLine};

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

        assert_eq!(line.command(), Command::Uname);
    }

    #[test]
    fn cat_command_is_recognized_as_process_run_utility() {
        let mut line = InputLine::new();
        for byte in b"cat /etc/motd" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::Cat(b"/etc/motd"));
    }

    #[test]
    fn alloc_command_is_recognized_as_process_run_utility() {
        let mut line = InputLine::new();
        for byte in b"alloc" {
            assert!(line.push_printable(*byte));
        }

        assert_eq!(line.command(), Command::AllocTest);
    }
}
