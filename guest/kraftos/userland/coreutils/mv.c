#include <kraft/fs.h>
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

static unsigned int text_len(const char *text) {
  unsigned int len = 0;
  while (text[len] != '\0') {
    len += 1;
  }
  return len;
}

static void write_text(int fd, const char *text) {
  write_all(fd, text, text_len(text));
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

static void write_path_error(int status, const char *path) {
  write_text(STDOUT_FILENO, "ERR ");
  write_text(STDOUT_FILENO, status_name(status, "RENAME"));
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");
}

static int is_noent(int status) { return (unsigned int)status == 0xfffffffeu; }

static int move_file(const char *source_path, const char *destination_path) {
  struct kraft_stat metadata;
  int status = stat(destination_path, &metadata);
  if (status == 0) {
    write_path_error((int)0xffffffeau, destination_path);
    return 1;
  }
  if (!is_noent(status)) {
    write_path_error(status, destination_path);
    return 1;
  }

  status = rename(source_path, destination_path);
  if (status < 0) {
    write_path_error(status, source_path);
    return 1;
  }

  write_text(STDOUT_FILENO, "MOVED ");
  write_text(STDOUT_FILENO, source_path);
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, destination_path);
  write_text(STDOUT_FILENO, "\n");
  return 0;
}

int main(int argc, char **argv) {
  if (argc != 3) {
    write_text(STDOUT_FILENO, "ERR INVAL\n");
    return 1;
  }

  return move_file(argv[1], argv[2]);
}
