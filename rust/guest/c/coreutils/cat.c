#include <kraft/syscalls.h>

static unsigned int c_strlen(const char *text) {
  unsigned int len = 0;
  while (text[len] != 0) {
    len += 1;
  }
  return len;
}

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
  write_all(fd, text, c_strlen(text));
}

static int copy_file(const char *path) {
  char buffer[256];
  int fd = open(path, KRAFT_OPEN_READ_ONLY);
  int exit_status = 0;

  if (fd < 0) {
    write_text(KRAFT_FD_STDERR, "cat: open failed: ");
    write_text(KRAFT_FD_STDERR, path);
    write_text(KRAFT_FD_STDERR, "\n");
    return 1;
  }

  for (;;) {
    int bytes_read = read(fd, buffer, sizeof(buffer));
    if (bytes_read == 0) {
      break;
    }
    if (bytes_read < 0) {
      write_text(KRAFT_FD_STDERR, "cat: read failed: ");
      write_text(KRAFT_FD_STDERR, path);
      write_text(KRAFT_FD_STDERR, "\n");
      exit_status = 1;
      break;
    }
    write_all(KRAFT_FD_STDOUT, buffer, (unsigned int)bytes_read);
  }

  if (close(fd) < 0) {
    exit_status = 1;
  }

  return exit_status;
}

int main(int argc, char **argv) {
  int exit_status = 0;

  if (argc <= 1) {
    write_text(KRAFT_FD_STDERR, "cat: missing operand\n");
    return 1;
  }

  for (int index = 1; index < argc; index += 1) {
    if (copy_file(argv[index]) != 0) {
      exit_status = 1;
    }
  }

  return exit_status;
}
