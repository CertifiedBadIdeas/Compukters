/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

typedef __builtin_va_list va_list;
#define va_start(ap, last) __builtin_va_start(ap, last)
#define va_arg(ap, type) __builtin_va_arg(ap, type)
#define va_copy(destination, source) __builtin_va_copy(destination, source)
#define va_end(ap) __builtin_va_end(ap)

struct pair {
    int first;
    int second;
};

static int one_fixed(int fixed, ...)
{
    va_list ap;
    int first;
    int second;
    int *pointer;

    va_start(ap, fixed);
    first = va_arg(ap, int);
    second = va_arg(ap, int);
    pointer = va_arg(ap, int *);
    va_end(ap);
    if (fixed != 1)
        return 11;
    if (first != 2)
        return 12;
    if (second != 3)
        return 13;
    if (*pointer != 4)
        return 14;
    return 10;
}

static int three_fixed(int first, int second, int third, ...)
{
    va_list ap;
    int fourth;

    va_start(ap, third);
    fourth = va_arg(ap, int);
    va_end(ap);
    return first + second + third + fourth;
}

static int four_fixed(int first, int second, int third, int fourth, ...)
{
    va_list ap;
    int fifth;

    va_start(ap, fourth);
    fifth = va_arg(ap, int);
    va_end(ap);
    return first + second + third + fourth + fifth;
}

static int aggregate_value(int fixed, ...)
{
    va_list ap;
    int marker;
    struct pair value;

    va_start(ap, fixed);
    marker = va_arg(ap, int);
    value = va_arg(ap, struct pair);
    va_end(ap);
    value.first = value.first + 1;
    return fixed + marker + value.first + value.second;
}

static int copied_cursor(int fixed, ...)
{
    va_list ap;
    va_list copy;
    int first;
    int first_copy;
    int second;
    int second_copy;

    va_start(ap, fixed);
    va_copy(copy, ap);
    first = va_arg(ap, int);
    first_copy = va_arg(copy, int);
    second = va_arg(ap, int);
    second_copy = va_arg(copy, int);
    va_end(copy);
    va_end(ap);
    return fixed + first + first_copy + second + second_copy;
}

int main(void)
{
    int pointed = 4;
    int one_result;
    struct pair pair = { 3, 4 };

    one_result = one_fixed(1, 2, 3, &pointed);
    if (one_result != 10)
        return one_result;
    if (three_fixed(5, 6, 7, 8) != 26)
        return 2;
    if (four_fixed(1, 2, 3, 4, 5) != 15)
        return 3;
    if (aggregate_value(1, 2, pair) != 11)
        return 4;
    if (pair.first != 3 || pair.second != 4)
        return 5;
    if (copied_cursor(2, 3, 4) != 16)
        return 6;
    return 42;
}
