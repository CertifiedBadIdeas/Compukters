#include <kraft/fs.h>
#include <kraft/process.h>
#include <kraft/syscalls.h>
#include <string.h>
#include <unistd.h>

#define PROMPT "K16> "
#define BIN_PREFIX "/bin/"
#define PROGRAM_SUFFIX ".kx"
#define ALLOC_ALIAS "alloc"
#define ALLOC_PROGRAM "alloc-test"

#define KRAFT_SHELL_INPUT_CAPACITY 256
#define KRAFT_MAX_SHELL_PATH_BYTES KRAFT_MAX_STAT_PATH_BYTES
#define KRAFT_STATUS_OK 0u
#define KRAFT_ERROR_INVALID 0xffffffeau
#define KRAFT_SYSCALL_GAME_TICKS 16u

#define COMMAND_EMPTY 0
#define COMMAND_INVALID 1
#define COMMAND_CLEAR 2
#define COMMAND_PWD 3
#define COMMAND_CD 4
#define COMMAND_TICKS 5
#define COMMAND_STATUS 6
#define COMMAND_EXIT 7
#define COMMAND_ECHO 8
#define COMMAND_EXEC 9

struct command {
  int kind;
  const char *name;
  const char *args[KRAFT_MAX_PROCESS_ARGS];
  int argc;
};

struct shell_state {
  char cwd[KRAFT_MAX_SHELL_PATH_BYTES + 1];
  char input[KRAFT_SHELL_INPUT_CAPACITY];
  unsigned int input_len;
  unsigned int last_status;
};

static unsigned char ticks_bytes[8];

extern unsigned int __k16_syscall1(unsigned int number, unsigned int arg0);

static void write_all(int fd, const char *buffer, unsigned int len) {
  unsigned int written = 0;
  while (written < len) {
    int result = write(fd, buffer + written, len - written);
    if (result <= 0) {
      _exit(1);
    }
    written += (unsigned int)result;
  }
}

static void write_text(int fd, const char *text) {
  write_all(fd, text, strlen(text));
}

static int text_equals_len(const char *left, const char *right,
                           unsigned int right_len) {
  unsigned int index = 0;
  while (index < right_len) {
    if (left[index] != right[index]) {
      return 0;
    }
    index += 1;
  }
  return left[index] == '\0';
}

static int text_equals(const char *left, const char *right) {
  return strcmp(left, right) == 0;
}

static int matches_command(const char *input, const char *command) {
  return text_equals(input, command);
}

static int is_echo_command(const char *input) {
  return input[0] == 'e' && input[1] == 'c' && input[2] == 'h' &&
         input[3] == 'o' && (input[4] == '\0' || input[4] == ' ');
}

static int has_path_separator(const char *text) {
  unsigned int index = 0;
  while (text[index] != '\0') {
    if (text[index] == '/') {
      return 1;
    }
    index += 1;
  }
  return 0;
}

static int append_byte(char *out, unsigned int out_len, unsigned int *cursor,
                       char byte) {
  if (*cursor + 1 >= out_len) {
    return -1;
  }
  out[*cursor] = byte;
  *cursor += 1;
  out[*cursor] = '\0';
  return 0;
}

static int append_text(char *out, unsigned int out_len, unsigned int *cursor,
                       const char *text) {
  unsigned int index = 0;
  while (text[index] != '\0') {
    if (append_byte(out, out_len, cursor, text[index]) != 0) {
      return -1;
    }
    index += 1;
  }
  return 0;
}

static int push_path_component(char *out, unsigned int out_len,
                               const char *component,
                               unsigned int component_len) {
  unsigned int cursor = strlen(out);
  if (component_len == 0) {
    return 0;
  }
  if (component_len == 1 && component[0] == '.') {
    return 0;
  }
  if (component_len == 2 && component[0] == '.' && component[1] == '.') {
    if (cursor <= 1) {
      out[0] = '/';
      out[1] = '\0';
      return 0;
    }
    cursor -= 1;
    while (cursor > 0 && out[cursor] != '/') {
      cursor -= 1;
    }
    if (cursor == 0) {
      out[1] = '\0';
    } else {
      out[cursor] = '\0';
    }
    return 0;
  }
  if (cursor > 1 && append_byte(out, out_len, &cursor, '/') != 0) {
    return -1;
  }
  for (unsigned int index = 0; index < component_len; index += 1) {
    if (append_byte(out, out_len, &cursor, component[index]) != 0) {
      return -1;
    }
  }
  return 0;
}

