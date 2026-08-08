/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

union integer_bits {
    unsigned long long value;
    unsigned int words[2];
};

union double_bits {
    double value;
    unsigned int words[2];
};

union long_double_bits {
    long double value;
    unsigned int words[2];
};

static unsigned long long echo_integer(int prefix, unsigned long long value,
                                       int suffix)
{
    union integer_bits bits;

    bits.value = value;
    if (prefix != 3 || suffix != 5 || bits.words[0] != 0x55667788u ||
        bits.words[1] != 0x11223344u)
        return 0;
    return value;
}

static double echo_double(int first, int second, double value)
{
    union double_bits bits;

    bits.value = value;
    if (first != 7 || second != 9 || bits.words[0] != 0x54442d18u ||
        bits.words[1] != 0x400921fbu)
        return 0.0;
    return value;
}

static long double echo_long_double(long double value, int suffix)
{
    union long_double_bits bits;

    bits.value = value;
    if (suffix != 11 || bits.words[0] != 0x00000000u ||
        bits.words[1] != 0x3ff00000u)
        return 0.0L;
    return value;
}

int main(void)
{
    union integer_bits integer_in;
    union integer_bits integer_out;
    union double_bits double_in;
    union double_bits double_out;
    union long_double_bits long_double_in;
    union long_double_bits long_double_out;

    integer_in.words[0] = 0x55667788u;
    integer_in.words[1] = 0x11223344u;
    integer_out.value = echo_integer(3, integer_in.value, 5);
    if (integer_out.words[0] != integer_in.words[0] ||
        integer_out.words[1] != integer_in.words[1])
        return 1;

    double_in.words[0] = 0x54442d18u;
    double_in.words[1] = 0x400921fbu;
    double_out.value = echo_double(7, 9, double_in.value);
    if (double_out.words[0] != double_in.words[0] ||
        double_out.words[1] != double_in.words[1])
        return 2;

    long_double_in.words[0] = 0x00000000u;
    long_double_in.words[1] = 0x3ff00000u;
    long_double_out.value = echo_long_double(long_double_in.value, 11);
    if (long_double_out.words[0] != long_double_in.words[0] ||
        long_double_out.words[1] != long_double_in.words[1])
        return 3;

    return 42;
}
