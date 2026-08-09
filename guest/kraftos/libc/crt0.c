#include <kraft/syscalls.h>

#define KRAFT_RAW_ARG_MAX 4
#define KRAFT_ARG_MAX 5
#define KRAFT_ARG_BYTES_MAX 128

#ifndef KRAFT_CRT_ENTRY
#define KRAFT_CRT_ENTRY main
#endif

#ifndef KRAFT_CRT_USER_MAIN
#define KRAFT_CRT_USER_MAIN kraft_main
#endif

struct kraft_raw_arg {
  const char *ptr;
  unsigned int len;
};

int KRAFT_CRT_USER_MAIN(int argc, char **argv);

static void copy_arg(char *dst, const struct kraft_raw_arg *src) {
  unsigned int len = src->len;
  if (len > KRAFT_ARG_BYTES_MAX) {
    len = KRAFT_ARG_BYTES_MAX;
  }

  for (unsigned int index = 0; index < len; index += 1) {
    dst[index] = src->ptr[index];
  }
  dst[len] = 0;
}

int KRAFT_CRT_ENTRY(unsigned int raw_argc,
                    const struct kraft_raw_arg *raw_argv) {
  char arg_storage[KRAFT_RAW_ARG_MAX][KRAFT_ARG_BYTES_MAX + 1];
  char *argv[KRAFT_ARG_MAX + 1];

  if (raw_argc > KRAFT_RAW_ARG_MAX) {
    raw_argc = KRAFT_RAW_ARG_MAX;
  }
  unsigned int argc = raw_argc + 1;

  argv[0] = "";
  for (unsigned int index = 0; index < raw_argc; index += 1) {
    copy_arg(arg_storage[index], &raw_argv[index]);
    argv[index + 1] = arg_storage[index];
  }
  argv[argc] = 0;

  return KRAFT_CRT_USER_MAIN((int)argc, argv);
}