static int resolve_path(const char *cwd, const char *path, char *out,
                        unsigned int out_len) {
  unsigned int cursor = 0;
  unsigned int start = 0;

  if (out_len < 2 || path[0] == '\0') {
    return -1;
  }
  out[0] = '/';
  out[1] = '\0';

  if (path[0] == '/') {
    start = 1;
  } else {
    if (cwd[0] == '\0' || cwd[0] != '/') {
      return -1;
    }
    if (append_text(out, out_len, &cursor, cwd) != 0) {
      return -1;
    }
    if (out[0] == '\0') {
      out[0] = '/';
      out[1] = '\0';
    }
  }

  while (path[start] != '\0') {
    unsigned int end = start;
    while (path[end] != '\0' && path[end] != '/') {
      end += 1;
    }
    if (push_path_component(out, out_len, path + start, end - start) != 0) {
      return -1;
    }
    start = end;
    while (path[start] == '/') {
      start += 1;
    }
  }
  return 0;
}

static int build_program_path(const char *name, char *out,
                              unsigned int out_len) {
  unsigned int cursor = 0;
  const char *program = text_equals(name, ALLOC_ALIAS) ? ALLOC_PROGRAM : name;
  out[0] = '\0';
  return append_text(out, out_len, &cursor, BIN_PREFIX) == 0 &&
                 append_text(out, out_len, &cursor, program) == 0 &&
                 append_text(out, out_len, &cursor, PROGRAM_SUFFIX) == 0
             ? 0
             : -1;
}

static int resolve_executable_path(const char *cwd, const char *name,
                                   char *out) {
  if (has_path_separator(name)) {
    return resolve_path(cwd, name, out, KRAFT_MAX_SHELL_PATH_BYTES + 1);
  }
  return build_program_path(name, out, KRAFT_MAX_SHELL_PATH_BYTES + 1);
}

static const char *status_name(unsigned int status, const char *fallback) {
  if (status == 0xfffffffeu) {
    return "NOENT";
  }
  if (status == 0xfffffff8u) {
    return "NOEXEC";
  }
  if (status == 0xfffffff7u) {
    return "BADFD";
  }
  if (status == 0xffffffe8u) {
    return "NOFD";
  }
  if (status == 0xffffffefu) {
    return "NOTEMPTY";
  }
  if (status == 0xffffffeau) {
    return "INVAL";
  }
  if (status == 0xfffffff4u) {
    return "NOMEM";
  }
  if (status == 0xfffffff2u) {
    return "FAULT";
  }
  if (status == 0xfffffff0u) {
    return "BUSY";
  }
  return fallback;
}

static void double_decimal_digits_and_add_bit(unsigned char *digits,
                                              unsigned int len,
                                              unsigned int *start,
                                              unsigned char bit) {
  unsigned char carry = bit;
  unsigned int index = len;
  while (index > *start) {
    unsigned char value;
    index -= 1;
    value = (unsigned char)(digits[index] * 2u + carry);
    if (value >= 10u) {
      digits[index] = (unsigned char)(value - 10u);
      carry = 1;
    } else {
      digits[index] = value;
      carry = 0;
    }
  }
  if (carry != 0 && *start > 0) {
    *start -= 1;
    digits[*start] = carry;
  }
}

static void write_decimal_bits(unsigned char *digits, unsigned int len,
                               unsigned int *start, unsigned int bits,
                               unsigned int count) {
  while (count > 0) {
    unsigned char bit;
    count -= 1;
    bit = (unsigned char)((bits >> count) & 1u);
    double_decimal_digits_and_add_bit(digits, len, start, bit);
  }
}

static void write_decimal_u32(unsigned int bits) {
  unsigned char digits[10];
  unsigned int start = 9;
  for (unsigned int index = 0; index < sizeof(digits); index += 1) {
    digits[index] = 0;
  }
  write_decimal_bits(digits, sizeof(digits), &start, bits, 32);
  for (unsigned int index = start; index < sizeof(digits); index += 1) {
    char byte = (char)(digits[index] + '0');
    write_all(STDOUT_FILENO, &byte, 1);
  }
}

