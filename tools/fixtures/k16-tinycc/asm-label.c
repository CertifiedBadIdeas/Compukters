/* GNU declaration labels rename symbols without integrated assembly. */
extern int write(int fd, const void *buffer, unsigned int count)
    __asm__("kraft_sys_write");

int call_write_alias(const char *text) {
  return write(1, text, 1u);
}
