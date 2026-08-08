#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

int main(void) {
  char buffer[8];
  int fd;

  errno = 0;
  if (open("/missing", O_RDONLY) != -1 || errno != ENOENT) return 30;
  errno = 0;
  if (open("/sdk/read-only", O_WRONLY) != -1 || errno != EROFS) return 31;

  fd = open("/eof", O_RDONLY);
  if (fd < 0 || read(fd, buffer, sizeof(buffer)) != 0 || close(fd) != 0) return 32;
  fd = open("/partial-read", O_RDONLY);
  if (fd < 0 || read(fd, buffer, sizeof(buffer)) != 3) return 33;
  if (memcmp(buffer, "k16", 3) != 0 || close(fd) != 0) return 34;
  fd = open("/partial-write", O_WRONLY);
  if (fd < 0 || write(fd, "hello", 5) != 2 || close(fd) != 0) return 35;

  errno = 0;
  if (read(99, buffer, 1) != -1 || errno != EBADF) return 36;
  errno = 0;
  if (lseek(99, 0, SEEK_SET) != -1 || errno != EINVAL) return 37;
  return 0;
}
