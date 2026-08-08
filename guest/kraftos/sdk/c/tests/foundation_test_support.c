#include <fcntl.h>
#include <stddef.h>
#include <string.h>

static unsigned char heap[4096] __attribute__((aligned(8)));
static unsigned int heap_size;
static const char file_read_data[] = "abcdef";
static unsigned int file_read_offset;
static char file_write_data[1024];
static unsigned int file_write_size;
static char file_append_data[32] = "abc";
static unsigned int file_append_size = 3;
static unsigned int file_append_offset;
static char standard_output_data[32];
static unsigned int standard_output_size;
static char standard_error_data[32];
static unsigned int standard_error_size;
static char stat_path[230];
static unsigned int stat_path_size;
static unsigned int stat_calls;
static char unlink_path[230];
static unsigned int unlink_path_size;
static unsigned int unlink_calls;
static unsigned char rename_request[468];
static unsigned int rename_request_size;
static unsigned int rename_calls;

unsigned int test_written_size(void) { return file_write_size; }
const char *test_written_data(void) { return file_write_data; }
unsigned int test_append_size(void) { return file_append_size; }
const char *test_append_data(void) { return file_append_data; }
unsigned int test_stdout_size(void) { return standard_output_size; }
const char *test_stdout_data(void) { return standard_output_data; }
unsigned int test_stderr_size(void) { return standard_error_size; }
const char *test_stderr_data(void) { return standard_error_data; }
unsigned int test_stat_calls(void) { return stat_calls; }
const char *test_stat_path(void) { return stat_path; }
unsigned int test_stat_path_size(void) { return stat_path_size; }
unsigned int test_unlink_calls(void) { return unlink_calls; }
const char *test_unlink_path(void) { return unlink_path; }
unsigned int test_unlink_path_size(void) { return unlink_path_size; }
unsigned int test_rename_calls(void) { return rename_calls; }
const unsigned char *test_rename_request(void) { return rename_request; }
unsigned int test_rename_request_size(void) { return rename_request_size; }

void *test_sbrk(int increment) __asm__("kraft_sys_sbrk");
void *test_sbrk(int increment) {
  unsigned int next;
  void *previous;
  if (increment < 0) return (void *)-22;
  next = heap_size + (unsigned int)increment;
  if (next < heap_size || next > sizeof(heap)) return (void *)-12;
  previous = heap + heap_size;
  heap_size = next;
  return previous;
}

int test_open(const char *path, unsigned int length, unsigned int flags)
    __asm__("kraft_sys_open");
int test_open(const char *path, unsigned int length, unsigned int flags) {
  (void)length;
  if (strcmp(path, "/eof") == 0 && flags == O_RDONLY) return 3;
  if (strcmp(path, "/partial-read") == 0 && flags == O_RDONLY) return 4;
  if (strcmp(path, "/partial-write") == 0 && flags == O_WRONLY) return 5;
  if (strcmp(path, "/file-read") == 0 && flags == O_RDONLY) {
    file_read_offset = 0;
    return 6;
  }
  if (strcmp(path, "/file-write") == 0 &&
      flags == (O_WRONLY | O_CREAT | O_TRUNC)) {
    file_write_size = 0;
    return 7;
  }
  if (strcmp(path, "/file-append") == 0 &&
      flags == (O_WRONLY | O_CREAT | O_APPEND)) {
    memcpy(file_append_data, "abc", 3);
    file_append_size = 3;
    file_append_offset = 3;
    return 8;
  }
  if (strcmp(path, "/write-error") == 0 &&
      flags == (O_WRONLY | O_CREAT | O_TRUNC)) return 9;
  if (strcmp(path, "/sdk/read-only") == 0) return -30;
  return -2;
}

int test_read(int fd, void *buffer, unsigned int count) __asm__("kraft_sys_read");
int test_read(int fd, void *buffer, unsigned int count) {
  if (fd == 3) return 0;
  if (fd == 4) {
    unsigned int returned = count < 3 ? count : 3;
    memcpy(buffer, "k16", returned);
    return (int)returned;
  }
  if (fd == 6) {
    unsigned int remaining = 6 - file_read_offset;
    unsigned int returned = count < remaining ? count : remaining;
    if (returned > 2) returned = 2;
    memcpy(buffer, file_read_data + file_read_offset, returned);
    file_read_offset += returned;
    return (int)returned;
  }
  return -9;
}

