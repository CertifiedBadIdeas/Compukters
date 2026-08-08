#include <string.h>
#include <unistd.h>

static void write_all(int fd, const char *buffer, unsigned int len) {
  unsigned int written = 0;
  while (written < len) {
    int result = write(fd, buffer + written, len - written);
    if (result <= 0) {
      return;
    }
    written += (unsigned int)result;
  }
}

static void write_text(int fd, const char *text) {
  write_all(fd, text, strlen(text));
}

static const char *status_name(int status, const char *fallback) {
  unsigned int raw = (unsigned int)status;
  if (raw == 0xfffffffeu) {
    return "NOENT";
  }
  if (raw == 0xfffffff8u) {
    return "NOEXEC";
  }
  if (raw == 0xfffffff7u) {
    return "BADFD";
  }
  if (raw == 0xffffffe8u) {
    return "NOFD";
  }
  if (raw == 0xffffffefu) {
    return "NOTEMPTY";
  }
  if (raw == 0xffffffeau) {
    return "INVAL";
  }
  if (raw == 0xfffffff4u) {
    return "NOMEM";
  }
  if (raw == 0xfffffff2u) {
    return "FAULT";
  }
  if (raw == 0xfffffff0u) {
    return "BUSY";
  }
  if (raw == 0xffffffe2u) {
    return "ROFS";
  }
  return fallback;
}

static int make_dir(const char *path) {
  int status = mkdir(path);
  if (status < 0) {
    write_text(STDOUT_FILENO, "ERR ");
    write_text(STDOUT_FILENO, status_name(status, "MKDIR"));
    write_text(STDOUT_FILENO, " ");
    write_text(STDOUT_FILENO, path);
    write_text(STDOUT_FILENO, "\n");
    return 1;
  }

  write_text(STDOUT_FILENO, "CREATED ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");
  return 0;
}

int main(int argc, char **argv) {
  int exit_status = 0;

  if (argc < 2) {
    return 1;
  }

  for (int index = 1; index < argc; index += 1) {
    if (make_dir(argv[index]) != 0) {
      exit_status = 1;
    }
  }
  return exit_status;
}
