#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <kraft/fs.h>
#include <kraft/syscalls.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

struct integer_parse {
  unsigned long long value;
  const char *end;
  int negative;
  int any;
  int overflow;
};

static int digit_value(int character) {
  if (character >= '0' && character <= '9') return character - '0';
  if (character >= 'a' && character <= 'z') return character - 'a' + 10;
  if (character >= 'A' && character <= 'Z') return character - 'A' + 10;
  return -1;
}

static struct integer_parse parse_integer(
    const char *text,
    int base,
    unsigned long long limit
) {
  struct integer_parse result = {0, text, 0, 0, 0};
  const char *cursor = text;
  unsigned long long cutoff;
  unsigned int remainder;

  while (isspace((unsigned char)*cursor)) cursor += 1;
  if (*cursor == '+' || *cursor == '-') {
    result.negative = *cursor == '-';
    cursor += 1;
  }
  if (base != 0 && (base < 2 || base > 36)) {
    errno = EINVAL;
    return result;
  }
  if ((base == 0 || base == 16) && cursor[0] == '0' &&
      (cursor[1] == 'x' || cursor[1] == 'X') &&
      digit_value((unsigned char)cursor[2]) >= 0 &&
      digit_value((unsigned char)cursor[2]) < 16) {
    base = 16;
    cursor += 2;
  } else if (base == 0) {
    base = cursor[0] == '0' ? 8 : 10;
  }
  cutoff = limit / (unsigned int)base;
  remainder = (unsigned int)(limit % (unsigned int)base);
  while (1) {
    int digit = digit_value((unsigned char)*cursor);
    if (digit < 0 || digit >= base) break;
    result.any = 1;
    if (result.value > cutoff ||
        (result.value == cutoff && (unsigned int)digit > remainder)) {
      result.overflow = 1;
    } else if (!result.overflow) {
      result.value = result.value * (unsigned int)base + (unsigned int)digit;
    }
    cursor += 1;
  }
  if (result.any) result.end = cursor;
  if (result.overflow) result.value = limit;
  return result;
}

unsigned long long strtoull(const char *text, char **end, int base) {
  struct integer_parse parsed = parse_integer(text, base, ULLONG_MAX);
  if (end != NULL) *end = (char *)parsed.end;
  if (parsed.overflow) errno = ERANGE;
  if (parsed.negative && !parsed.overflow) return 0u - parsed.value;
  return parsed.value;
}

long long strtoll(const char *text, char **end, int base) {
  const char *cursor = text;
  unsigned long long limit;
  struct integer_parse parsed;
  while (isspace((unsigned char)*cursor)) cursor += 1;
  limit = *cursor == '-' ? (unsigned long long)LLONG_MAX + 1u : LLONG_MAX;
  parsed = parse_integer(text, base, limit);
  if (end != NULL) *end = (char *)parsed.end;
  if (parsed.overflow) {
    errno = ERANGE;
    return parsed.negative ? LLONG_MIN : LLONG_MAX;
  }
  if (parsed.negative) {
    if (parsed.value == (unsigned long long)LLONG_MAX + 1u) return LLONG_MIN;
    return -(long long)parsed.value;
  }
  return (long long)parsed.value;
}

unsigned long strtoul(const char *text, char **end, int base) {
  struct integer_parse parsed = parse_integer(text, base, ULONG_MAX);
  if (end != NULL) *end = (char *)parsed.end;
  if (parsed.overflow) errno = ERANGE;
  if (parsed.negative && !parsed.overflow) return (unsigned long)(0u - parsed.value);
  return (unsigned long)parsed.value;
}

long strtol(const char *text, char **end, int base) {
  const char *cursor = text;
  unsigned long long limit;
  struct integer_parse parsed;
  while (isspace((unsigned char)*cursor)) cursor += 1;
  limit = *cursor == '-' ? (unsigned long long)LONG_MAX + 1u : LONG_MAX;
  parsed = parse_integer(text, base, limit);
  if (end != NULL) *end = (char *)parsed.end;
  if (parsed.overflow) {
    errno = ERANGE;
    return parsed.negative ? LONG_MIN : LONG_MAX;
  }
  if (parsed.negative) {
    if (parsed.value == (unsigned long long)LONG_MAX + 1u) return LONG_MIN;
    return -(long)parsed.value;
  }
  return (long)parsed.value;
}

int atoi(const char *text) { return (int)strtol(text, NULL, 10); }
long atol(const char *text) { return strtol(text, NULL, 10); }
long long atoll(const char *text) { return strtoll(text, NULL, 10); }

