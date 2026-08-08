#include <errno.h>
#include <kraft/fs.h>
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

char *getcwd(char *buffer, size_t size) {
  if (buffer == NULL) {
    errno = EINVAL;
    return NULL;
  }
  if (size < 2) {
    errno = ERANGE;
    return NULL;
  }
  buffer[0] = '/';
  buffer[1] = 0;
  return buffer;
}

int unlink(const char *path) {
  size_t length;
  if (path == NULL) {
    errno = EINVAL;
    return -1;
  }
  length = strlen(path);
  if (length == 0 || length > KRAFT_MAX_PATH_BYTES) {
    errno = EINVAL;
    return -1;
  }
  return status_result(__kraft_sys_unlink(path, (unsigned int)length));
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
