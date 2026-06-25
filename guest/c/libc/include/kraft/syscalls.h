#ifndef KRAFT_SYSCALLS_H
#define KRAFT_SYSCALLS_H

#define KRAFT_FD_STDIN 0
#define KRAFT_FD_STDOUT 1
#define KRAFT_FD_STDERR 2

#define KRAFT_OPEN_READ_ONLY 0
#define KRAFT_OPEN_WRITE_ONLY 1
#define KRAFT_OPEN_CREATE 2
#define KRAFT_OPEN_TRUNCATE 4
#define KRAFT_OPEN_APPEND 8

extern int __kraft_sys_open(const char *path, unsigned int len,
                            unsigned int flags) __asm__("open");
int kraft_open(const char *path, unsigned int flags);
int read(int fd, void *buffer, unsigned int count);
int write(int fd, const void *buffer, unsigned int count);
int close(int fd);
void *sbrk(int increment);
void _exit(int status);

#endif
