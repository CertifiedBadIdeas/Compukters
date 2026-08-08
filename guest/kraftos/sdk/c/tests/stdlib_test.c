#include <errno.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

static int compare_ints(const void *left, const void *right) {
  int lhs = *(const int *)left;
  int rhs = *(const int *)right;
  return (lhs > rhs) - (lhs < rhs);
}

union double_bits {
  double value;
  uint64_t bits;
};

int main(void) {
  char *end;
  char compensated_exponent[4107];
  char long_fraction[403];
  double parsed;
  union double_bits floating;
  int values[] = {7, -2, 7, 0, 4};
  int key = 4;
  unsigned int index;

  if (strtol("  -0x2a!", &end, 0) != -42 || *end != '!') return 90;
  if (strtoul("077x", &end, 0) != 63 || *end != 'x') return 91;
  if (strtoull("18446744073709551615", &end, 10) != UINT64_MAX || *end != 0) return 92;
  errno = 0;
  if (strtoull("18446744073709551616", &end, 10) != UINT64_MAX || errno != ERANGE) return 93;
  if (strtod("-12.5e2x", &end) != -1250.0 || *end != 'x') return 94;
  if (strtof(".25!", &end) != 0.25f || *end != '!') return 95;
  if (strtold("1e-3?", &end) != 0.001L || *end != '?') return 96;

  errno = 0;
  parsed = strtod("0e999", &end);
  if (parsed != 0.0 || parsed != parsed || *end != 0 || errno != 0) return 102;
  long_fraction[0] = '0';
  long_fraction[1] = '.';
  for (index = 2; index < 402; index += 1) long_fraction[index] = '1';
  long_fraction[402] = 0;
  errno = 0;
  parsed = strtod(long_fraction, &end);
  if (parsed != parsed || parsed <= 0.1 || parsed >= 0.2 ||
      *end != 0 || errno != 0) return 103;
  errno = 0;
  floating.value = strtod("1e999", &end);
  if (floating.bits != UINT64_C(0x7ff0000000000000) ||
      *end != 0 || errno != ERANGE) return 104;
  errno = 0;
  floating.value = strtod("1e-999", &end);
  if (floating.bits != 0 || *end != 0 || errno != ERANGE) return 105;
  compensated_exponent[0] = '1';
  for (index = 1; index < 4100; index += 1) compensated_exponent[index] = '0';
  compensated_exponent[4100] = 'e';
  compensated_exponent[4101] = '-';
  compensated_exponent[4102] = '4';
  compensated_exponent[4103] = '0';
  compensated_exponent[4104] = '9';
  compensated_exponent[4105] = '9';
  compensated_exponent[4106] = 0;
  errno = 0;
  floating.value = strtod(compensated_exponent, &end);
  if (floating.value != 1.0 || *end != 0 || errno != 0) return 127;
  errno = 0;
  floating.value = strtod("1e-308", &end);
  if ((floating.bits & UINT64_C(0x7fffffffffffffff)) == 0 ||
      (floating.bits & UINT64_C(0x7ff0000000000000)) != 0 ||
      *end != 0 || errno != ERANGE) return 128;

  qsort(values, 5, sizeof(values[0]), compare_ints);
  if (values[0] != -2 || values[1] != 0 || values[2] != 4 ||
      values[3] != 7 || values[4] != 7) return 97;
  if (bsearch(&key, values, 5, sizeof(values[0]), compare_ints) != &values[2]) return 98;
  if (getenv("PATH") != NULL) return 99;
  if (abs(-7) != 7 || labs(-9) != 9 || llabs(-11) != 11) return 100;
  if (ldexp(1.5, 3) != 12.0 || ldexpl(8.0L, -2) != 2.0L) return 101;
  floating.value = ldexp(0.0, 1024);
  if (floating.bits != 0) return 106;
  floating.value = ldexp(-0.0, 1024);
  if (floating.bits != UINT64_C(0x8000000000000000)) return 107;
  floating.value = ldexp(1.0, -1074);
  if (floating.bits != 1) return 108;
  floating.value = ldexp(1.0, -1075);
  if (floating.bits != 0) return 109;
  floating.bits = UINT64_C(0x7ff0000000000000);
  floating.value = ldexp(floating.value, -5000);
  if (floating.bits != UINT64_C(0x7ff0000000000000)) return 125;
  floating.bits = UINT64_C(0x7ff8000000000001);
  floating.value = ldexp(floating.value, 5000);
  if (floating.bits != UINT64_C(0x7ff8000000000001)) return 126;
  return 0;
}
