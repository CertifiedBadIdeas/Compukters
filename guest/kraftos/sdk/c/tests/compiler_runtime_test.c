/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <limits.h>
#include <stdint.h>

float __addsf3(float lhs, float rhs);
float __subsf3(float lhs, float rhs);
float __mulsf3(float lhs, float rhs);
float __divsf3(float lhs, float rhs);
float __negsf2(float value);
double __adddf3(double lhs, double rhs);
double __muldf3(double lhs, double rhs);
double __divdf3(double lhs, double rhs);
double __negdf2(double value);
int __eqdf2(double lhs, double rhs);
int __ledf2(double lhs, double rhs);
int __gedf2(double lhs, double rhs);
int __unorddf2(double lhs, double rhs);
double __extendsfdf2(float value);
float __truncdfsf2(double value);
float __floatsisf(int value);
float __floatunsisf(unsigned int value);
double __floatsidf(int value);
double __floatundidf(uint64_t value);
double __floatdidf(int64_t value);
int __fixdfsi(double value);
unsigned int __fixunsdfsi(double value);
int64_t __fixdfdi(double value);
uint64_t __fixunsdfdi(double value);
int64_t __ashldi3(int64_t value, int shift);
int64_t __ashrdi3(int64_t value, int shift);
uint64_t __lshrdi3(uint64_t value, int shift);
int64_t __muldi3(int64_t lhs, int64_t rhs);
int64_t __divdi3(int64_t lhs, int64_t rhs);
int64_t __moddi3(int64_t lhs, int64_t rhs);
uint64_t __udivdi3(uint64_t lhs, uint64_t rhs);
uint64_t __umoddi3(uint64_t lhs, uint64_t rhs);

union float_bits {
  float value;
  uint32_t bits;
};

union double_bits {
  double value;
  uint64_t bits;
};

static float float_from_bits(uint32_t bits) {
  union float_bits value;
  value.bits = bits;
  return value.value;
}

static uint32_t float_to_bits(float value) {
  union float_bits result;
  result.value = value;
  return result.bits;
}

static double double_from_bits(uint64_t bits) {
  union double_bits value;
  value.bits = bits;
  return value.value;
}

static uint64_t double_to_bits(double value) {
  union double_bits result;
  result.value = value;
  return result.bits;
}

int main(void) {
  float positive_zero = float_from_bits(UINT32_C(0x00000000));
  float negative_zero = float_from_bits(UINT32_C(0x80000000));
  float float_nan = float_from_bits(UINT32_C(0x7fc12345));
  double double_positive_zero = double_from_bits(UINT64_C(0x0000000000000000));
  double double_nan = double_from_bits(UINT64_C(0x7ff8123456789abc));

  if (float_to_bits(__addsf3(1.5f, 2.25f)) != UINT32_C(0x40700000)) return 1;
  if (float_to_bits(__subsf3(1.0f, 1.0f)) != UINT32_C(0x00000000)) return 2;
  if (float_to_bits(__mulsf3(-2.0f, 0.5f)) != UINT32_C(0xbf800000)) return 3;
  if (float_to_bits(__negsf2(positive_zero)) != UINT32_C(0x80000000)) return 4;
  if (float_to_bits(__addsf3(negative_zero, negative_zero)) != UINT32_C(0x80000000)) return 5;
  if (float_to_bits(__divsf3(1.0f, positive_zero)) != UINT32_C(0x7f800000)) return 6;
  if (float_to_bits(__divsf3(positive_zero, positive_zero)) != UINT32_C(0x7fc00000)) return 7;
  if (float_to_bits(__addsf3(float_nan, 1.0f)) != UINT32_C(0x7fc12345)) return 8;

  if (double_to_bits(__adddf3(1.5, 2.25)) != UINT64_C(0x400e000000000000)) return 9;
  if (double_to_bits(__muldf3(-2.0, 0.5)) != UINT64_C(0xbff0000000000000)) return 10;
  if (double_to_bits(__negdf2(double_positive_zero)) != UINT64_C(0x8000000000000000)) return 11;
  if (double_to_bits(__divdf3(1.0, double_positive_zero)) != UINT64_C(0x7ff0000000000000)) return 12;
  if (double_to_bits(__divdf3(double_positive_zero, double_positive_zero)) !=
      UINT64_C(0x7ff8000000000000)) return 13;
  if (double_to_bits(__adddf3(double_nan, 1.0)) != UINT64_C(0x7ff8123456789abc)) return 14;
  if (__eqdf2(-0.0, 0.0) != 0) return 15;
  if (__ledf2(1.0, 2.0) != -1 || __gedf2(2.0, 1.0) != 1) return 16;
  if (__unorddf2(double_nan, 1.0) != 1) return 17;
  if (__ledf2(double_nan, 1.0) != 1 || __gedf2(double_nan, 1.0) != -1) return 18;

  if (double_to_bits(__extendsfdf2(negative_zero)) != UINT64_C(0x8000000000000000)) return 19;
  if (float_to_bits(__truncdfsf2(1.5)) != UINT32_C(0x3fc00000)) return 20;
  if (float_to_bits(__floatsisf(-16777216)) != UINT32_C(0xcb800000)) return 21;
  if (float_to_bits(__floatunsisf(UINT_MAX)) != UINT32_C(0x4f800000)) return 22;
  if (double_to_bits(__floatsidf(INT_MIN)) != UINT64_C(0xc1e0000000000000)) return 23;
  if (double_to_bits(__floatdidf(INT64_MIN)) != UINT64_C(0xc3e0000000000000)) return 24;
  if (double_to_bits(__floatundidf(UINT64_MAX)) != UINT64_C(0x43f0000000000000)) return 25;
  if (__fixdfsi(2147483647.0) != INT_MAX) return 26;
  if (__fixunsdfsi(4294967295.0) != UINT_MAX) return 27;
  if (__fixdfdi(double_from_bits(UINT64_C(0xc3e0000000000000))) != INT64_MIN) return 28;
  if (__fixunsdfdi(double_from_bits(UINT64_C(0x43efffffffffffff))) !=
      UINT64_C(0xfffffffffffff800)) return 29;

  if ((uint64_t)__ashldi3((int64_t)UINT64_C(0x0123456789abcdef), 12) !=
      UINT64_C(0x3456789abcdef000)) return 30;
  if ((uint64_t)__ashrdi3(INT64_MIN, 4) != UINT64_C(0xf800000000000000)) return 31;
  if (__lshrdi3(UINT64_C(0xf000000000000000), 4) != UINT64_C(0x0f00000000000000)) return 32;
  if ((uint64_t)__muldi3(INT64_C(0x12345678), INT64_C(0x100000001)) !=
      UINT64_C(0x1234567812345678)) return 33;
  if (__divdi3(INT64_C(-10000000000), INT64_C(4)) != INT64_C(-2500000000)) return 34;
  if (__moddi3(INT64_C(-10000000001), INT64_C(4)) != INT64_C(-1)) return 35;
  if (__udivdi3(UINT64_C(0xf000000000000000), UINT64_C(0x10)) !=
      UINT64_C(0x0f00000000000000)) return 36;
  if (__umoddi3(UINT64_C(0xfedcba9876543210), UINT64_C(0x1000)) != UINT64_C(0x210)) return 37;
  return 0;
}
