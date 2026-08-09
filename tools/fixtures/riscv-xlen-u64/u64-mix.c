#include "common.h"

u32 kernel(u32 iterations) {
    u64 state = 0x9e3779b97f4a7c15ull;
    for (u32 index = 0; index < iterations; ++index) {
        u64 lane = (u64)index * 0xd6e8feb86659fd93ull + 0xa5a3564e27f8862full;
        state ^= rotate_left64(lane, index & 63u);
        state = state * 0x9e3779b185ebca87ull + 0x632be59bd9b4e019ull;
        state ^= state >> 29u;
    }
    return fold_u64(state);
}
