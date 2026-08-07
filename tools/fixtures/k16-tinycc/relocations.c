/* K16 TinyCC data, pointer, and call relocation ABI fixture. */
extern int external_add(int, int);
int writable = 5;
int *writable_ptr = &writable;

int main(void) {
  *writable_ptr += 8;
  return external_add(*writable_ptr, 29);
}
