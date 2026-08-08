#ifndef KRAFT_SDK_STRING_H
#define KRAFT_SDK_STRING_H

#include <stddef.h>

void *memcpy(void *destination, const void *source, size_t count);
void *memmove(void *destination, const void *source, size_t count);
void *memset(void *destination, int value, size_t count);
int memcmp(const void *left, const void *right, size_t count);
void *memchr(const void *memory, int character, size_t count);
size_t strlen(const char *text);
size_t strnlen(const char *text, size_t maximum);
int strcmp(const char *left, const char *right);
int strncmp(const char *left, const char *right, size_t count);
char *strcpy(char *destination, const char *source);
char *strncpy(char *destination, const char *source, size_t count);
char *strchr(const char *text, int character);
char *strrchr(const char *text, int character);
char *strstr(const char *text, const char *needle);
char *strcat(char *destination, const char *source);
char *strpbrk(const char *text, const char *characters);
char *strerror(int error);

#endif
