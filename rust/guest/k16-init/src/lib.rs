#![no_std]

pub const INPUT_CAPACITY: usize = 128;

#[derive(Debug, Eq, PartialEq)]
pub enum Command<'a> {
    Empty,
    Help,
    Clear,
    Ticks,
    Echo(&'a [u8]),
    Unknown,
}

pub struct InputLine {
    bytes: [u8; INPUT_CAPACITY],
    len: usize,
}

impl InputLine {
    pub const fn new() -> Self {
        Self {
            bytes: [0; INPUT_CAPACITY],
            len: 0,
        }
    }

    pub fn clear(&mut self) {
        self.len = 0;
        let mut index = 0;
        while index < self.bytes.len() {
            self.bytes[index] = 0;
            index += 1;
        }
    }

    pub fn push_printable(&mut self, byte: u8) -> bool {
        if self.len >= INPUT_CAPACITY {
            return false;
        }
        self.bytes[self.len] = byte;
        self.len += 1;
        true
    }

    pub fn backspace(&mut self) -> bool {
        if self.len == 0 {
            return false;
        }
        self.len -= 1;
        self.bytes[self.len] = 0;
        true
    }

    pub fn command(&self) -> Command<'_> {
        classify_line(&self.bytes, self.len)
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
    } else if is_echo_command(input, line_len) {
        let start = if line_len > 4 { 5 } else { 4 };
        Command::Echo(&input[start..line_len])
    } else {
        Command::Unknown
    }
}

fn matches_command(input: &[u8], command: &[u8]) -> bool {
    let mut index = 0;
    while index < command.len() {
        if input[index] != command[index] {
            return false;
        }
        index += 1;
    }
    input[index] == 0
}

fn is_echo_command(input: &[u8], line_len: usize) -> bool {
    line_len >= 4
        && input[0] == b'e'
        && input[1] == b'c'
        && input[2] == b'h'
        && input[3] == b'o'
        && (line_len == 4 || input[4] == b' ')
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
}
