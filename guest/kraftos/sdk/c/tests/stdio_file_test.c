#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

unsigned int test_written_size(void);
const char *test_written_data(void);
unsigned int test_append_size(void);
const char *test_append_data(void);
unsigned int test_stdout_size(void);
const char *test_stdout_data(void);
unsigned int test_stderr_size(void);
const char *test_stderr_data(void);

int main(void) {
  char buffer[8] = {0};
  char payload[BUFSIZ + 8];
  FILE *stream;

  for (int index = 0; index < (int)sizeof(payload); index += 1) {
    payload[index] = (char)('a' + index % 26);
  }

  errno = 0;
  if (fopen("/file-read", "invalid") != NULL || errno != EINVAL) return 50;
  errno = 0;
  if (fopen("/file-read", "r+") != NULL || errno != EINVAL) return 63;

  stream = fopen("/file-write", "w");
  if (stream == NULL) return 51;
  if (fwrite(payload, 13, 40, stream) != 40 || ftell(stream) != sizeof(payload)) return 52;
  if (test_written_size() != BUFSIZ) return 53;
  if (fprintf(stream, ":%d", 42) != 3 || ftell(stream) != sizeof(payload) + 3) return 54;
  if (fflush(stream) != 0) return 54;
  if (test_written_size() != sizeof(payload) + 3 ||
      memcmp(test_written_data(), payload, sizeof(payload)) != 0 ||
      memcmp(test_written_data() + sizeof(payload), ":42", 3) != 0) return 55;
  if (fclose(stream) != 0) return 56;

  stream = fopen("/file-read", "r");
  if (stream == NULL) return 57;
  if (fread(buffer, 2, 4, stream) != 3 || memcmp(buffer, "abcdef", 6) != 0) return 58;
  if (fgetc(stream) != EOF || ftell(stream) != 6) return 59;
  if (fseek(stream, 0, SEEK_SET) != 0 || ftell(stream) != 0) return 60;
  if (fgetc(stream) != 'a' || ftell(stream) != 1) return 61;
  if (fseek(stream, 2, SEEK_CUR) != 0 || ftell(stream) != 3) return 64;
  if (fgetc(stream) != 'd' || ftell(stream) != 4) return 65;
  if (fseek(stream, 99, SEEK_SET) != -1 || ftell(stream) != 4) return 66;
  if (fgetc(stream) != 'e' || ftell(stream) != 5) return 67;
  if (fseek(stream, -2, SEEK_END) != 0 || ftell(stream) != 4) return 68;
  if (fgetc(stream) != 'e' || ftell(stream) != 5) return 69;
  if (fclose(stream) != 0) return 62;

  stream = fopen("/file-write", "w");
  if (stream == NULL || fwrite("all", 1, 3, stream) != 3) return 70;
  if (test_written_size() != 0 || fflush(NULL) != 0 ||
      test_written_size() != 3 || memcmp(test_written_data(), "all", 3) != 0) return 71;
  if (fclose(stream) != 0) return 72;

  stream = fopen("/file-append", "a");
  if (stream == NULL || ftell(stream) != 3 || fseek(stream, 0, SEEK_SET) != 0) return 73;
  if (fwrite("Z", 1, 1, stream) != 1 || fflush(stream) != 0 ||
      ftell(stream) != 4 || test_append_size() != 4 ||
      memcmp(test_append_data(), "abcZ", 4) != 0) return 74;
  if (fclose(stream) != 0) return 75;

  stream = fdopen(open("/file-append", O_WRONLY | O_CREAT | O_APPEND), "a");
  if (stream == NULL || ftell(stream) != 3) return 76;
  if (fwrite("Q", 1, 1, stream) != 1 || fflush(stream) != 0 ||
      memcmp(test_append_data(), "abcQ", 4) != 0) return 77;
  if (fclose(stream) != 0) return 78;

  stream = fopen("/write-error", "w");
  if (stream == NULL || fwrite(payload, 1, BUFSIZ, stream) != 0 || errno != EBADF) return 79;
  if (fclose(stream) != EOF) return 80;

  if (printf("stdout") != 6 || test_stdout_size() != 6 ||
      memcmp(test_stdout_data(), "stdout", 6) != 0) return 81;
  if (fputs("stderr", stderr) == EOF || test_stderr_size() != 6 ||
      memcmp(test_stderr_data(), "stderr", 6) != 0) return 82;
  return 0;
}
