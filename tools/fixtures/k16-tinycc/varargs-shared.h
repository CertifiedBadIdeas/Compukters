/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef K16_TINYCC_VARARGS_SHARED_H
#define K16_TINYCC_VARARGS_SHARED_H

struct k16_varargs_small {
    int first;
    int second;
};

struct k16_varargs_large {
    unsigned int first;
    unsigned long long wide;
    unsigned int last;
};

typedef int (*k16_varargs_fn)(int, int, int, int, ...);

int k16_verify_one_fixed(int fixed, ...);
int k16_verify_three_fixed(int first, int second, int third, ...);
int k16_verify_complete(int first, int second, int third, int fourth, ...);

#endif
