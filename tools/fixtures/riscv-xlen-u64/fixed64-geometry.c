#include "common.h"

u32 kernel(u32 iterations) {
    const i64 cos_q16 = 46341;
    const i64 sin_q16 = 46340;
    u64 checksum = 0x243f6a8885a308d3ull;
    for (u32 index = 0; index < iterations; ++index) {
        i64 x = ((i64)(index & 0xffffu) - 32768) * 65536;
        u32 y_index = index * 17u & 0xffffu;
        i64 y = ((i64)y_index - 32768) * 65536;
        i64 rotated_x = (x * cos_q16 - y * sin_q16) >> 16;
        i64 rotated_y = (x * sin_q16 + y * cos_q16) >> 16;
        checksum += (u64)rotated_x * 0x00000001000001b3ull
            ^ rotate_left64((u64)rotated_y, index & 63u);
    }
    return fold_u64(checksum);
}
