#include <kraft/syscalls.h>
#include <string.h>

static void put_u32_le(char *out, unsigned int value) {
  out[0] = (char)(value & 0xffu);
  out[1] = (char)((value >> 8) & 0xffu);
  out[2] = (char)((value >> 16) & 0xffu);
  out[3] = (char)((value >> 24) & 0xffu);
}

int kraft_open(const char *path, unsigned int flags) {
  return __kraft_sys_open(path, strlen(path), flags);
}

int kraft_read_dir(const char *path, char *out, unsigned int out_len) {
  char request[KRAFT_MAX_READ_DIR_REQUEST_BYTES];
  unsigned int path_len = strlen(path);
  unsigned int request_len = 16u + path_len;

  if (path_len > KRAFT_MAX_READ_DIR_PATH_BYTES) {
    return (int)0xffffffeau;
  }

  put_u32_le(request + 0, KRAFT_READ_DIR_REQUEST_MAGIC);
  put_u32_le(request + 4, path_len);
  put_u32_le(request + 8, (unsigned int)out);
  put_u32_le(request + 12, out_len);
  for (unsigned int index = 0; index < path_len; index += 1) {
    request[16u + index] = path[index];
  }

  return __kraft_sys_read_dir(request, request_len);
}

int kraft_stat(const char *path, struct kraft_stat *metadata) {
  unsigned int path_len = strlen(path);
  if (path_len > KRAFT_MAX_STAT_PATH_BYTES) {
    return (int)0xffffffeau;
  }
  return __kraft_sys_stat(path, path_len, metadata);
}

int kraft_rename(const char *old_path, const char *new_path) {
  char request[KRAFT_MAX_RENAME_REQUEST_BYTES];
  unsigned int old_len = strlen(old_path);
  unsigned int new_len = strlen(new_path);
  unsigned int cursor = 12u;
  unsigned int request_len = 12u + old_len + new_len;

  if (old_len == 0 || new_len == 0 ||
      old_len > KRAFT_MAX_RENAME_PATH_BYTES ||
      new_len > KRAFT_MAX_RENAME_PATH_BYTES) {
    return (int)0xffffffeau;
  }

  put_u32_le(request + 0, KRAFT_RENAME_REQUEST_MAGIC);
  put_u32_le(request + 4, old_len);
  put_u32_le(request + 8, new_len);
  for (unsigned int index = 0; index < old_len; index += 1) {
    request[cursor] = old_path[index];
    cursor += 1;
  }
  for (unsigned int index = 0; index < new_len; index += 1) {
    request[cursor] = new_path[index];
    cursor += 1;
  }

  return __kraft_sys_rename(request, request_len);
}

int kraft_mkdir(const char *path) {
  return __kraft_sys_mkdir(path, strlen(path));
}

int kraft_rmdir(const char *path) {
  return __kraft_sys_rmdir(path, strlen(path));
}

int kraft_unlink(const char *path) {
  return __kraft_sys_unlink(path, strlen(path));
}
