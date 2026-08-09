#include "common.h"

u32 kernel(u32 iterations) {
    u8 source[256];
    volatile u8 destination[256];
    for (u32 index = 0; index < 256u; ++index) {
        source[index] = (u8)(index * 29u + 7u);
        destination[index] = 0;
    }
    u32 checksum = 0;
    for (u32 iteration = 0; iteration < iterations; ++iteration) {
        for (u32 index = 0; index < 256u; ++index) {
            destination[index] = source[index];
        }
        for (u32 index = 0; index < 256u; ++index) {
            checksum += (u32)destination[index] + iteration;
        }
    }
    return checksum;
}
