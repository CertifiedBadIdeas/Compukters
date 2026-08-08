#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

unsigned int test_stat_calls(void);
const char *test_stat_path(void);
unsigned int test_stat_path_size(void);
unsigned int test_unlink_calls(void);
const char *test_unlink_path(void);
unsigned int test_unlink_path_size(void);
unsigned int test_rename_calls(void);
const unsigned char *test_rename_request(void);
unsigned int test_rename_request_size(void);

int main(void) {
  char path[230];
  char cwd[2];
  char *allocated;
  unsigned int calls;
  unsigned int index;
  static const unsigned char expected_rename[] = {
      0x52, 0x4e, 0x41, 0x4d,
      0x08, 0x00, 0x00, 0x00,
      0x08, 0x00, 0x00, 0x00,
      '/', 'o', 'l', 'd', '.', 't', 'x', 't',
      '/', 'n', 'e', 'w', '.', 't', 'x', 't',
  };

  errno = 0;
  if (getcwd(NULL, 2) != NULL || errno != EINVAL) return 110;
  errno = 0;
  if (getcwd(cwd, 1) != NULL || errno != ERANGE) return 111;
  if (getcwd(cwd, sizeof(cwd)) != cwd || strcmp(cwd, "/") != 0) return 112;

  if (realpath("///exists/./folder/../file", path) != path ||
      strcmp(path, "/exists/file") != 0 ||
      test_stat_path_size() != 12 ||
      memcmp(test_stat_path(), "/exists/file", 12) != 0) return 113;
  if (realpath("../../exists//file", path) != path ||
      strcmp(path, "/exists/file") != 0) return 114;
  allocated = realpath("exists/file", NULL);
  if (allocated == NULL || strcmp(allocated, "/exists/file") != 0) return 115;
  free(allocated);

  errno = 0;
  if (realpath("/missing/../missing", path) != NULL || errno != ENOENT) return 116;

  path[0] = '/';
  for (index = 1; index < 228; index += 1) path[index] = 'a';
  path[228] = 0;
  if (realpath(path, path) != path || strlen(path) != 228) return 117;
  path[228] = 'a';
  path[229] = 0;
  calls = test_stat_calls();
  errno = 0;
  if (realpath(path, path) != NULL || errno != EINVAL ||
      test_stat_calls() != calls) return 118;

  calls = test_unlink_calls();
  if (unlink("/remove-me") != 0 || test_unlink_calls() != calls + 1 ||
      test_unlink_path_size() != 10 ||
      memcmp(test_unlink_path(), "/remove-me", 10) != 0) return 119;
  if (remove("/remove-via-stdio") != 0 ||
      test_unlink_path_size() != 17 ||
      memcmp(test_unlink_path(), "/remove-via-stdio", 17) != 0) return 120;
  errno = 0;
  if (unlink("/sdk/read-only") != -1 || errno != EROFS) return 121;

  calls = test_rename_calls();
  if (rename("/old.txt", "/new.txt") != 0 || test_rename_calls() != calls + 1 ||
      test_rename_request_size() != sizeof(expected_rename) ||
      memcmp(test_rename_request(), expected_rename, sizeof(expected_rename)) != 0) return 122;
  errno = 0;
  if (rename("", "/new.txt") != -1 || errno != EINVAL ||
      test_rename_calls() != calls + 1) return 123;
  errno = 0;
  if (rename("/missing", "/new.txt") != -1 || errno != ENOENT) return 124;
  return 0;
}
