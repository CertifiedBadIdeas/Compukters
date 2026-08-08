#include <errno.h>
#include <stdint.h>
#include <stdlib.h>

int main(void) {
  unsigned char *zero = malloc(0);
  unsigned char *first = malloc(17);
  unsigned char *second;
  unsigned char *grown;

  if (zero == NULL) return 10;
  if (first == NULL || ((uintptr_t)first & 7u) != 0) return 11;
  for (unsigned int index = 0; index < 17; index += 1) {
    first[index] = (unsigned char)index;
  }
  second = calloc(4, 8);
  if (second == NULL) return 12;
  for (unsigned int index = 0; index < 32; index += 1) {
    if (second[index] != 0) return 13;
  }
  grown = realloc(first, 64);
  if (grown == NULL) return 14;
  for (unsigned int index = 0; index < 17; index += 1) {
    if (grown[index] != (unsigned char)index) return 15;
  }
  free(zero);
  free(second);
  free(grown);
  errno = 0;
  if (calloc(UINT32_MAX, 2) != NULL || errno != ENOMEM) return 16;
  return 0;
}
