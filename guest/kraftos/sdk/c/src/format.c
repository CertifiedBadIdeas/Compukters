#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

struct format_output {
  char *buffer;
  size_t capacity;
  size_t length;
};

static void output_character(struct format_output *output, char character) {
  if (output->capacity != 0 && output->length + 1 < output->capacity) {
    output->buffer[output->length] = character;
  }
  output->length += 1;
}

static void output_repeat(struct format_output *output, char character, int count) {
  while (count-- > 0) output_character(output, character);
}

static void output_text(struct format_output *output, const char *text, size_t length) {
  for (size_t index = 0; index < length; index += 1) output_character(output, text[index]);
}

static int decimal_digit(char character) {
  return character >= '0' && character <= '9';
}

static size_t unsigned_digits(
    char *reverse,
    unsigned long long value,
    unsigned int base,
    int uppercase
) {
  const char *alphabet = uppercase ? "0123456789ABCDEF" : "0123456789abcdef";
  size_t length = 0;
  do {
    reverse[length++] = alphabet[value % base];
    value /= base;
  } while (value != 0);
  return length;
}

static void format_integer(
    struct format_output *output,
    unsigned long long value,
    int negative,
    unsigned int base,
    int uppercase,
    int alternate,
    int plus,
    int space,
    int left,
    int zero,
    int width,
    int precision,
    int pointer
) {
  char reverse[32];
  char sign = negative ? '-' : plus ? '+' : space ? ' ' : 0;
  char prefix_second = uppercase ? 'X' : 'x';
  int prefix_length = (pointer || (alternate && base == 16 && value != 0)) ? 2 : 0;
  size_t digit_count = precision == 0 && value == 0 ? 0 : unsigned_digits(reverse, value, base, uppercase);
  int zero_count = precision > (int)digit_count ? precision - (int)digit_count : 0;
  int content = (sign != 0) + prefix_length + zero_count + (int)digit_count;
  int padding = width > content ? width - content : 0;

  if (zero && !left && precision < 0) {
    zero_count += padding;
    padding = 0;
  }
  if (!left) output_repeat(output, ' ', padding);
  if (sign != 0) output_character(output, sign);
  if (prefix_length != 0) {
    output_character(output, '0');
    output_character(output, prefix_second);
  }
  output_repeat(output, '0', zero_count);
  while (digit_count != 0) output_character(output, reverse[--digit_count]);
  if (left) output_repeat(output, ' ', padding);
}

static size_t append_unsigned(char *buffer, size_t length, unsigned long long value) {
  char reverse[32];
  size_t digits = unsigned_digits(reverse, value, 10, 0);
  while (digits != 0) buffer[length++] = reverse[--digits];
  return length;
}

static size_t format_fixed(char *buffer, double value, int precision, int alternate) {
  unsigned long long whole;
  unsigned long long scale = 1;
  unsigned long long fraction;
  double scaled_fraction;
  double remainder;
  size_t length = 0;

  if (precision < 0) precision = 6;
  if (precision > 18) precision = 18;
  for (int index = 0; index < precision; index += 1) scale *= 10;
  whole = (unsigned long long)value;
  scaled_fraction = (value - (double)whole) * (double)scale;
  fraction = (unsigned long long)scaled_fraction;
  remainder = scaled_fraction - (double)fraction;
  if (remainder > 0.5 ||
      (remainder == 0.5 && ((precision == 0 ? whole : fraction) & 1u) != 0)) {
    fraction += 1;
  }
  if (fraction == scale) {
    whole += 1;
    fraction = 0;
  }
  length = append_unsigned(buffer, length, whole);
  if (precision != 0 || alternate) buffer[length++] = '.';
  if (precision != 0) {
    unsigned long long divisor = scale / 10;
    while (divisor != 0) {
      buffer[length++] = (char)('0' + (fraction / divisor) % 10);
      divisor /= 10;
    }
  }
  return length;
}

