#ifndef KRAFT_SDK_SYS_STAT_H
#define KRAFT_SDK_SYS_STAT_H

#include <sys/types.h>

struct stat {
  mode_t st_mode;
  off_t st_size;
};

#define S_IFREG 0100000u
#define S_IFDIR 0040000u

#endif
