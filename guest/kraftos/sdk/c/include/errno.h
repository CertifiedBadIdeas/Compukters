#ifndef KRAFT_SDK_ERRNO_H
#define KRAFT_SDK_ERRNO_H

#define ENOENT 2
#define ENOEXEC 8
#define EBADF 9
#define ENOMEM 12
#define EFAULT 14
#define EBUSY 16
#define EINVAL 22
#define EMFILE 24
#define EROFS 30
#define ENOTEMPTY 39

int *__errno_location(void);
#define errno (*__errno_location())

#endif
