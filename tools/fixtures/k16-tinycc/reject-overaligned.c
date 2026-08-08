/* K16 C ABI alignment is capped at eight bytes. */
struct Overaligned {
  int value;
} __attribute__((aligned(16)));

int unsupported_overaligned(struct Overaligned value) {
  return value.value;
}
