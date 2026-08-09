#ifndef RISCV_XLEN_U64_COMMON_H
#define RISCV_XLEN_U64_COMMON_H

typedef unsigned int u32;
typedef unsigned long long u64;
typedef long long i64;

static inline u64 rotate_left64(u64 value, u32 amount) {
    amount &= 63u;
    return (value << amount) | (value >> ((0u - amount) & 63u));
}

static inline u32 fold_u64(u64 value) {
    return (u32)value ^ (u32)(value >> 32u);
}

#endif
