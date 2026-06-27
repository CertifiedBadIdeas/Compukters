#include <fcntl.h>
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

static int copy_file(const char *path) {
  char buffer[256];
  int fd = open(path, O_RDONLY);
  int exit_status = 0;

  if (fd < 0) {
    write_text(STDERR_FILENO, "cat: open failed: ");
    write_text(STDERR_FILENO, path);
    write_text(STDERR_FILENO, "\n");
    return 1;
  }

  for (;;) {
    int bytes_read = read(fd, buffer, sizeof(buffer));
    if (bytes_read == 0) {
      break;
    }
    if (bytes_read < 0) {
      write_text(STDERR_FILENO, "cat: read failed: ");
      write_text(STDERR_FILENO, path);
      write_text(STDERR_FILENO, "\n");
      exit_status = 1;
      break;
    }
    write_all(STDOUT_FILENO, buffer, (unsigned int)bytes_read);
  }

  if (close(fd) < 0) {
    exit_status = 1;
  }

  return exit_status;
}

int main(int argc, char **argv) {
  int exit_status = 0;

  if (argc <= 1) {
    write_text(STDERR_FILENO, "cat: missing operand\n");
    return 1;
  }

  for (int index = 1; index < argc; index += 1) {
    if (copy_file(argv[index]) != 0) {
      exit_status = 1;
    }
  }

  return exit_status;
}
