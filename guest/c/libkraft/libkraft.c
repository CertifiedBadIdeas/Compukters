extern void __k16_halt_once(void);
extern unsigned int __k16_syscall1(unsigned int number, unsigned int arg0);
extern unsigned int __k16_syscall3(unsigned int number, unsigned int arg0,
                                   unsigned int arg1, unsigned int arg2);
extern unsigned int __k16_close_syscall(unsigned int fd);
extern unsigned int __k16_open_syscall(unsigned int ptr, unsigned int len,
                                       unsigned int flags);
extern unsigned int __k16_read_syscall(unsigned int fd, unsigned int ptr,
                                       unsigned int len);
extern unsigned int __k16_sbrk_syscall(unsigned int delta);
extern unsigned int __k16_write_syscall(unsigned int fd, unsigned int ptr,
                                        unsigned int len);

#define K16_SYSCALL_EXIT 6u
#define K16_SYSCALL_READ_DIR 14u
#define K16_SYSCALL_STAT 15u
#define K16_SYSCALL_UNLINK 18u
#define K16_SYSCALL_MKDIR 19u
#define K16_SYSCALL_RMDIR 20u
#define K16_SYSCALL_RENAME 21u
#define K16_SYSCALL_SPAWN 22u
#define K16_SYSCALL_WAIT 23u
#define K16_SYSCALL_RUN 9u
#define K16_RUN_FORMAT_ARGV 1u

int kraft_sys_open(const char *path, unsigned int len, unsigned int flags) {
  return (int)__k16_open_syscall((unsigned int)path, len, flags);
}

int kraft_sys_read(unsigned int fd, void *buffer, unsigned int len) {
  return (int)__k16_read_syscall(fd, (unsigned int)buffer, len);
}

int kraft_sys_write(unsigned int fd, const void *buffer, unsigned int len) {
  return (int)__k16_write_syscall(fd, (unsigned int)buffer, len);
}

int kraft_sys_close(unsigned int fd) { return (int)__k16_close_syscall(fd); }

int kraft_sys_read_dir(const void *request, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_READ_DIR, (unsigned int)request, len,
                             0u);
}

int kraft_sys_stat(const char *path, unsigned int len, void *metadata) {
  return (int)__k16_syscall3(K16_SYSCALL_STAT, (unsigned int)path, len,
                             (unsigned int)metadata);
}

int kraft_sys_rename(const void *request, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_RENAME, (unsigned int)request, len,
                             0u);
}

int kraft_sys_spawn(const void *request, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_SPAWN, (unsigned int)request, len,
                             0u);
}

int kraft_sys_run(const void *request, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_RUN, (unsigned int)request, len,
                             K16_RUN_FORMAT_ARGV);
}

int kraft_sys_wait(unsigned int pid, int *status) {
  return (int)__k16_syscall3(K16_SYSCALL_WAIT, pid, (unsigned int)status, 0u);
}

int kraft_sys_mkdir(const char *path, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_MKDIR, (unsigned int)path, len, 0u);
}

int kraft_sys_rmdir(const char *path, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_RMDIR, (unsigned int)path, len, 0u);
}

int kraft_sys_unlink(const char *path, unsigned int len) {
  return (int)__k16_syscall3(K16_SYSCALL_UNLINK, (unsigned int)path, len, 0u);
}

void *kraft_sys_sbrk(int delta) {
  return (void *)__k16_sbrk_syscall((unsigned int)delta);
}

void kraft_sys_exit(int status) {
  (void)__k16_syscall1(K16_SYSCALL_EXIT, (unsigned int)status);
  for (;;) {
    __k16_halt_once();
  }
}
