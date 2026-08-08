#ifndef KRAFT_SDK_UNISTD_H
#define KRAFT_SDK_UNISTD_H

#include <stddef.h>
#include <sys/types.h>

#define STDIN_FILENO 0
#define STDOUT_FILENO 1
#define STDERR_FILENO 2

int open(const char *path, int flags, ...);
ssize_t read(int fd, void *buffer, size_t count);
ssize_t write(int fd, const void *buffer, size_t count);
off_t lseek(int fd, off_t offset, int origin);
int close(int fd);
void *sbrk(int increment);
void _exit(int status);

#endif
