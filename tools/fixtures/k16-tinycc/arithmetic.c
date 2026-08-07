/* K16 TinyCC scalar arithmetic ABI fixture. */
int main(void) {
  volatile unsigned int source_a = 11u;
  volatile unsigned int source_b = 5u;
  volatile unsigned int source_six = 6u;
  volatile unsigned int source_high = 0x80000003u;
  volatile int source_negative = -32;
  volatile int source_negative_fifty_five = -55;
  unsigned int a = source_a;
  unsigned int b = source_b;
  int negative = source_negative;
  if (a + b != 16u || a - b != 6u || a * b != 55u) return 1;
  if ((a & b) != 1u || (a | b) != 15u || (a ^ b) != 14u) return 2;
  if ((b << 3) != 40u || (0x80000000u >> 31) != 1u) return 3;
  if ((negative >> 3) != -4) return 4;
  if (!(a == 11u) || !(a != b) || !(negative < 0) || !(a > b)) return 5;
  if ((a * b) / b != a || (a * b) % source_six != 1u) return 6;
  if (source_negative_fifty_five / (int)b != -11 || source_negative_fifty_five % (int)source_six != -1) return 7;
  if ((source_high - 3u) / 8u != 0x10000000u || source_high % 8u != 3u) return 8;
  return 42;
}
