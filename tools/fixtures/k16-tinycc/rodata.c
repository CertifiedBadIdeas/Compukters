/* K16 TinyCC canonical read-only data section fixture. */
const char k16_rodata_text[] = "K16";

int main(void) {
  return k16_rodata_text[0] == 'K' ? 42 : 1;
}
