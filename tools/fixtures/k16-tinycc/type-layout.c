/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

struct mixed_wide {
    char prefix;
    long long integer;
    double real;
    char suffix;
};

struct mixed_long_double {
    char prefix;
    long double real;
    char suffix;
};

_Static_assert(sizeof(long long) == 8, "K16 long long size");
_Static_assert(_Alignof(long long) == 8, "K16 long long alignment");
_Static_assert(sizeof(double) == 8, "K16 double size");
_Static_assert(_Alignof(double) == 8, "K16 double alignment");
_Static_assert(sizeof(long double) == 8, "K16 long double size");
_Static_assert(_Alignof(long double) == 8, "K16 long double alignment");

_Static_assert(sizeof(struct mixed_wide) == 32, "K16 mixed wide size");
_Static_assert(_Alignof(struct mixed_wide) == 8,
               "K16 mixed wide alignment");
_Static_assert(__builtin_offsetof(struct mixed_wide, integer) == 8,
               "K16 mixed wide integer offset");
_Static_assert(__builtin_offsetof(struct mixed_wide, real) == 16,
               "K16 mixed wide real offset");
_Static_assert(__builtin_offsetof(struct mixed_wide, suffix) == 24,
               "K16 mixed wide suffix offset");

_Static_assert(sizeof(struct mixed_long_double) == 24,
               "K16 mixed long double size");
_Static_assert(_Alignof(struct mixed_long_double) == 8,
               "K16 mixed long double alignment");
_Static_assert(__builtin_offsetof(struct mixed_long_double, real) == 8,
               "K16 mixed long double real offset");
_Static_assert(__builtin_offsetof(struct mixed_long_double, suffix) == 16,
               "K16 mixed long double suffix offset");

int type_layout_probe(void)
{
    return 0;
}
