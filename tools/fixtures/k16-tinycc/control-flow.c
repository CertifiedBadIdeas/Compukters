/* K16 TinyCC structured control-flow ABI fixture. */
static int classify(int value) {
  if (value < 0) return 3;
  if (value == 0) return 5;
  return 7;
}

int main(void) {
  int total = 0;
  for (int i = -2; i < 4; i += 1) total += classify(i);
  switch (total) {
    case 32: return 42;
    default: return 1;
  }
}
