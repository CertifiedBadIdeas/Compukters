#ifndef KRAFT_STRING_H
#define KRAFT_STRING_H

#include <stddef.h>

static inline unsigned int strlen(const char *text) {
  unsigned int len = 0;
  while (text[len] != 0) {
    len += 1;
  }
  return len;
}

static inline int strcmp(const char *left, const char *right) {
  unsigned int index = 0;
  while (left[index] != 0 && right[index] != 0) {
    if (left[index] != right[index]) {
      return (int)(unsigned char)left[index] - (int)(unsigned char)right[index];
    }
    index += 1;
  }
  return (int)(unsigned char)left[index] - (int)(unsigned char)right[index];
}

#endif
