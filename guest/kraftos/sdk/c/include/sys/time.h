#ifndef KRAFT_SDK_SYS_TIME_H
#define KRAFT_SDK_SYS_TIME_H

#include <sys/types.h>

struct timeval {
  time_t tv_sec;
  int tv_usec;
};

struct timezone {
  int tz_minuteswest;
  int tz_dsttime;
};

#endif
