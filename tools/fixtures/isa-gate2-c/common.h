#ifndef ISA_GATE2_COMMON_H
#define ISA_GATE2_COMMON_H

typedef unsigned char u8;
typedef unsigned int u32;

#define NOINLINE __attribute__((noinline))

static inline u32 rotate_left(u32 value, u32 amount) {
    amount &= 31u;
    return (value << amount) | (value >> ((0u - amount) & 31u));
}

#endif
