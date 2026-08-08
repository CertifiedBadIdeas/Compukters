/* Empty GNU aggregates have no K16 C ABI representation. */
struct Empty {
};

int unsupported_empty_aggregate(struct Empty value) {
  return sizeof(value);
}
