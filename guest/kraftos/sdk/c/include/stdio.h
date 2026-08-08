#ifndef KRAFT_SDK_STDIO_H
#define KRAFT_SDK_STDIO_H

#include <stdarg.h>
#include <stddef.h>

typedef struct __kraft_file FILE;

extern FILE *stdin;
extern FILE *stdout;
extern FILE *stderr;

#define EOF (-1)
#define BUFSIZ 512
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2

FILE *fopen(const char *path, const char *mode);
FILE *fdopen(int descriptor, const char *mode);
FILE *freopen(const char *path, const char *mode, FILE *stream);
int fclose(FILE *stream);
size_t fread(void *buffer, size_t size, size_t count, FILE *stream);
size_t fwrite(const void *buffer, size_t size, size_t count, FILE *stream);
int fseek(FILE *stream, long offset, int origin);
long ftell(FILE *stream);
int fflush(FILE *stream);
int fgetc(FILE *stream);
char *fgets(char *buffer, int size, FILE *stream);
int fputc(int character, FILE *stream);
int fputs(const char *text, FILE *stream);
int fileno(FILE *stream);
int remove(const char *path);
int rename(const char *old_path, const char *new_path);
void perror(const char *prefix);
int printf(const char *format, ...);
int fprintf(FILE *stream, const char *format, ...);
int sprintf(char *buffer, const char *format, ...);
int snprintf(char *buffer, size_t size, const char *format, ...);
int vprintf(const char *format, va_list arguments);
int vfprintf(FILE *stream, const char *format, va_list arguments);
int vsprintf(char *buffer, const char *format, va_list arguments);
int vsnprintf(char *buffer, size_t size, const char *format, va_list arguments);
int getchar(void);
int putchar(int character);
int puts(const char *text);

#endif