static void write_decimal_words(unsigned int high, unsigned int low) {
  unsigned char digits[20];
  unsigned int start = 19;
  for (unsigned int index = 0; index < sizeof(digits); index += 1) {
    digits[index] = 0;
  }
  write_decimal_bits(digits, sizeof(digits), &start, high, 32);
  write_decimal_bits(digits, sizeof(digits), &start, low, 32);
  for (unsigned int index = start; index < sizeof(digits); index += 1) {
    char byte = (char)(digits[index] + '0');
    write_all(STDOUT_FILENO, &byte, 1);
  }
}

static unsigned int read_u32_le(const unsigned char *bytes) {
  return ((unsigned int)bytes[0]) | ((unsigned int)bytes[1] << 8) |
         ((unsigned int)bytes[2] << 16) | ((unsigned int)bytes[3] << 24);
}

static unsigned int parse_exit_status(const char *text, int *ok) {
  unsigned int status = 0;
  unsigned int index = 0;
  if (text == 0 || text[0] == '\0') {
    *ok = 1;
    return 0;
  }
  while (text[index] != '\0') {
    unsigned int digit;
    if (text[index] < '0' || text[index] > '9') {
      *ok = 0;
      return 0;
    }
    digit = (unsigned int)(text[index] - '0');
    unsigned int next = status * 10u + digit;
    if (next < status) {
      *ok = 0;
      return 0;
    }
    status = next;
    index += 1;
  }
  *ok = 1;
  return status;
}

static void classify_line(char *input, struct command *out) {
  char *cursor = input;
  out->kind = COMMAND_EMPTY;
  out->name = "";
  out->argc = 0;
  for (int index = 0; index < KRAFT_MAX_PROCESS_ARGS; index += 1) {
    out->args[index] = 0;
  }

  while (*cursor == ' ' || *cursor == '\t') {
    cursor += 1;
  }
  if (*cursor == '\0') {
    return;
  }
  if (matches_command(cursor, "clear")) {
    out->kind = COMMAND_CLEAR;
    return;
  }
  if (matches_command(cursor, "pwd")) {
    out->kind = COMMAND_PWD;
    return;
  }
  if (matches_command(cursor, "cd")) {
    out->kind = COMMAND_CD;
    return;
  }
  if (cursor[0] == 'c' && cursor[1] == 'd' && cursor[2] == ' ') {
    out->kind = COMMAND_CD;
    out->args[0] = cursor + 3;
    out->argc = 1;
    return;
  }
  if (matches_command(cursor, "ticks")) {
    out->kind = COMMAND_TICKS;
    return;
  }
  if (matches_command(cursor, "status")) {
    out->kind = COMMAND_STATUS;
    return;
  }
  if (is_echo_command(cursor)) {
    out->kind = COMMAND_ECHO;
    out->args[0] = cursor[4] == ' ' ? cursor + 5 : cursor + 4;
    out->argc = 1;
    return;
  }
  if (cursor[0] == 'e' && cursor[1] == 'x' && cursor[2] == 'i' &&
      cursor[3] == 't' &&
      (cursor[4] == '\0' || cursor[4] == ' ' || cursor[4] == '\t')) {
    out->kind = COMMAND_EXIT;
    cursor += 4;
    while (*cursor == ' ' || *cursor == '\t') {
      cursor += 1;
    }
    if (*cursor != '\0') {
      out->args[0] = cursor;
      out->argc = 1;
      while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t') {
        cursor += 1;
      }
      if (*cursor != '\0') {
        *cursor = '\0';
        cursor += 1;
        while (*cursor == ' ' || *cursor == '\t') {
          cursor += 1;
        }
        if (*cursor != '\0') {
          out->kind = COMMAND_INVALID;
        }
      }
    }
    return;
  }

  out->kind = COMMAND_EXEC;
  out->name = cursor;
  while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t') {
    cursor += 1;
  }
  while (*cursor != '\0') {
    *cursor = '\0';
    cursor += 1;
    while (*cursor == ' ' || *cursor == '\t') {
      cursor += 1;
    }
    if (*cursor == '\0') {
      return;
    }
    if (out->argc >= KRAFT_MAX_PROCESS_ARGS) {
      out->kind = COMMAND_INVALID;
      return;
    }
    out->args[out->argc] = cursor;
    out->argc += 1;
    while (*cursor != '\0' && *cursor != ' ' && *cursor != '\t') {
      cursor += 1;
    }
  }
}

