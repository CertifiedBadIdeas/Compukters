#ifndef KRAFT_SDK_STDDEF_H
#define KRAFT_SDK_STDDEF_H

typedef unsigned int size_t;
typedef int ptrdiff_t;

#define NULL ((void *)0)
#define offsetof(type, member) __builtin_offsetof(type, member)

#endif
