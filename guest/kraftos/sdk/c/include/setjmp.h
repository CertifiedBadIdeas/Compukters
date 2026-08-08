#ifndef KRAFT_SDK_SETJMP_H
#define KRAFT_SDK_SETJMP_H

typedef unsigned int jmp_buf[11];

int setjmp(jmp_buf environment) __attribute__((returns_twice));
void longjmp(jmp_buf environment, int value) __attribute__((noreturn));

#endif
