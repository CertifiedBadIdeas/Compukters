#include "common.h"

u32 kernel(u32 iterations) {
    u64 cells[64];
    for (u32 index = 0; index < 64u; ++index) {
        cells[index] = (u64)index * 0xa5a3564e27f8862full + 7ull;
    }
    u32 slot = 0;
    for (u32 index = 0; index < iterations; ++index) {
        slot = (slot * 17u + 11u) & 63u;
        u64 value = cells[slot]
            + ((u64)index + 1ull) * 0x9e3779b97f4a7c15ull;
        value = rotate_left64(value, index & 63u) ^ 0xd6e8feb86659fd93ull;
        cells[slot] = value;
    }
    u64 checksum = 0;
    for (u32 index = 0; index < 64u; ++index) {
        checksum += cells[index];
    }
    return fold_u64(checksum);
}