int test_write(int fd, const void *buffer, unsigned int count) __asm__("kraft_sys_write");
int test_write(int fd, const void *buffer, unsigned int count) {
  if (fd == 1 || fd == 2) {
    char *destination = fd == 1 ? standard_output_data : standard_error_data;
    unsigned int *size = fd == 1 ? &standard_output_size : &standard_error_size;
    unsigned int returned = count < 2 ? count : 2;
    if (returned > 32 - *size) returned = 32 - *size;
    memcpy(destination + *size, buffer, returned);
    *size += returned;
    return (int)returned;
  }
  if (fd == 5) return count < 2 ? (int)count : 2;
  if (fd == 7) {
    unsigned int returned = count < 2 ? count : 2;
    if (returned > sizeof(file_write_data) - file_write_size) {
      returned = sizeof(file_write_data) - file_write_size;
    }
    memcpy(file_write_data + file_write_size, buffer, returned);
    file_write_size += returned;
    return (int)returned;
  }
  if (fd == 8) {
    unsigned int returned = count < 2 ? count : 2;
    if (returned > sizeof(file_append_data) - file_append_offset) {
      returned = sizeof(file_append_data) - file_append_offset;
    }
    memcpy(file_append_data + file_append_offset, buffer, returned);
    file_append_offset += returned;
    if (file_append_offset > file_append_size) file_append_size = file_append_offset;
    return (int)returned;
  }
  if (fd == 9) return -9;
  return -9;
}

int test_seek(int fd, int offset, unsigned int origin) __asm__("kraft_sys_seek");
int test_seek(int fd, int offset, unsigned int origin) {
  if (fd == 6 && origin == SEEK_SET && offset >= 0 && offset <= 6) {
    file_read_offset = (unsigned int)offset;
    return offset;
  }
  if (fd == 6 && origin == SEEK_END && offset == 0) {
    file_read_offset = 6;
    return 6;
  }
  if (fd == 7 && origin == SEEK_SET && offset >= 0 && (unsigned int)offset <= file_write_size) {
    return offset;
  }
  if (fd == 7 && origin == SEEK_END && offset == 0) return (int)file_write_size;
  if (fd == 8 && origin == SEEK_SET && offset >= 0 && (unsigned int)offset <= file_append_size) {
    file_append_offset = (unsigned int)offset;
    return offset;
  }
  if (fd == 8 && origin == SEEK_END && offset == 0) {
    file_append_offset = file_append_size;
    return (int)file_append_size;
  }
  return -22;
}

int test_close(int fd) __asm__("kraft_sys_close");
int test_close(int fd) {
  if (fd >= 3 && fd <= 9) return 0;
  return -9;
}

int test_game_ticks(unsigned int words[2]) __asm__("kraft_sys_game_ticks");
int test_game_ticks(unsigned int words[2]) {
  words[0] = 41;
  words[1] = 0;
  return 0;
}

int test_stat(const char *path, unsigned int length, void *metadata)
    __asm__("kraft_sys_stat");
int test_stat(const char *path, unsigned int length, void *metadata) {
  unsigned int *words = metadata;
  stat_calls += 1;
  stat_path_size = length;
  if (length < sizeof(stat_path)) {
    memcpy(stat_path, path, length);
    stat_path[length] = 0;
  }
  if ((length == 1 && memcmp(path, "/", 1) == 0) ||
      (length == 12 && memcmp(path, "/exists/file", 12) == 0) ||
      length == 228) {
    if (words != NULL) {
      words[0] = 1;
      words[1] = 0;
      words[2] = 0;
      words[3] = 0;
    }
    return 0;
  }
  return -2;
}

int test_unlink(const char *path, unsigned int length) __asm__("kraft_sys_unlink");
int test_unlink(const char *path, unsigned int length) {
  unlink_calls += 1;
  unlink_path_size = length;
  if (length < sizeof(unlink_path)) {
    memcpy(unlink_path, path, length);
    unlink_path[length] = 0;
  }
  if (length == 14 && memcmp(path, "/sdk/read-only", 14) == 0) return -30;
  return 0;
}

int test_rename(const void *request, unsigned int length)
    __asm__("kraft_sys_rename");
int test_rename(const void *request, unsigned int length) {
  const unsigned char *bytes = request;
  unsigned int copy_size = length < sizeof(rename_request) ? length : sizeof(rename_request);
  rename_calls += 1;
  rename_request_size = length;
  memcpy(rename_request, bytes, copy_size);
  if (length >= 20 && bytes[4] == 8 && bytes[5] == 0 &&
      memcmp(bytes + 12, "/missing", 8) == 0) return -2;
  return 0;
}

void test_exit(int status) __asm__("kraft_sys_exit");
void test_exit(int status) {
  (void)status;
  for (;;) {}
}
