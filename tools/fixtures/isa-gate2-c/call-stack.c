#include "common.h"

NOINLINE u32 call_stack_inner(u32 value, u32 index, u32 a, u32 b, u32 c, u32 d) {
    return value + index + a + b + c + d;
}

static NOINLINE u32 call_stack_outer(u32 value, u32 index) {
    return call_stack_inner(value, index, 1u, 0u, 0u, 0u) + 2u;
}

u32 kernel(u32 iterations) {
    u32 accumulator = 0;
    for (u32 index = 0; index < iterations; ++index) {
        accumulator = call_stack_outer(accumulator, index);
    }
    return accumulator;
}
