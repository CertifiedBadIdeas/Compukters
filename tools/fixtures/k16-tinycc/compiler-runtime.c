/* Freestanding 32-bit division helpers required by the current K16 Clang ABI. */
static unsigned int divide_unsigned(
    unsigned int dividend,
    unsigned int divisor,
    unsigned int *remainder
) {
  unsigned int quotient = 0;
  unsigned int current_remainder = 0;
  int bit;

  if (divisor == 0) {
    *remainder = dividend;
    return 0;
  }
  for (bit = 31; bit >= 0; bit -= 1) {
    current_remainder = (current_remainder << 1) | ((dividend >> bit) & 1u);
    if (current_remainder >= divisor) {
      current_remainder -= divisor;
      quotient |= 1u << bit;
    }
  }
  *remainder = current_remainder;
  return quotient;
}

unsigned int __udivsi3(unsigned int dividend, unsigned int divisor) {
  unsigned int remainder;
  return divide_unsigned(dividend, divisor, &remainder);
}

unsigned int __umodsi3(unsigned int dividend, unsigned int divisor) {
  unsigned int remainder;
  divide_unsigned(dividend, divisor, &remainder);
  return remainder;
}

int __divsi3(int dividend, int divisor) {
  int negative = (dividend < 0) != (divisor < 0);
  unsigned int unsigned_dividend = dividend < 0 ? (unsigned int)-dividend : (unsigned int)dividend;
  unsigned int unsigned_divisor = divisor < 0 ? (unsigned int)-divisor : (unsigned int)divisor;
  unsigned int remainder;
  unsigned int quotient = divide_unsigned(unsigned_dividend, unsigned_divisor, &remainder);
  return negative ? -(int)quotient : (int)quotient;
}

int __modsi3(int dividend, int divisor) {
  unsigned int unsigned_dividend = dividend < 0 ? (unsigned int)-dividend : (unsigned int)dividend;
  unsigned int unsigned_divisor = divisor < 0 ? (unsigned int)-divisor : (unsigned int)divisor;
  unsigned int remainder;
  divide_unsigned(unsigned_dividend, unsigned_divisor, &remainder);
  return dividend < 0 ? -(int)remainder : (int)remainder;
}
