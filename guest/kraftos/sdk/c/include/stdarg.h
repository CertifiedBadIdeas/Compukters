#ifndef KRAFT_SDK_STDARG_H
#define KRAFT_SDK_STDARG_H

typedef __builtin_va_list va_list;

#define va_start(arguments, last) __builtin_va_start(arguments, last)
#define va_arg(arguments, type) __builtin_va_arg(arguments, type)
#define va_copy(destination, source) __builtin_va_copy(destination, source)
#define va_end(arguments) __builtin_va_end(arguments)

#endif
