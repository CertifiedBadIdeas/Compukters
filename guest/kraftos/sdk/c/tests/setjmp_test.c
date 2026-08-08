#include <setjmp.h>

static __attribute__((noinline)) void jump_inner(jmp_buf environment,
                                                 int value) {
  volatile unsigned int stack_marker = 0x16a5u;

  if (stack_marker != 0x16a5u) return;
  longjmp(environment, value);
}

static __attribute__((noinline)) void jump_outer(jmp_buf environment,
                                                 int value) {
  volatile unsigned int stack_marker = 0x165au;

  if (stack_marker != 0x165au) return;
  jump_inner(environment, value);
}

int main(void) {
  jmp_buf first;
  jmp_buf second;
  volatile int retained = 11;

  if (setjmp(first) != 7) {
    retained = 29;
    jump_outer(first, 7);
    return 20;
  }
  if (retained != 29) return 21;

  if (setjmp(first) != 1) {
    jump_inner(first, 0);
    return 22;
  }

  if (setjmp(first) != 5) {
    if (setjmp(second) != 3) {
      jump_inner(second, 3);
      return 23;
    }
    jump_outer(first, 5);
    return 24;
  }
  return 0;
}
