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

static unsigned int read_u32_le(const char *bytes) {
  return ((unsigned int)(unsigned char)bytes[0]) |
         ((unsigned int)(unsigned char)bytes[1] << 8) |
         ((unsigned int)(unsigned char)bytes[2] << 16) |
         ((unsigned int)(unsigned char)bytes[3] << 24);
}

static int write_dir_entry(unsigned int file_type, const char *name,
                           unsigned int name_len) {
  if (name_len == 0) {
    return -1;
  }

  write_all(STDOUT_FILENO, name, name_len);
  if (file_type == KRAFT_FILE_TYPE_DIRECTORY) {
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
    unsigned int remaining = (unsigned int)status - cursor;
    if (remaining < KRAFT_READ_DIR_ENTRY_FIXED_BYTES) {
      write_ls_error((int)0xffffffeau, path);
      return 1;
    }

    unsigned int file_type = read_u32_le(buffer + cursor);
    unsigned int name_len =
        read_u32_le(buffer + cursor + KRAFT_READ_DIR_ENTRY_NAME_LEN_OFFSET);
    if ((file_type != KRAFT_FILE_TYPE_REGULAR &&
         file_type != KRAFT_FILE_TYPE_DIRECTORY) ||
        name_len == 0 || name_len > remaining - KRAFT_READ_DIR_ENTRY_FIXED_BYTES) {
      write_ls_error((int)0xffffffeau, path);
      return 1;
    }
    unsigned int entry_len = KRAFT_READ_DIR_ENTRY_FIXED_BYTES + name_len;
    unsigned int size_bytes =
        read_u32_le(buffer + cursor + KRAFT_READ_DIR_ENTRY_NAME_OFFSET +
                    name_len);
    (void)size_bytes;

    if (write_dir_entry(
            file_type, buffer + cursor + KRAFT_READ_DIR_ENTRY_NAME_OFFSET,
            name_len) != 0) {
      write_ls_error((int)0xffffffeau, path);
      return 1;
    }
    cursor += entry_len;
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
