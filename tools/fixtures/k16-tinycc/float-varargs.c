typedef __builtin_va_list va_list;
#define va_start(list, last) __builtin_va_start(list, last)
#define va_arg(list, type) __builtin_va_arg(list, type)
#define va_end(list) ((void)0)

double add_promoted_float(int count, ...) {
  va_list arguments;
  double lhs;
  double rhs;

  va_start(arguments, count);
  lhs = va_arg(arguments, double);
  rhs = va_arg(arguments, double);
  va_end(arguments);
  return lhs + rhs;
}
