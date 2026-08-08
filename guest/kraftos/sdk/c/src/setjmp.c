#include <setjmp.h>

void __k16_longjmp_restore(jmp_buf environment, int value)
    __attribute__((noreturn));

void longjmp(jmp_buf environment, int value) {
  __k16_longjmp_restore(environment, value == 0 ? 1 : value);
}
