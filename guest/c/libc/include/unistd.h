#ifndef KRAFT_UNISTD_H
#define KRAFT_UNISTD_H

#include <kraft/syscalls.h>

#define STDIN_FILENO KRAFT_FD_STDIN
#define STDOUT_FILENO KRAFT_FD_STDOUT
#define STDERR_FILENO KRAFT_FD_STDERR

int open(const char *path, int flags);
int read(int fd, void *buffer, unsigned int count);
int write(int fd, const void *buffer, unsigned int count);
int close(int fd);
int mkdir(const char *path);
int rmdir(const char *path);
int unlink(const char *path);
void *sbrk(int increment);
void _exit(int status);

#define open(path, flags) kraft_open((path), (unsigned int)(flags))

#endif
