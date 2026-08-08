/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "varargs-shared.h"

typedef __builtin_va_list va_list;
#define va_start(ap, last) __builtin_va_start(ap, last)
#define va_arg(ap, type) __builtin_va_arg(ap, type)
#define va_copy(destination, source) __builtin_va_copy(destination, source)
#define va_end(ap) __builtin_va_end(ap)

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

int k16_verify_one_fixed(int fixed, ...)
{
    va_list ap;
    int unnamed;

    va_start(ap, fixed);
    unnamed = va_arg(ap, int);
    va_end(ap);
    return fixed == 11 && unnamed == 31 ? 42 : 101;
}

int k16_verify_three_fixed(int first, int second, int third, ...)
{
    va_list ap;
    int *pointer;

    va_start(ap, third);
    pointer = va_arg(ap, int *);
    va_end(ap);
    return first == 1 && second == 2 && third == 3 && *pointer == 17
               ? 42
               : 102;
}

int k16_verify_complete(int first, int second, int third, int fourth, ...)
{
    va_list ap;
    va_list copy;
    int promoted_signed;
    int promoted_unsigned;
    int copied_signed;
    int copied_unsigned;
    int *pointer;
    union k16_integer_bits integer;
    union k16_double_bits promoted_float;
    union k16_double_bits real;
    union k16_long_double_bits long_real;
    struct k16_varargs_small small;
    struct k16_varargs_large large;
    union k16_integer_bits large_wide;
    int tail;

    if (first != 1 || second != 2 || third != 3 || fourth != 4)
        return 1;

    va_start(ap, fourth);
    va_copy(copy, ap);
    promoted_signed = va_arg(ap, int);
    promoted_unsigned = va_arg(ap, int);
    copied_signed = va_arg(copy, int);
    copied_unsigned = va_arg(copy, int);
    va_end(copy);
    if (promoted_signed != -7)
        return 2;
    if (copied_signed != -7)
        return 12;
    if (promoted_unsigned != 60000)
        return 3;
    if (copied_unsigned != 60000)
        return 13;

    pointer = va_arg(ap, int *);
    if (*pointer != 17)
        return 4;

    integer.value = va_arg(ap, unsigned long long);
    if (integer.words[0] != 0x55667788u ||
        integer.words[1] != 0x11223344u)
        return 5;

    promoted_float.value = va_arg(ap, double);
    if (promoted_float.words[0] != 0x00000000u ||
        promoted_float.words[1] != 0x3ff80000u)
        return 6;

    real.value = va_arg(ap, double);
    if (real.words[0] != 0x54442d18u || real.words[1] != 0x400921fbu)
        return 7;

    long_real.value = va_arg(ap, long double);
    if (long_real.words[0] != 0x00000000u ||
        long_real.words[1] != 0x3ff00000u)
        return 8;

    small = va_arg(ap, struct k16_varargs_small);
    if (small.first != 5 || small.second != 6)
        return 9;
    small.first = 105;
    small.second = 106;

    large = va_arg(ap, struct k16_varargs_large);
    large_wide.value = large.wide;
    if (large.first != 7 || large_wide.words[0] != 0xddeeff00u ||
        large_wide.words[1] != 0x99aabbccu || large.last != 8)
        return 10;
    large.first = 107;
    large.last = 108;

    tail = va_arg(ap, int);
    va_end(ap);
    return tail == 99 ? 42 : 11;
}
