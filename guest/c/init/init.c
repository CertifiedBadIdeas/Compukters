#include <kraft/process.h>
#include <unistd.h>

#define SHELL_PATH "/bin/shell.kx"

int main(int argc, char **argv) {
  const char *shell_args[] = {SHELL_PATH};

  (void)argc;
  (void)argv;

  for (;;) {
    int status = 0;
    int pid = kraft_spawn_with_args(SHELL_PATH, 1, shell_args);
    if (pid < 0) {
      _exit(1);
    }

    if (kraft_wait(pid, &status) < 0) {
      _exit(1);
    }

    if (status == 0) {
      continue;
    }
    _exit(status);
  }
}
