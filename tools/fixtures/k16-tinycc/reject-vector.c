/* K16 C ABI intentionally excludes vector extension values. */
typedef int k16_vector4 __attribute__((vector_size(16)));

int unsupported_vector(k16_vector4 value) {
  return value[0];
}
