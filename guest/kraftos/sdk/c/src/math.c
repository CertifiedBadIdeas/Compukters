#include <math.h>
#include <stdint.h>

union double_shape {
  double value;
  uint64_t bits;
};

double ldexp(double value, int exponent) {
  union double_shape scale;
  union double_shape input;

  input.value = value;
  if ((input.bits & UINT64_C(0x7fffffffffffffff)) == 0 ||
      (input.bits & UINT64_C(0x7ff0000000000000)) ==
          UINT64_C(0x7ff0000000000000)) {
    return value;
  }
  if (exponent > 1023) {
    scale.value = 0x1p1023;
    value *= scale.value;
    exponent -= 1023;
    if (exponent > 1023) {
      value *= scale.value;
      exponent -= 1023;
      if (exponent > 1023) exponent = 1023;
    }
  } else if (exponent < -1022) {
    scale.value = 0x1p-969;
    value *= scale.value;
    exponent += 969;
    if (exponent < -1022) {
      value *= scale.value;
      exponent += 969;
      if (exponent < -1022) exponent = -1022;
    }
  }
  scale.bits = (uint64_t)(exponent + 1023) << 52;
  return value * scale.value;
}

long double ldexpl(long double value, int exponent) {
  return (long double)ldexp((double)value, exponent);
}
