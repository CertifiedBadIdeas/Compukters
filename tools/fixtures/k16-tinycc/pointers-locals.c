/* K16 TinyCC pointer arithmetic and local array ABI fixture. */
int main(void) {
  int values[4] = { 3, 5, 7, 11 };
  int *cursor = values;
  cursor += 2;
  *cursor += 16;
  return values[0] + values[1] + values[2] + values[3];
}
