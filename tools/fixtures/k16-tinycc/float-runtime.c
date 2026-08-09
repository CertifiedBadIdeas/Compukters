/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

float add_float(float lhs, float rhs);
float sub_float(float lhs, float rhs);
float mul_float(float lhs, float rhs);
float div_float(float lhs, float rhs);
float neg_float(float value);
double add_double(double lhs, double rhs);
double sub_double(double lhs, double rhs);
double mul_double(double lhs, double rhs);
double div_double(double lhs, double rhs);
double neg_double(double value);
int compare_float(float lhs, float rhs);
int compare_double(double lhs, double rhs);
float signed_int_to_float(int value);
double unsigned_int_to_double(unsigned int value);
float signed_long_long_to_float(long long value);
double unsigned_long_long_to_double(unsigned long long value);
int float_to_signed_int(float value);
unsigned int double_to_unsigned_int(double value);
long long double_to_signed_long_long(double value);
unsigned long long float_to_unsigned_long_long(float value);
double widen_float(float value);
float narrow_double(double value);
double add_promoted_float(int count, ...);

union float_bits {
    float value;
    unsigned int bits;
};

union double_bits {
    double value;
    unsigned long long bits;
};

static float float_from_bits(unsigned int bits)
{
    union float_bits value;
    value.bits = bits;
    return value.value;
}

static unsigned int float_to_bits(float value)
{
    union float_bits result;
    result.value = value;
    return result.bits;
}

static double double_from_bits(unsigned long long bits)
{
    union double_bits value;
    value.bits = bits;
    return value.value;
}

static unsigned long long double_to_bits(double value)
{
    union double_bits result;
    result.value = value;
    return result.bits;
}

int main(void)
{
    float float_nan = float_from_bits(0x7fc12345u);
    double double_nan = double_from_bits(0x7ff8123456789abcull);

    if (float_to_bits(add_float(1.5f, 2.25f)) != 0x40700000u)
        return 1;
    if (float_to_bits(sub_float(5.5f, 1.25f)) != 0x40880000u)
        return 2;
    if (float_to_bits(mul_float(-2.0f, 0.5f)) != 0xbf800000u)
        return 3;
    if (float_to_bits(div_float(1.0f, 0.0f)) != 0x7f800000u)
        return 4;
    if (float_to_bits(neg_float(0.0f)) != 0x80000000u)
        return 5;
    if (double_to_bits(add_double(1.5, 2.25)) != 0x400e000000000000ull)
        return 6;
    if (double_to_bits(sub_double(5.5, 1.25)) != 0x4011000000000000ull)
        return 7;
    if (double_to_bits(mul_double(-2.0, 0.5)) != 0xbff0000000000000ull)
        return 8;
    if (double_to_bits(div_double(1.0, 0.0)) != 0x7ff0000000000000ull)
        return 9;
    if (double_to_bits(neg_double(0.0)) != 0x8000000000000000ull)
        return 10;
    if (compare_float(1.0f, 2.0f) != 14 || compare_double(1.0, 2.0) != 14)
        return 11;
    if (compare_float(2.0f, 1.0f) != 50 || compare_double(2.0, 1.0) != 50)
        return 12;
    if (compare_float(1.0f, 1.0f) != 41 || compare_double(1.0, 1.0) != 41)
        return 13;
    if (compare_float(float_nan, 1.0f) != 2 || compare_double(double_nan, 1.0) != 2)
        return 14;
    if (float_to_bits(signed_int_to_float(-16777216)) != 0xcb800000u)
        return 15;
    if (double_to_bits(unsigned_int_to_double(0xffffffffu)) !=
        0x41efffffffe00000ull)
        return 16;
    if (float_to_bits(signed_long_long_to_float(-9223372036854775807ll - 1)) !=
        0xdf000000u)
        return 17;
    if (double_to_bits(unsigned_long_long_to_double(0xffffffffffffffffull)) !=
        0x43f0000000000000ull)
        return 18;
    if (float_to_signed_int(-3.75f) != -3)
        return 19;
    if (double_to_unsigned_int(1234.75) != 1234u)
        return 20;
    if (double_to_signed_long_long(1099511627776.0) != 1099511627776ll)
        return 21;
    if (float_to_unsigned_long_long(65536.0f) != 65536ull)
        return 22;
    if (double_to_bits(widen_float(-0.0f)) != 0x8000000000000000ull)
        return 23;
    if (float_to_bits(narrow_double(1.5)) != 0x3fc00000u)
        return 24;
    if (double_to_bits(add_promoted_float(2, 1.25f, 2.5f)) !=
        0x400e000000000000ull)
        return 25;
    return 42;
}
