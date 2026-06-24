#ifndef KRAFT_SYSCALLS_H
#define KRAFT_SYSCALLS_H

#define KRAFT_FD_STDIN 0
#define KRAFT_FD_STDOUT 1
#define KRAFT_FD_STDERR 2

#define KRAFT_OPEN_READ_ONLY 0

extern int __kraft_sys_open(const char *path, unsigned int len,
                            unsigned int flags) __asm__("open");
int read(int fd, void *buffer, unsigned int count);
int write(int fd, const void *buffer, unsigned int count);
int close(int fd);
void *sbrk(int increment);
void _exit(int status);

static inline unsigned int kraft_c_strlen(const char *text) {
  unsigned int len = 0;
  while (text[len] != 0) {
    len += 1;
  }
  return len;
}

static inline int kraft_open(const char *path, unsigned int flags) {
  return __kraft_sys_open(path, kraft_c_strlen(path), flags);
}

#define open(path, flags) kraft_open((path), (flags))

#endif
