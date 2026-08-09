/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <stdarg.h>
#include <unistd.h>

static int global_bias = 4;
static int global_runs;

static void add_through_pointer(int *value, int amount) {
  *value += amount;
}

static int sum_values(int count, ...) {
  va_list args;
  int sum = 0;
  va_start(args, count);
  for (int index = 0; index < count; index += 1) {
    sum += va_arg(args, int);
  }
  va_end(args);
  return sum;
}

int main(void) {
  static const char message[] = "native tinycc ok\n";
  int local[] = {2, 4, 6};
  float scaled;

  global_bias += 1;
  global_runs += 1;
  add_through_pointer(&local[1], 3);
  scaled = (float)(sum_values(3, local[0], local[1], local[2]) + global_bias) * 1.5f;
  if (global_runs != 1 || (int)scaled != 30) {
    return 1;
  }
  if (write(STDOUT_FILENO, message, sizeof(message) - 1) != (int)(sizeof(message) - 1)) {
    return 2;
  }
  return 0;
}
