/* K16 TinyCC direct, indirect, and stack-argument ABI fixture. */
typedef int (*binary_fn)(int, int);

static int add(int a, int b) { return a + b; }
static int sum6(int a, int b, int c, int d, int e, int f) {
  return a + b + c + d + e + f;
}
static int invoke(binary_fn fn, int a, int b) { return fn(a, b); }

int main(void) {
  return sum6(1, 2, 3, 4, 5, 6) + invoke(add, 10, 11);
}
