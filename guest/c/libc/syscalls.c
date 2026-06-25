#include <kraft/syscalls.h>
#include <string.h>

int kraft_open(const char *path, unsigned int flags) {
  return __kraft_sys_open(path, strlen(path), flags);
}

int mkdir(const char *path) { return __kraft_sys_mkdir(path, strlen(path)); }

int rmdir(const char *path) { return __kraft_sys_rmdir(path, strlen(path)); }

int unlink(const char *path) { return __kraft_sys_unlink(path, strlen(path)); }
