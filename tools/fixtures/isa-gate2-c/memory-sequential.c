#include "common.h"

u32 kernel(u32 iterations) {
    volatile u32 cells[64];
    for (u32 index = 0; index < 64u; ++index) {
        cells[index] = 0;
    }
    for (u32 index = 0; index < iterations; ++index) {
        u32 slot = index & 63u;
        cells[slot] = cells[slot] + index + 1u;
    }
    u32 sum = 0;
    for (u32 index = 0; index < 64u; ++index) {
        sum += cells[index];
    }
    return sum;
}
