#ifndef KRAFT_SDK_FCNTL_H
#define KRAFT_SDK_FCNTL_H

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 2
#define O_TRUNC 4
#define O_APPEND 8

#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2

int open(const char *path, int flags, ...);

#endif
