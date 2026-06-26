#ifndef KRAFT_UNISTD_H
#define KRAFT_UNISTD_H

#include <kraft/syscalls.h>

#define STDIN_FILENO KRAFT_FD_STDIN
#define STDOUT_FILENO KRAFT_FD_STDOUT
#define STDERR_FILENO KRAFT_FD_STDERR

int open(const char *path, int flags);
int read(int fd, void *buffer, unsigned int count) __asm__("kraft_sys_read");
int write(int fd, const void *buffer, unsigned int count)
    __asm__("kraft_sys_write");
int close(int fd) __asm__("kraft_sys_close");
int kraft_mkdir(const char *path);
int kraft_rmdir(const char *path);
int kraft_unlink(const char *path);
void *sbrk(int increment) __asm__("kraft_sys_sbrk");
void _exit(int status) __asm__("kraft_sys_exit");

#define open(path, flags) kraft_open((path), (unsigned int)(flags))
#define mkdir(path) kraft_mkdir(path)
#define rmdir(path) kraft_rmdir(path)
#define unlink(path) kraft_unlink(path)

#endif
