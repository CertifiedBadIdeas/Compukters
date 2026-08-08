#ifndef KRAFT_SDK_TIME_H
#define KRAFT_SDK_TIME_H

#include <sys/types.h>

typedef unsigned int clock_t;

struct tm {
  int tm_sec;
  int tm_min;
  int tm_hour;
  int tm_mday;
  int tm_mon;
  int tm_year;
  int tm_wday;
  int tm_yday;
  int tm_isdst;
};

#endif
