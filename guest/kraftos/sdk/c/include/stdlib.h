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
int atoi(const char *text);
long atol(const char *text);
long long atoll(const char *text);
long strtol(const char *text, char **end, int base);
unsigned long strtoul(const char *text, char **end, int base);
long long strtoll(const char *text, char **end, int base);
unsigned long long strtoull(const char *text, char **end, int base);
double strtod(const char *text, char **end);
float strtof(const char *text, char **end);
long double strtold(const char *text, char **end);
void qsort(void *base, size_t count, size_t size, int (*compare)(const void *, const void *));
void *bsearch(
    const void *key,
    const void *base,
    size_t count,
    size_t size,
    int (*compare)(const void *, const void *)
);
int abs(int value);
long labs(long value);
long long llabs(long long value);
char *getenv(const char *name);
char *realpath(const char *path, char *resolved_path);
int mkstemp(char *template_path);

#endif
