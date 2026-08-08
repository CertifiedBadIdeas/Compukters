#include <assert.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

int main(void) {
  char text[8] = "abcdef";
  char bounded[5];

  memmove(text + 1, text, 5);
  if (memcmp(text, "aabcde", 6) != 0) return 20;
  if (memchr(text, 'c', 6) != text + 3) return 21;
  if (memchr(text, 'z', 6) != NULL) return 22;
  if (strstr("kraftos", "fto") == NULL) return 23;
  if (strnlen("abcdef", 3) != 3) return 24;
  strncpy(bounded, "xy", sizeof(bounded));
  if (memcmp(bounded, "xy\0\0\0", sizeof(bounded)) != 0) return 25;
  return 0;
}