static size_t format_scientific(
    char *buffer,
    double value,
    int precision,
    int alternate,
    int uppercase
) {
  int exponent = 0;
  size_t length;
  if (value != 0.0) {
    while (value >= 10.0) {
      value /= 10.0;
      exponent += 1;
    }
    while (value < 1.0) {
      value *= 10.0;
      exponent -= 1;
    }
  }
  length = format_fixed(buffer, value, precision, alternate);
  buffer[length++] = uppercase ? 'E' : 'e';
  buffer[length++] = exponent < 0 ? '-' : '+';
  if (exponent < 0) exponent = -exponent;
  if (exponent < 10) buffer[length++] = '0';
  return append_unsigned(buffer, length, (unsigned int)exponent);
}

static size_t format_float_value(
    char *buffer,
    double value,
    char conversion,
    int precision,
    int alternate,
    int *negative
) {
  int uppercase = conversion >= 'A' && conversion <= 'Z';
  char lower = uppercase ? (char)(conversion + ('a' - 'A')) : conversion;
  int exponent = 0;
  size_t length;

  *negative = value < 0.0;
  if (*negative) value = -value;
  if (value != value) {
    memcpy(buffer, uppercase ? "NAN" : "nan", 3);
    return 3;
  }
  if (value > 1.7976931348623157e308) {
    memcpy(buffer, uppercase ? "INF" : "inf", 3);
    return 3;
  }
  if (lower == 'f') return format_fixed(buffer, value, precision, alternate);
  if (lower == 'e') return format_scientific(buffer, value, precision < 0 ? 6 : precision, alternate, uppercase);

  if (precision <= 0) precision = 6;
  if (value != 0.0) {
    double normalized = value;
    while (normalized >= 10.0) {
      normalized /= 10.0;
      exponent += 1;
    }
    while (normalized < 1.0) {
      normalized *= 10.0;
      exponent -= 1;
    }
  }
  if (exponent < -4 || exponent >= precision) {
    length = format_scientific(buffer, value, precision - 1, alternate, uppercase);
  } else {
    int fractional_digits = precision - exponent - 1;
    length = format_fixed(buffer, value, fractional_digits, alternate);
  }
  if (!alternate) {
    char exponent_marker = uppercase ? 'E' : 'e';
    size_t exponent_index = length;
    for (size_t index = 0; index < length; index += 1) {
      if (buffer[index] == exponent_marker) {
        exponent_index = index;
        break;
      }
    }
    size_t end = exponent_index;
    while (end != 0 && buffer[end - 1] == '0') end -= 1;
    if (end != 0 && buffer[end - 1] == '.') end -= 1;
    if (exponent_index != length) {
      memmove(buffer + end, buffer + exponent_index, length - exponent_index);
      length = end + length - exponent_index;
    } else {
      length = end;
    }
  }
  return length;
}

static void format_float(
    struct format_output *output,
    double value,
    char conversion,
    int alternate,
    int plus,
    int space,
    int left,
    int zero,
    int width,
    int precision
) {
  char text[96];
  int negative;
  size_t length = format_float_value(text, value, conversion, precision, alternate, &negative);
  char sign = negative ? '-' : plus ? '+' : space ? ' ' : 0;
  int padding = width > (int)length + (sign != 0) ? width - (int)length - (sign != 0) : 0;
  if (!left && !zero) output_repeat(output, ' ', padding);
  if (sign != 0) output_character(output, sign);
  if (!left && zero) output_repeat(output, '0', padding);
  output_text(output, text, length);
  if (left) output_repeat(output, ' ', padding);
}