#define MAX_DECIMAL_SIGNIFICAND_DIGITS 19u
#define MAX_DECIMAL_EXPONENT 4096

union decimal_double_shape {
  double value;
  uint64_t bits;
};

static int64_t combine_decimal_exponent(
    int64_t exponent,
    uint64_t magnitude,
    int negative
) {
  int64_t delta;
  if (magnitude > (uint64_t)INT64_MAX) {
    return negative ? INT64_MIN : INT64_MAX;
  }
  delta = (int64_t)magnitude;
  if (negative) {
    return exponent < INT64_MIN + delta ? INT64_MIN : exponent - delta;
  }
  return exponent > INT64_MAX - delta ? INT64_MAX : exponent + delta;
}

static double scale_decimal(double value, int exponent) {
  static const double powers[] = {
      1.0e1, 1.0e2, 1.0e4, 1.0e8,
      1.0e16, 1.0e32, 1.0e64, 1.0e128,
  };
  unsigned int magnitude =
      exponent < 0 ? 0u - (unsigned int)exponent : (unsigned int)exponent;
  unsigned int bit = 0;
  while (magnitude >= 256u && value != 0.0 &&
         value <= 1.7976931348623157e308) {
    value = exponent < 0 ? value / 1.0e256 : value * 1.0e256;
    magnitude -= 256u;
  }
  while (magnitude != 0 && value != 0.0 &&
         value <= 1.7976931348623157e308) {
    if ((magnitude & 1u) != 0) {
      value = exponent < 0 ? value / powers[bit] : value * powers[bit];
    }
    magnitude >>= 1;
    bit += 1;
  }
  return value;
}

double strtod(const char *text, char **end) {
  const char *cursor = text;
  uint64_t significand = 0;
  unsigned int kept_digits = 0;
  uint64_t parsed_exponent = 0;
  int64_t decimal_exponent = 0;
  int exponent_negative = 0;
  int negative = 0;
  int any = 0;
  int significant = 0;
  double value;

  while (isspace((unsigned char)*cursor)) cursor += 1;
  if (*cursor == '+' || *cursor == '-') {
    negative = *cursor == '-';
    cursor += 1;
  }
  while (isdigit((unsigned char)*cursor)) {
    unsigned int digit = (unsigned int)(*cursor - '0');
    any = 1;
    if (significant || digit != 0) {
      significant = 1;
      if (kept_digits < MAX_DECIMAL_SIGNIFICAND_DIGITS) {
        significand = significand * 10u + digit;
        kept_digits += 1;
      } else {
        decimal_exponent += 1;
      }
    }
    cursor += 1;
  }
  if (*cursor == '.') {
    cursor += 1;
    while (isdigit((unsigned char)*cursor)) {
      unsigned int digit = (unsigned int)(*cursor - '0');
      any = 1;
      if (significant || digit != 0) {
        significant = 1;
        if (kept_digits < MAX_DECIMAL_SIGNIFICAND_DIGITS) {
          significand = significand * 10u + digit;
          kept_digits += 1;
          decimal_exponent -= 1;
        }
      } else {
        decimal_exponent -= 1;
      }
      cursor += 1;
    }
  }
  if (!any) {
    if (end != NULL) *end = (char *)text;
    return 0.0;
  }
  if (*cursor == 'e' || *cursor == 'E') {
    const char *exponent_mark = cursor;
    const char *digits;
    cursor += 1;
    if (*cursor == '+' || *cursor == '-') {
      exponent_negative = *cursor == '-';
      cursor += 1;
    }
    digits = cursor;
    while (isdigit((unsigned char)*cursor)) {
      unsigned int digit = (unsigned int)(*cursor - '0');
      if (parsed_exponent > (UINT64_MAX - digit) / 10u) {
        parsed_exponent = UINT64_MAX;
      } else {
        parsed_exponent = parsed_exponent * 10u + digit;
      }
      cursor += 1;
    }
    if (cursor == digits) {
      cursor = exponent_mark;
      parsed_exponent = 0;
    } else {
      decimal_exponent = combine_decimal_exponent(
          decimal_exponent,
          parsed_exponent,
          exponent_negative
      );
    }
  }
  if (end != NULL) *end = (char *)cursor;
  if (significand == 0) return negative ? -0.0 : 0.0;
  {
    int scale_exponent = decimal_exponent > MAX_DECIMAL_EXPONENT
        ? MAX_DECIMAL_EXPONENT
        : decimal_exponent < -MAX_DECIMAL_EXPONENT
            ? -MAX_DECIMAL_EXPONENT
            : (int)decimal_exponent;
    union decimal_double_shape scaled;
    scaled.value = scale_decimal((double)significand, scale_exponent);
    if ((scaled.bits & UINT64_C(0x7ff0000000000000)) == 0 ||
        (scaled.bits & UINT64_C(0x7ff0000000000000)) ==
            UINT64_C(0x7ff0000000000000)) {
      errno = ERANGE;
    }
    value = scaled.value;
  }
  return negative ? -value : value;
}