static int should_resolve_path_arg(const char *name, const char *const *raw_args,
                                   int index) {
  if (text_equals(name, "ls") || text_equals(name, "cat") ||
      text_equals(name, "cp") || text_equals(name, "mv") ||
      text_equals(name, "stat") || text_equals(name, "rm") ||
      text_equals(name, "mkdir") || text_equals(name, "rmdir")) {
    return 1;
  }
  if (text_equals(name, "write")) {
    if (raw_args[0] != 0 && raw_args[1] != 0 && raw_args[2] == 0) {
      return index == 0;
    }
    if (raw_args[0] != 0 && text_equals(raw_args[0], "--append") &&
        raw_args[1] != 0 && raw_args[2] != 0 && raw_args[3] == 0) {
      return index == 1;
    }
  }
  return 0;
}

static void run_pwd(struct shell_state *state) {
  write_text(STDOUT_FILENO, state->cwd);
  write_text(STDOUT_FILENO, "\n");
}

static unsigned int run_cd(struct shell_state *state, const char *const *args,
                           int argc) {
  char path_buffer[KRAFT_MAX_SHELL_PATH_BYTES + 1];
  struct kraft_stat metadata;
  const char *path = argc == 0 ? "/" : args[0];

  if (argc > 1 || resolve_path(state->cwd, path, path_buffer,
                               sizeof(path_buffer)) != 0) {
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    return KRAFT_ERROR_INVALID;
  }
  int status = stat(path_buffer, &metadata);
  if (status < 0) {
    write_text(STDOUT_FILENO, "ERR ");
    write_text(STDOUT_FILENO, status_name((unsigned int)status, "RUN"));
    write_text(STDOUT_FILENO, "\n");
    return (unsigned int)status;
  }
  if (metadata.file_type != KRAFT_FILE_TYPE_DIRECTORY) {
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    return KRAFT_ERROR_INVALID;
  }
  for (unsigned int index = 0; index < sizeof(state->cwd); index += 1) {
    state->cwd[index] = path_buffer[index];
    if (path_buffer[index] == '\0') {
      break;
    }
  }
  return KRAFT_STATUS_OK;
}

static unsigned int run_ticks() {
  int status = (int)__k16_syscall1(
      KRAFT_SYSCALL_GAME_TICKS, (unsigned int)(unsigned long)ticks_bytes);
  write_text(STDOUT_FILENO, "TICKS ");
  if (status == 0) {
    write_decimal_words(read_u32_le(ticks_bytes + 4), read_u32_le(ticks_bytes));
    write_text(STDOUT_FILENO, "\n");
    return KRAFT_STATUS_OK;
  }
  write_text(STDOUT_FILENO, "ERR ");
  write_text(STDOUT_FILENO, status_name((unsigned int)status, "RUN"));
  write_text(STDOUT_FILENO, "\n");
  return (unsigned int)status;
}

static void write_status(unsigned int status) {
  write_text(STDOUT_FILENO, "STATUS ");
  if ((status & 0x80000000u) != 0) {
    write_text(STDOUT_FILENO, status_name(status, "RUN"));
  } else {
    write_decimal_u32(status);
  }
  write_text(STDOUT_FILENO, "\n");
}

static void run_echo(const char *text) {
  write_text(STDOUT_FILENO, text);
  write_text(STDOUT_FILENO, "\n");
}

static void write_child_exit_status(unsigned int status) {
  if (status == 0) {
    return;
  }
  write_text(STDOUT_FILENO, "ERR EXIT ");
  write_decimal_u32(status);
  write_text(STDOUT_FILENO, "\n");
}

static unsigned int write_run_error(unsigned int status) {
  write_text(STDOUT_FILENO, "ERR ");
  write_text(STDOUT_FILENO, status_name(status, "RUN"));
  write_text(STDOUT_FILENO, "\n");
  return status;
}