int vsnprintf(char *buffer, size_t size, const char *format, va_list arguments) {
  struct format_output output = {buffer, size, 0};

  while (*format != 0) {
    if (*format != '%') {
      output_character(&output, *format++);
      continue;
    }
    format += 1;
    if (*format == '%') {
      output_character(&output, *format++);
      continue;
    }

    int alternate = 0;
    int left = 0;
    int plus = 0;
    int space = 0;
    int zero = 0;
    for (;;) {
      if (*format == '#') alternate = 1;
      else if (*format == '-') left = 1;
      else if (*format == '+') plus = 1;
      else if (*format == ' ') space = 1;
      else if (*format == '0') zero = 1;
      else break;
      format += 1;
    }

    int width = 0;
    if (*format == '*') {
      width = va_arg(arguments, int);
      format += 1;
      if (width < 0) {
        left = 1;
        width = -width;
      }
    } else {
      while (decimal_digit(*format)) width = width * 10 + (*format++ - '0');
    }

    int precision = -1;
    if (*format == '.') {
      format += 1;
      precision = 0;
      if (*format == '*') {
        precision = va_arg(arguments, int);
        format += 1;
        if (precision < 0) precision = -1;
      } else {
        while (decimal_digit(*format)) precision = precision * 10 + (*format++ - '0');
      }
    }

    int length = 0;
    if (*format == 'l') {
      length = 1;
      format += 1;
      if (*format == 'l') {
        length = 2;
        format += 1;
      }
    } else if (*format == 'L') {
      length = 3;
      format += 1;
    } else if (*format == 'z') {
      length = 1;
      format += 1;
    } else if (*format == 'h') {
      format += 1;
      if (*format == 'h') format += 1;
    }

    char conversion = *format == 0 ? 0 : *format++;
    if (conversion == 'd' || conversion == 'i') {
      long long signed_value = length == 2 ? va_arg(arguments, long long) : va_arg(arguments, int);
      unsigned long long value = signed_value < 0 ? 0ull - (unsigned long long)signed_value : signed_value;
      format_integer(&output, value, signed_value < 0, 10, 0, 0, plus, space, left, zero, width, precision, 0);
    } else if (conversion == 'u' || conversion == 'o' || conversion == 'x' || conversion == 'X') {
      unsigned long long value = length == 2 ? va_arg(arguments, unsigned long long) : va_arg(arguments, unsigned int);
      unsigned int base = conversion == 'o' ? 8u : conversion == 'u' ? 10u : 16u;
      format_integer(&output, value, 0, base, conversion == 'X', alternate, 0, 0, left, zero, width, precision, 0);
    } else if (conversion == 'p') {
      uintptr_t value = (uintptr_t)va_arg(arguments, void *);
      format_integer(&output, value, 0, 16, 0, 1, 0, 0, left, zero, width, precision < 0 ? 1 : precision, 1);
    } else if (conversion == 'c') {
      char value = (char)va_arg(arguments, int);
      if (!left) output_repeat(&output, ' ', width > 1 ? width - 1 : 0);
      output_character(&output, value);
      if (left) output_repeat(&output, ' ', width > 1 ? width - 1 : 0);
    } else if (conversion == 's') {
      const char *value = va_arg(arguments, const char *);
      size_t text_length;
      if (value == NULL) value = "(null)";
      text_length = precision >= 0 ? strnlen(value, (size_t)precision) : strlen(value);
      if (!left) output_repeat(&output, ' ', width > (int)text_length ? width - (int)text_length : 0);
      output_text(&output, value, text_length);
      if (left) output_repeat(&output, ' ', width > (int)text_length ? width - (int)text_length : 0);
    } else if (conversion == 'f' || conversion == 'F' || conversion == 'e' || conversion == 'E' ||
               conversion == 'g' || conversion == 'G') {
      double value = length == 3 ? (double)va_arg(arguments, long double) : va_arg(arguments, double);
      format_float(&output, value, conversion, alternate, plus, space, left, zero, width, precision);
    } else if (conversion == 'n') {
      int *value = va_arg(arguments, int *);
      if (value != NULL) *value = (int)output.length;
    } else if (conversion != 0) {
      output_character(&output, '%');
      output_character(&output, conversion);
    }
  }
  if (size != 0) buffer[output.length < size ? output.length : size - 1] = 0;
  return (int)output.length;
}

int snprintf(char *buffer, size_t size, const char *format, ...) {
  va_list arguments;
  int result;
  va_start(arguments, format);
  result = vsnprintf(buffer, size, format, arguments);
  va_end(arguments);
  return result;
}

int vsprintf(char *buffer, const char *format, va_list arguments) {
  return vsnprintf(buffer, (size_t)-1, format, arguments);
}

int sprintf(char *buffer, const char *format, ...) {
  va_list arguments;
  int result;
  va_start(arguments, format);
  result = vsprintf(buffer, format, arguments);
  va_end(arguments);
  return result;
}
