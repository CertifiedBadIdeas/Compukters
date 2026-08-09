#include "common.h"

u32 kernel(u32 iterations) {
    volatile u32 cells[64];
    for (u32 index = 0; index < 64u; ++index) {
        cells[index] = 0;
    }
    u32 slot = 0;
    for (u32 index = 0; index < iterations; ++index) {
        slot = (slot * 17u + 11u) & 63u;
        cells[slot] = cells[slot] + index + 1u;
    }
    u32 sum = 0;
    for (u32 index = 0; index < 64u; ++index) {
        sum += cells[index];
    }
    return sum;
}
