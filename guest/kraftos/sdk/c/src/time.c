#include <errno.h>
#include <kraft/syscalls.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/time.h>
#include <time.h>

#define KRAFT_GAME_TICKS_PER_SECOND 20u

static int read_game_ticks(uint64_t *ticks) {
  unsigned int words[2];
  int status = __kraft_sys_game_ticks(words);
  if (status < 0) {
    errno = -status;
    return -1;
  }
  *ticks = ((uint64_t)words[1] << 32) | words[0];
  return 0;
}

time_t time(time_t *result) {
  uint64_t ticks;
  time_t seconds;
  if (read_game_ticks(&ticks) != 0) return -1;
  seconds = (time_t)(ticks / KRAFT_GAME_TICKS_PER_SECOND);
  if (result != NULL) *result = seconds;
  return seconds;
}

int gettimeofday(struct timeval *time_value, struct timezone *timezone_value) {
  uint64_t ticks;
  if (time_value == NULL || timezone_value != NULL) {
    errno = EINVAL;
    return -1;
  }
  if (read_game_ticks(&ticks) != 0) return -1;
  time_value->tv_sec = (time_t)(ticks / KRAFT_GAME_TICKS_PER_SECOND);
  time_value->tv_usec = (int)(ticks % KRAFT_GAME_TICKS_PER_SECOND) * 50000;
  return 0;
}

static int leap_year(int year) {
  return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

struct tm *localtime(const time_t *value) {
  static struct tm result;
  static const unsigned char month_lengths[12] = {
      31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  unsigned int seconds;
  unsigned int days;
  unsigned int day_of_year;
  int year = 1970;
  int month = 0;
  if (value == NULL || *value < 0) {
    errno = EINVAL;
    return NULL;
  }
  seconds = (unsigned int)*value;
  days = seconds / 86400u;
  result.tm_sec = (int)(seconds % 60u);
  result.tm_min = (int)((seconds / 60u) % 60u);
  result.tm_hour = (int)((seconds / 3600u) % 24u);
  result.tm_wday = (int)((days + 4u) % 7u);
  while (days >= (unsigned int)(leap_year(year) ? 366 : 365)) {
    days -= (unsigned int)(leap_year(year) ? 366 : 365);
    year += 1;
  }
  day_of_year = days;
  while (month < 11) {
    unsigned int length = month_lengths[month];
    if (month == 1 && leap_year(year)) length += 1;
    if (days < length) break;
    days -= length;
    month += 1;
  }
  result.tm_mday = (int)days + 1;
  result.tm_mon = month;
  result.tm_year = year - 1900;
  result.tm_yday = (int)day_of_year;
  result.tm_isdst = 0;
  return &result;
}
