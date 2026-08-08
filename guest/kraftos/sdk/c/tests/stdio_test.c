#include <stdio.h>
#include <string.h>

int main(void) {
  char buffer[64];
  char truncated[5];
  int length;

  length = snprintf(buffer, sizeof(buffer), "%s %d %u %#x %p", "k16", -7, 9u, 42u, (void *)0x1234u);
  if (length != 20 || strcmp(buffer, "k16 -7 9 0x2a 0x1234") != 0) return 40;
  length = snprintf(truncated, sizeof(truncated), "abcdef");
  if (length != 6 || strcmp(truncated, "abcd") != 0) return 41;
  length = snprintf(buffer, sizeof(buffer), "[%*.*s]", -6, 3, "kraft");
  if (length != 8 || strcmp(buffer, "[kra   ]") != 0) return 42;
  length = snprintf(buffer, sizeof(buffer), "%.2f %.1g", 1.5, 25.0);
  if (length != 10 || strcmp(buffer, "1.50 2e+01") != 0) return 43;
  return 0;
}
