#include <fcntl.h>
#include <kraft/fs.h>
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
  if ((unsigned int)status == KRAFT_ERROR_READ_ONLY) {
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

static void write_decimal(int fd, unsigned int value) {
  static const unsigned int divisors[] = {
      1000000000u, 100000000u, 10000000u, 1000000u, 100000u,
      10000u,      1000u,      100u,      10u,      1u,
  };
  char output[10];
  unsigned int output_len = 0;
  int started = 0;

  for (unsigned int index = 0; index < sizeof(divisors) / sizeof(divisors[0]);
       index += 1) {
    unsigned int digit = 0;
    while (value >= divisors[index]) {
      value -= divisors[index];
      digit += 1;
    }
    if (digit != 0 || started || divisors[index] == 1u) {
      output[output_len] = (char)('0' + digit);
      output_len += 1;
      started = 1;
    }
  }
  (void)write_all(fd, output, output_len);
}

static int write_payload(const char *path, const char *payload, int flags) {
  unsigned int len = strlen(payload);
  int fd = open(path, flags);
  int exit_status = 0;

  if (fd < 0) {
    write_path_error(fd, path);
    return 1;
  }

  int write_status = write_all(fd, payload, len);
  if (write_status < 0) {
    write_path_error(write_status, path);
    (void)close(fd);
    return 1;
  }
  if (close(fd) < 0) {
    exit_status = 1;
  }

  write_text(STDOUT_FILENO, "WROTE ");
  write_decimal(STDOUT_FILENO, len);
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");

  return exit_status;
}

int main(int argc, char **argv) {
  const char *path;
  const char *payload;
  int flags = O_WRONLY | O_CREAT | O_TRUNC;

  if (argc == 4 && strcmp(argv[1], "--append") == 0) {
    flags = O_WRONLY | O_CREAT | O_APPEND;
    path = argv[2];
    payload = argv[3];
  } else if (argc == 3) {
    path = argv[1];
    payload = argv[2];
  } else {
    write_text(STDERR_FILENO, "write: usage: write [--append] <path> <payload>\n");
    return 1;
  }

  return write_payload(path, payload, flags);
}