float strtof(const char *text, char **end) { return (float)strtod(text, end); }
long double strtold(const char *text, char **end) { return (long double)strtod(text, end); }

static void swap_elements(unsigned char *left, unsigned char *right, size_t size) {
  while (size-- != 0) {
    unsigned char byte = *left;
    *left++ = *right;
    *right++ = byte;
  }
}

static void sift_down(
    unsigned char *base,
    size_t start,
    size_t count,
    size_t size,
    int (*compare)(const void *, const void *)
) {
  size_t root = start;
  if (count < 2) return;
  while (root <= (count - 2) / 2) {
    size_t child = root * 2 + 1;
    if (child + 1 < count &&
        compare(base + child * size, base + (child + 1) * size) < 0) {
      child += 1;
    }
    if (compare(base + root * size, base + child * size) >= 0) return;
    swap_elements(base + root * size, base + child * size, size);
    root = child;
  }
}

void qsort(
    void *memory,
    size_t count,
    size_t size,
    int (*compare)(const void *, const void *)
) {
  unsigned char *base = memory;
  size_t start;
  size_t end;
  if (count < 2 || size == 0 || count > UINT32_MAX / size) return;
  start = (count - 2) / 2 + 1;
  while (start != 0) {
    start -= 1;
    sift_down(base, start, count, size, compare);
  }
  end = count - 1;
  while (end != 0) {
    swap_elements(base, base + end * size, size);
    sift_down(base, 0, end, size, compare);
    end -= 1;
  }
}

void *bsearch(
    const void *key,
    const void *memory,
    size_t count,
    size_t size,
    int (*compare)(const void *, const void *)
) {
  const unsigned char *base = memory;
  size_t left = 0;
  size_t right = count;
  while (left < right) {
    size_t middle = left + (right - left) / 2;
    int order = compare(key, base + middle * size);
    if (order < 0) right = middle;
    else if (order > 0) left = middle + 1;
    else return (void *)(base + middle * size);
  }
  return NULL;
}

int abs(int value) { return value < 0 ? -value : value; }
long labs(long value) { return value < 0 ? -value : value; }
long long llabs(long long value) { return value < 0 ? -value : value; }

char *getenv(const char *name) {
  (void)name;
  return NULL;
}

char *realpath(const char *path, char *resolved_path) {
  char canonical[KRAFT_MAX_PATH_BYTES + 1u];
  unsigned int metadata[4];
  size_t input = 0;
  size_t output = 1;
  int status;
  char *result;

  if (path == NULL || path[0] == 0) {
    errno = EINVAL;
    return NULL;
  }
  canonical[0] = '/';
  while (path[input] != 0) {
    size_t component_start;
    size_t component_size;
    while (path[input] == '/') input += 1;
    if (path[input] == 0) break;
    component_start = input;
    while (path[input] != 0 && path[input] != '/') input += 1;
    component_size = input - component_start;
    if (component_size == 1 && path[component_start] == '.') continue;
    if (component_size == 2 && path[component_start] == '.' &&
        path[component_start + 1] == '.') {
      while (output > 1 && canonical[output - 1] != '/') output -= 1;
      if (output > 1) output -= 1;
      continue;
    }
    {
      size_t separator = output > 1 ? 1u : 0u;
      if (separator > KRAFT_MAX_PATH_BYTES - output ||
          component_size > KRAFT_MAX_PATH_BYTES - output - separator) {
        errno = EINVAL;
        return NULL;
      }
    }
    if (output > 1) canonical[output++] = '/';
    memcpy(canonical + output, path + component_start, component_size);
    output += component_size;
  }
  canonical[output] = 0;

  status = __kraft_sys_stat(canonical, (unsigned int)output, metadata);
  if (status < 0) {
    errno = -status;
    return NULL;
  }
  result = resolved_path;
  if (result == NULL) {
    result = malloc(output + 1);
    if (result == NULL) return NULL;
  }
  memcpy(result, canonical, output + 1);
  return result;
}
