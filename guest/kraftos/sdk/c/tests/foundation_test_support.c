#include <fcntl.h>
#include <stddef.h>
#include <string.h>

static unsigned char heap[4096] __attribute__((aligned(8)));
static unsigned int heap_size;

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
  return -9;
}

int test_write(int fd, const void *buffer, unsigned int count) __asm__("kraft_sys_write");
int test_write(int fd, const void *buffer, unsigned int count) {
  (void)buffer;
  if (fd == 5) return count < 2 ? (int)count : 2;
  return -9;
}

int test_seek(int fd, int offset, unsigned int origin) __asm__("kraft_sys_seek");
int test_seek(int fd, int offset, unsigned int origin) {
  (void)fd;
  (void)offset;
  (void)origin;
  return -22;
}

int test_close(int fd) __asm__("kraft_sys_close");
int test_close(int fd) {
  if (fd >= 3 && fd <= 5) return 0;
  return -9;
}

void test_exit(int status) __asm__("kraft_sys_exit");
void test_exit(int status) {
  (void)status;
  for (;;) {}
}
