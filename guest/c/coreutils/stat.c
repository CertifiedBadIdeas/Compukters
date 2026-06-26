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
  write_all(fd, output, output_len);
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

static void write_stat_error(int status, const char *path) {
  write_text(STDOUT_FILENO, "ERR ");
  write_text(STDOUT_FILENO, status_name(status, "STAT"));
  write_text(STDOUT_FILENO, " ");
  write_text(STDOUT_FILENO, path);
  write_text(STDOUT_FILENO, "\n");
}

static int stat_path(const char *path) {
  struct kraft_stat metadata;
  int status = stat(path, &metadata);

  if (status < 0) {
    write_stat_error(status, path);
    return 1;
  }

  if (metadata.file_type == KRAFT_FILE_TYPE_REGULAR) {
    write_text(STDOUT_FILENO, "FILE ");
  } else if (metadata.file_type == KRAFT_FILE_TYPE_DIRECTORY) {
    write_text(STDOUT_FILENO, "DIR ");
  } else {
    write_stat_error((int)0xffffffeau, path);
    return 1;
  }

  write_decimal(STDOUT_FILENO, metadata.size_bytes);
  write_text(STDOUT_FILENO, " ");
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
    if (stat_path(argv[index]) != 0) {
      exit_status = 1;
    }
  }
  return exit_status;
}
