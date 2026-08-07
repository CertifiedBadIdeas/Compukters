struct Pair {
  int first;
  int second;
};

struct Pair unsupported_aggregate(struct Pair value) {
  return value;
}
