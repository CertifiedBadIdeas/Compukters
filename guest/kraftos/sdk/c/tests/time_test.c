#include <errno.h>
#include <stddef.h>
#include <sys/time.h>
#include <time.h>

int main(void) {
  struct timeval value;
  struct timezone zone;
  struct tm *broken_down;
  time_t seconds = -1;

  if (time(&seconds) != 2 || seconds != 2) return 110;
  if (gettimeofday(&value, NULL) != 0 || value.tv_sec != 2 || value.tv_usec != 50000) return 111;
  errno = 0;
  if (gettimeofday(&value, &zone) != -1 || errno != EINVAL) return 112;
  seconds = 0;
  broken_down = localtime(&seconds);
  if (broken_down == NULL || broken_down->tm_year != 70 || broken_down->tm_mon != 0 ||
      broken_down->tm_mday != 1 || broken_down->tm_wday != 4 || broken_down->tm_yday != 0) return 113;
  seconds = 86400;
  broken_down = localtime(&seconds);
  if (broken_down == NULL || broken_down->tm_mday != 2 || broken_down->tm_wday != 5) return 114;
  seconds = -1;
  errno = 0;
  if (localtime(&seconds) != NULL || errno != EINVAL) return 115;
  return 0;
}
