#ifndef KRAFT_SDK_PROCESS_H
#define KRAFT_SDK_PROCESS_H

int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);
int kraft_run_with_args(const char *path, int argc, const char *const *argv);
int kraft_wait(int pid, int *status);

#endif
