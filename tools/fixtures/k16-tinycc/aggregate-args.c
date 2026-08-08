/*
 * Copyright (C) 2026 Compukter Kraft contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

struct pair {
    int first;
    int second;
};

struct large_value {
    int words[5];
};

static int consume_pair(int prefix, struct pair value, int suffix)
{
    int result = prefix + value.first + value.second + suffix;

    value.first = 99;
    value.second = 101;
    return result;
}

static int consume_large(int first, int second, int third,
                         struct large_value value)
{
    int result = first + second + third + value.words[0] + value.words[1] +
                 value.words[2] + value.words[3] + value.words[4];

    value.words[0] = 77;
    value.words[4] = 88;
    return result;
}

int main(void)
{
    struct pair pair = { 4, 5 };
    struct large_value large = { { 4, 5, 6, 7, 8 } };

    if (consume_pair(2, pair, 3) != 14)
        return 1;
    if (pair.first != 4 || pair.second != 5)
        return 2;
    if (consume_large(1, 2, 3, large) != 36)
        return 3;
    if (large.words[0] != 4 || large.words[4] != 8)
        return 4;
    return 42;
}
