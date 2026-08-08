/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "varargs-shared.h"

union k16_integer_bits {
    unsigned long long value;
    unsigned int words[2];
};

union k16_double_bits {
    double value;
    unsigned int words[2];
};

union k16_long_double_bits {
    long double value;
    unsigned int words[2];
};

int main(void)
{
    signed char promoted_signed = -7;
    unsigned short promoted_unsigned = 60000;
    int pointed = 17;
    union k16_integer_bits integer;
    union k16_double_bits real;
    union k16_long_double_bits long_real;
    struct k16_varargs_small small = { 5, 6 };
    struct k16_varargs_large large;
    union k16_integer_bits large_wide;
    k16_varargs_fn indirect = k16_verify_complete;
    int result;

    if (k16_verify_one_fixed(11, 31) != 42)
        return 101;
    if (k16_verify_three_fixed(1, 2, 3, &pointed) != 42)
        return 102;

    integer.words[0] = 0x55667788u;
    integer.words[1] = 0x11223344u;
    real.words[0] = 0x54442d18u;
    real.words[1] = 0x400921fbu;
    long_real.words[0] = 0x00000000u;
    long_real.words[1] = 0x3ff00000u;
    large_wide.words[0] = 0xddeeff00u;
    large_wide.words[1] = 0x99aabbccu;
    large.first = 7;
    large.wide = large_wide.value;
    large.last = 8;

    result = indirect(1, 2, 3, 4, promoted_signed, promoted_unsigned,
                      &pointed, integer.value, 1.5f, real.value,
                      long_real.value, small, large, 99);
    if (result != 42)
        return result;
    if (small.first != 5 || small.second != 6)
        return 103;
    if (large.first != 7 || large.last != 8)
        return 104;
    return 42;
}
