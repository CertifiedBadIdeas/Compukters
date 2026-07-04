#include <string.h>
#include <unistd.h>

static int write_all(int fd, const char *buffer, unsigned int len) {
  unsigned int written = 0;
  while (written < len) {
    int result = write(fd, buffer + written, len - written);
    if (result <= 0) {
      return -1;
    }
    written += (unsigned int)result;
  }
  return 0;
}

static int write_text(int fd, const char *text) {
  return write_all(fd, text, strlen(text));
}

static int write_line(const char *text) {
  if (write_text(STDOUT_FILENO, text) < 0) {
    return -1;
  }
  return write_all(STDOUT_FILENO, "\n", 1);
}

static int parse_count(const char *text, unsigned int *out) {
  unsigned int value = 0;
  unsigned int index = 0;

  if (text[0] == '\0') {
    return -1;
  }
  while (text[index] != '\0') {
    char ch = text[index];
    if (ch < '0' || ch > '9') {
      return -1;
    }
    value = value * 10u + (unsigned int)(ch - '0');
    index += 1;
  }
  *out = value;
  return 0;
}

static int write_joined_line(int argc, char **argv, int first_arg) {
  if (first_arg >= argc) {
    return write_line("y");
  }
  for (int index = first_arg; index < argc; index += 1) {
    if (index > first_arg && write_all(STDOUT_FILENO, " ", 1) < 0) {
      return -1;
    }
    if (write_text(STDOUT_FILENO, argv[index]) < 0) {
      return -1;
    }
  }
  return write_all(STDOUT_FILENO, "\n", 1);
}

int main(int argc, char **argv) {
  unsigned int count = 0;
  int bounded = 0;
  int first_text_arg = 1;

  if (argc >= 3 && strcmp(argv[1], "-n") == 0) {
    if (parse_count(argv[2], &count) != 0) {
      write_text(STDERR_FILENO, "ERR INVAL\n");
      return 1;
    }
    bounded = 1;
    first_text_arg = 3;
  }

  if (bounded) {
    for (unsigned int line = 0; line < count; line += 1) {
      if (write_joined_line(argc, argv, first_text_arg) < 0) {
        return 1;
      }
    }
    return 0;
  }

  while (1) {
    if (write_joined_line(argc, argv, first_text_arg) < 0) {
      return 1;
    }
  }
}
