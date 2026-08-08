#ifndef KRAFT_SDK_SYSCALLS_H
#define KRAFT_SDK_SYSCALLS_H

#include <stddef.h>

int __kraft_sys_open(const char *path, unsigned int length, unsigned int flags)
    __asm__("kraft_sys_open");
int __kraft_sys_read(int fd, void *buffer, unsigned int count)
    __asm__("kraft_sys_read");
int __kraft_sys_write(int fd, const void *buffer, unsigned int count)
    __asm__("kraft_sys_write");
int __kraft_sys_seek(int fd, int offset, unsigned int origin)
    __asm__("kraft_sys_seek");
int __kraft_sys_close(int fd) __asm__("kraft_sys_close");
int __kraft_sys_game_ticks(unsigned int words[2])
    __asm__("kraft_sys_game_ticks");
int __kraft_sys_stat(const char *path, unsigned int length, void *metadata)
    __asm__("kraft_sys_stat");
int __kraft_sys_unlink(const char *path, unsigned int length)
    __asm__("kraft_sys_unlink");
int __kraft_sys_rename(const void *request, unsigned int length)
    __asm__("kraft_sys_rename");
void *__kraft_sys_sbrk(int increment) __asm__("kraft_sys_sbrk");
void __kraft_sys_exit(int status) __asm__("kraft_sys_exit");

#endif
