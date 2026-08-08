#include <errno.h>
#include <string.h>

void *memcpy(void *destination, const void *source, size_t count) {
  unsigned char *out = destination;
  const unsigned char *in = source;
  for (size_t index = 0; index < count; index += 1) out[index] = in[index];
  return destination;
}

void *memmove(void *destination, const void *source, size_t count) {
  unsigned char *out = destination;
  const unsigned char *in = source;
  if (out < in) {
    for (size_t index = 0; index < count; index += 1) out[index] = in[index];
  } else if (out > in) {
    while (count != 0) {
      count -= 1;
      out[count] = in[count];
    }
  }
  return destination;
}

void *memset(void *destination, int value, size_t count) {
  unsigned char *out = destination;
  for (size_t index = 0; index < count; index += 1) out[index] = (unsigned char)value;
  return destination;
}

int memcmp(const void *left, const void *right, size_t count) {
  const unsigned char *lhs = left;
  const unsigned char *rhs = right;
  for (size_t index = 0; index < count; index += 1) {
    if (lhs[index] != rhs[index]) return (int)lhs[index] - (int)rhs[index];
  }
  return 0;
}

void *memchr(const void *memory, int character, size_t count) {
  const unsigned char *bytes = memory;
  unsigned char needle = (unsigned char)character;
  for (size_t index = 0; index < count; index += 1) {
    if (bytes[index] == needle) return (void *)(bytes + index);
  }
  return NULL;
}

size_t strlen(const char *text) {
  size_t length = 0;
  while (text[length] != 0) length += 1;
  return length;
}

size_t strnlen(const char *text, size_t maximum) {
  size_t length = 0;
  while (length < maximum && text[length] != 0) length += 1;
  return length;
}

int strncmp(const char *left, const char *right, size_t count) {
  for (size_t index = 0; index < count; index += 1) {
    unsigned char lhs = (unsigned char)left[index];
    unsigned char rhs = (unsigned char)right[index];
    if (lhs != rhs) return (int)lhs - (int)rhs;
    if (lhs == 0) return 0;
  }
  return 0;
}

int strcmp(const char *left, const char *right) {
  return strncmp(left, right, (size_t)-1);
}

char *strcpy(char *destination, const char *source) {
  size_t index = 0;
  do {
    destination[index] = source[index];
  } while (source[index++] != 0);
  return destination;
}

char *strncpy(char *destination, const char *source, size_t count) {
  size_t index = 0;
  while (index < count && source[index] != 0) {
    destination[index] = source[index];
    index += 1;
  }
  while (index < count) destination[index++] = 0;
  return destination;
}

char *strchr(const char *text, int character) {
  unsigned char needle = (unsigned char)character;
  do {
    if ((unsigned char)*text == needle) return (char *)text;
  } while (*text++ != 0);
  return NULL;
}

char *strrchr(const char *text, int character) {
  char *result = NULL;
  unsigned char needle = (unsigned char)character;
  do {
    if ((unsigned char)*text == needle) result = (char *)text;
  } while (*text++ != 0);
  return result;
}

char *strstr(const char *text, const char *needle) {
  size_t needle_length = strlen(needle);
  if (needle_length == 0) return (char *)text;
  while (*text != 0) {
    if (strncmp(text, needle, needle_length) == 0) return (char *)text;
    text += 1;
  }
  return NULL;
}

char *strerror(int error) {
  switch (error) {
    case ENOENT: return "No such file or directory";
    case ENOEXEC: return "Exec format error";
    case EBADF: return "Bad file descriptor";
    case ENOMEM: return "Cannot allocate memory";
    case EFAULT: return "Bad address";
    case EBUSY: return "Device or resource busy";
    case EINVAL: return "Invalid argument";
    case EMFILE: return "Too many open files";
    case EROFS: return "Read-only file system";
    case ENOTEMPTY: return "Directory not empty";
    default: return "Unknown error";
  }
}
