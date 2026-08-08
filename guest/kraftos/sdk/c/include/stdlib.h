#ifndef KRAFT_SDK_STDLIB_H
#define KRAFT_SDK_STDLIB_H

#include <stddef.h>

#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1

void *malloc(size_t size);
void *calloc(size_t count, size_t size);
void *realloc(void *pointer, size_t size);
void free(void *pointer);
void abort(void);
void exit(int status);

#endif
