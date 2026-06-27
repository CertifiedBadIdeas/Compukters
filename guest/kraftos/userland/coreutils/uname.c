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

int main(int argc, char **argv) {
  (void)argc;
  (void)argv;

  if (write_all(STDOUT_FILENO, "K16\n", 4) < 0) {
    return 1;
  }
  return 0;
}
