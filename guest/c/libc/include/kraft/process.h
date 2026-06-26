#ifndef KRAFT_PROCESS_H
#define KRAFT_PROCESS_H

#define KRAFT_SPAWN_ARGV_REQUEST_MAGIC 0x57415053u
#define KRAFT_MAX_PROCESS_ARGS 4
#define KRAFT_MAX_PROCESS_PATH_BYTES 61
#define KRAFT_MAX_PROCESS_ARG_BYTES 128
#define KRAFT_MAX_SPAWN_ARGV_REQUEST_BYTES 601

int kraft_spawn_with_args(const char *path, int argc, const char *const *argv);
int kraft_wait(int pid, int *status);

#endif
