#include "common.h"

u32 kernel(u32 iterations) {
    u32 sum = 0;
    for (u32 index = 0; index < iterations; ++index) {
        sum += (index & 1u) == 0 ? 1u : 3u;
    }
    return sum;
}