static unsigned int run_exec(struct shell_state *state, const char *name,
                             const char *const *raw_args, int argc) {
  char program_path[KRAFT_MAX_SHELL_PATH_BYTES + 1];
  char arg_paths[KRAFT_MAX_PROCESS_ARGS][KRAFT_MAX_SHELL_PATH_BYTES + 1];
  const char *argv[KRAFT_MAX_PROCESS_ARGS];

  if (resolve_executable_path(state->cwd, name, program_path) != 0) {
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    return KRAFT_ERROR_INVALID;
  }

  for (int index = 0; index < argc; index += 1) {
    if (should_resolve_path_arg(name, raw_args, index)) {
      if (resolve_path(state->cwd, raw_args[index], arg_paths[index],
                       sizeof(arg_paths[index])) != 0) {
        write_text(STDOUT_FILENO, "ERR INVAL\n");
        return KRAFT_ERROR_INVALID;
      }
      argv[index] = arg_paths[index];
    } else {
      argv[index] = raw_args[index];
    }
  }

  int status = kraft_run_with_args(program_path, argc, argv);
  if (status < 0) {
    return write_run_error((unsigned int)status);
  }
  write_child_exit_status((unsigned int)status);
  return (unsigned int)status;
}

static void dispatch_command(struct shell_state *state,
                             const struct command *command) {
  switch (command->kind) {
  case COMMAND_EMPTY:
    return;
  case COMMAND_INVALID:
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    state->last_status = KRAFT_ERROR_INVALID;
    return;
  case COMMAND_CLEAR:
    write_text(STDOUT_FILENO, "\f");
    state->last_status = KRAFT_STATUS_OK;
    return;
  case COMMAND_PWD:
    run_pwd(state);
    state->last_status = KRAFT_STATUS_OK;
    return;
  case COMMAND_CD:
    state->last_status = run_cd(state, command->args, command->argc);
    return;
  case COMMAND_TICKS:
    state->last_status = run_ticks();
    return;
  case COMMAND_STATUS:
    write_status(state->last_status);
    return;
  case COMMAND_EXIT: {
    int ok = 0;
    unsigned int status =
        parse_exit_status(command->argc == 0 ? 0 : command->args[0], &ok);
    if (!ok) {
      write_text(STDOUT_FILENO, "ERR INVAL\n");
      state->last_status = KRAFT_ERROR_INVALID;
      return;
    }
    _exit((int)status);
  }
  case COMMAND_ECHO:
    run_echo(command->args[0]);
    state->last_status = KRAFT_STATUS_OK;
    return;
  case COMMAND_EXEC:
    state->last_status =
        run_exec(state, command->name, command->args, command->argc);
    return;
  default:
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    state->last_status = KRAFT_ERROR_INVALID;
    return;
  }
}

int main(int argc, char **argv) {
  struct shell_state state;
  char read_buffer[1];

  (void)argc;
  (void)argv;

  state.cwd[0] = '/';
  state.cwd[1] = '\0';
  state.input_len = 0;
  state.last_status = KRAFT_STATUS_OK;

  write_text(STDOUT_FILENO, "K16 SHELL\n");
  for (;;) {
    write_text(STDOUT_FILENO, PROMPT);
    state.input_len = 0;
    state.input[0] = '\0';
    for (;;) {
      int count = read(STDIN_FILENO, read_buffer, sizeof(read_buffer));
      if (count < 0) {
        _exit(1);
      }
      for (int index = 0; index < count; index += 1) {
        char byte = read_buffer[index];
        if (byte == '\n' || byte == '\r') {
          struct command command;
          write_text(STDOUT_FILENO, "\n");
          state.input[state.input_len] = '\0';
          classify_line(state.input, &command);
          dispatch_command(&state, &command);
          goto next_prompt;
        }
        if (byte == '\b' || byte == 0x7f) {
          if (state.input_len > 0) {
            state.input_len -= 1;
            state.input[state.input_len] = '\0';
            write_text(STDOUT_FILENO, "\b");
          }
        } else if (byte >= 0x20 && byte <= 0x7e) {
          if (state.input_len + 1 < sizeof(state.input)) {
            state.input[state.input_len] = byte;
            state.input_len += 1;
            state.input[state.input_len] = '\0';
            write_all(STDOUT_FILENO, &byte, 1);
          }
        }
      }
    }
  next_prompt:;
  }
}
