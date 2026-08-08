/* K16 C ABI intentionally excludes complex values. */
double _Complex unsupported_complex(double _Complex value) {
  return value;
}
