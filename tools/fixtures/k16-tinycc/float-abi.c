float add_float(float lhs, float rhs) {
  return lhs + rhs;
}

float sub_float(float lhs, float rhs) {
  return lhs - rhs;
}

float mul_float(float lhs, float rhs) {
  return lhs * rhs;
}

float div_float(float lhs, float rhs) {
  return lhs / rhs;
}

float neg_float(float value) {
  return -value;
}

double add_double(double lhs, double rhs) {
  return lhs + rhs;
}

double sub_double(double lhs, double rhs) {
  return lhs - rhs;
}

double mul_double(double lhs, double rhs) {
  return lhs * rhs;
}

double div_double(double lhs, double rhs) {
  return lhs / rhs;
}

double neg_double(double value) {
  return -value;
}

int compare_float(float lhs, float rhs) {
  return ((lhs == rhs) << 0) | ((lhs != rhs) << 1) |
         ((lhs < rhs) << 2) | ((lhs <= rhs) << 3) |
         ((lhs > rhs) << 4) | ((lhs >= rhs) << 5);
}

int compare_double(double lhs, double rhs) {
  return ((lhs == rhs) << 0) | ((lhs != rhs) << 1) |
         ((lhs < rhs) << 2) | ((lhs <= rhs) << 3) |
         ((lhs > rhs) << 4) | ((lhs >= rhs) << 5);
}

float signed_int_to_float(int value) {
  return (float)value;
}

double unsigned_int_to_double(unsigned int value) {
  return (double)value;
}

float signed_long_long_to_float(long long value) {
  return (float)value;
}

double unsigned_long_long_to_double(unsigned long long value) {
  return (double)value;
}

int float_to_signed_int(float value) {
  return (int)value;
}

unsigned int double_to_unsigned_int(double value) {
  return (unsigned int)value;
}

long long double_to_signed_long_long(double value) {
  return (long long)value;
}

unsigned long long float_to_unsigned_long_long(float value) {
  return (unsigned long long)value;
}

double widen_float(float value) {
  return (double)value;
}

float narrow_double(double value) {
  return (float)value;
}
