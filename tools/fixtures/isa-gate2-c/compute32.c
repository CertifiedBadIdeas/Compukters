#include "common.h"

u32 kernel(u32 iterations) {
    u32 accumulator = 0x6d2b79f5u;
    for (u32 index = 0; index < iterations; ++index) {
        accumulator = (accumulator + index * 17u) * 0x045d9f3bu
            ^ rotate_left(index, index & 31u);
        accumulator = rotate_left(accumulator, (index ^ accumulator) & 31u);
    }
    return accumulator;
}
