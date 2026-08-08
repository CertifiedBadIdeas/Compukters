#include <errno.h>
#include <kraft/syscalls.h>
#include <string.h>
#include <unistd.h>

static int status_result(int result) {
  if (result >= 0) return result;
  errno = -result;
  return -1;
}

int open(const char *path, int flags, ...) {
  return status_result(__kraft_sys_open(path, strlen(path), (unsigned int)flags));
}

ssize_t read(int fd, void *buffer, size_t count) {
  return status_result(__kraft_sys_read(fd, buffer, count));
}

ssize_t write(int fd, const void *buffer, size_t count) {
  return status_result(__kraft_sys_write(fd, buffer, count));
}

off_t lseek(int fd, off_t offset, int origin) {
  return status_result(__kraft_sys_seek(fd, offset, (unsigned int)origin));
}

int close(int fd) {
  return status_result(__kraft_sys_close(fd));
}

void *sbrk(int increment) {
  void *result = __kraft_sys_sbrk(increment);
  int status = (int)result;
  if (status < 0) {
    errno = -status;
    return (void *)-1;
  }
  return result;
}

void _exit(int status) {
  __kraft_sys_exit(status);
  for (;;) {}
}
