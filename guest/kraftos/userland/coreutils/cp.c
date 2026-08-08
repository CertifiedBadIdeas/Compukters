#include <fcntl.h>
#include <string.h>
#include <unistd.h>

static int write_all(int fd, const char *buffer, unsigned int len) {
  unsigned int written = 0;
  while (written < len) {
    int result = write(fd, buffer + written, len - written);
    if (result <= 0) {
      return result < 0 ? result : (int)0xfffffff2u;
    }
    written += (unsigned int)result;
  }
  return 0;
}

static void write_text(int fd, const char *text) {
  (void)write_all(fd, text, strlen(text));
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
  write_text(STDOUT_FILENO, status_name(status, "IO"));
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");
}

static int copy_open_files(int source, int destination, int *failed_path) {
  char buffer[64];
  for (;;) {
    int bytes_read = read(source, buffer, sizeof(buffer));
    if (bytes_read == 0) {
      return 0;
    }
    if (bytes_read < 0) {
      *failed_path = 0;
      return bytes_read;
    }
    int result = write_all(destination, buffer, (unsigned int)bytes_read);
    if (result < 0) {
      *failed_path = 1;
      return result;
    }
  }
}

static int copy_file(const char *source_path, const char *destination_path) {
  int source = open(source_path, O_RDONLY);
  int destination;
  int copy_status;
  int source_close;
  int destination_close;
  int failed_path = 0;

  if (source < 0) {
    write_path_error(source, source_path);
    return 1;
  }

  destination = open(destination_path, O_WRONLY | O_CREAT | O_TRUNC);
  if (destination < 0) {
    close(source);
    write_path_error(destination, destination_path);
    return 1;
  }

  copy_status = copy_open_files(source, destination, &failed_path);
  source_close = close(source);
  destination_close = close(destination);

  if (copy_status < 0) {
    write_path_error(copy_status, failed_path == 0 ? source_path : destination_path);
    return 1;
  }
  if (source_close < 0) {
    write_path_error(source_close, source_path);
    return 1;
  }
  if (destination_close < 0) {
    write_path_error(destination_close, destination_path);
    return 1;
  }

  write_text(STDOUT_FILENO, "COPIED ");
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

  return copy_file(argv[1], argv[2]);
}
