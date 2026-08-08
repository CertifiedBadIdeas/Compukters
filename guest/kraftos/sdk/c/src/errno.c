#include <errno.h>

static int kraft_errno;

int *__errno_location(void) {
  return &kraft_errno;
}
