#include <kraft/syscalls.h>
#include <string.h>

int kraft_open(const char *path, unsigned int flags) {
  return __kraft_sys_open(path, strlen(path), flags);
}
