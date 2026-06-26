#ifndef KRAFT_SYSCALLS_H
#define KRAFT_SYSCALLS_H

#include <kraft/fs.h>
#include <kraft/process.h>

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
extern int __kraft_sys_mkdir(const char *path, unsigned int len)
    __asm__("mkdir");
extern int __kraft_sys_rmdir(const char *path, unsigned int len)
    __asm__("rmdir");
extern int __kraft_sys_unlink(const char *path, unsigned int len)
    __asm__("unlink");
extern int __kraft_sys_read_dir(const void *request, unsigned int len)
    __asm__("read_dir");
extern int __kraft_sys_stat(const char *path, unsigned int len,
                            struct kraft_stat *metadata) __asm__("stat");
extern int __kraft_sys_rename(const void *request, unsigned int len)
    __asm__("rename");
extern int __kraft_sys_spawn(const void *request, unsigned int len)
    __asm__("spawn");
extern int __kraft_sys_wait(unsigned int pid, int *status) __asm__("wait");
int kraft_open(const char *path, unsigned int flags);
int kraft_read_dir(const char *path, char *out, unsigned int out_len);
int kraft_stat(const char *path, struct kraft_stat *metadata);
int kraft_rename(const char *old_path, const char *new_path);
int kraft_mkdir(const char *path);
int kraft_rmdir(const char *path);
int kraft_unlink(const char *path);
int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);
int kraft_wait(int pid, int *status);
int read(int fd, void *buffer, unsigned int count);
int write(int fd, const void *buffer, unsigned int count);
int close(int fd);
void *sbrk(int increment);
void _exit(int status);

#endif
