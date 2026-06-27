#include <kraft/fs.h>
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
  return fallback;
}

static void write_ls_error(int status, const char *path) {
  write_text(STDOUT_FILENO, "ERR ");
  write_text(STDOUT_FILENO, status_name(status, "READDIR"));
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");
}

static int write_child_path(const char *base, const char *name,
                            unsigned int name_len, char *out,
                            unsigned int out_len) {
  unsigned int base_len = strlen(base);
  unsigned int separator_len = 1;
  unsigned int cursor = 0;

  if (base_len == 0 || name_len == 0) {
    return -1;
  }
  if (base_len == 1 && base[0] == '/') {
    separator_len = 0;
  }
  if (base_len + separator_len + name_len + 1 > out_len) {
    return -1;
  }

  for (unsigned int index = 0; index < base_len; index += 1) {
    out[cursor] = base[index];
    cursor += 1;
  }
  if (separator_len == 1) {
    out[cursor] = '/';
    cursor += 1;
  }
  for (unsigned int index = 0; index < name_len; index += 1) {
    out[cursor] = name[index];
    cursor += 1;
  }
  out[cursor] = '\0';
  return 0;
}

static int write_dir_entry(const char *path, const char *name,
                           unsigned int name_len) {
  char child_path[KRAFT_MAX_STAT_PATH_BYTES + 1];
  struct kraft_stat metadata;

  if (write_child_path(path, name, name_len, child_path, sizeof(child_path)) !=
      0) {
    return -1;
  }

  if (stat(child_path, &metadata) < 0) {
    return -1;
  }

  write_all(STDOUT_FILENO, name, name_len);
  if (metadata.file_type == KRAFT_FILE_TYPE_DIRECTORY) {
    write_text(STDOUT_FILENO, "/");
  }
  write_text(STDOUT_FILENO, "\n");
  return 0;
}

static int list_dir(const char *path) {
  char buffer[256];
  int status = read_dir(path, buffer, sizeof(buffer));
  unsigned int cursor = 0;

  if (status < 0) {
    write_ls_error(status, path);
    return 1;
  }

  while (cursor < (unsigned int)status) {
    unsigned int start = cursor;
    while (cursor < (unsigned int)status && buffer[cursor] != '\n') {
      cursor += 1;
    }
    if (cursor == start) {
      write_ls_error((int)0xffffffeau, path);
      return 1;
    }
    if (write_dir_entry(path, buffer + start, cursor - start) != 0) {
      write_ls_error((int)0xffffffeau, path);
      return 1;
    }
    if (cursor < (unsigned int)status) {
      cursor += 1;
    }
  }

  return 0;
}

int main(int argc, char **argv) {
  int exit_status = 0;

  for (int index = 1; index < argc || (argc <= 1 && index == 1); index += 1) {
    const char *path = argc > 1 ? argv[index] : "/bin";
    if (list_dir(path) != 0) {
      exit_status = 1;
    }
  }
  return exit_status;
}
