#include "../boot-chain/boot_chain.h"

typedef unsigned char u8;
typedef unsigned int u32;

extern void __k16_halt_once(void);

#define CONTROL_STATUS 0x10000000u
#define CONTROL_PANIC_CODE 0x10000004u
#define STATUS_HALTED 3
#define STATUS_PANIC 4
#define DEBUG_WRITE 0x10000100u

static void write_u8(u32 address, u8 value) { *(volatile u8 *)address = value; }

static void write_i32(u32 address, int value) {
  *(volatile int *)address = value;
}

static void halt_forever(void) {
  for (;;) {
    __k16_halt_once();
  }
}

static void debug_print(const char *text) {
  u32 index = 0;
  while (text[index] != 0) {
    write_u8(DEBUG_WRITE, (u8)text[index]);
    index++;
  }
}

static void set_halted(int code) {
  write_i32(CONTROL_PANIC_CODE, code);
  write_i32(CONTROL_STATUS, STATUS_HALTED);
}

void _start(void) {
  struct k16_loaded_image image;
  int error;
  debug_print("K16 BOOT\n");

  error = load_k16e_from_storage0("ROOT", "boot", 4, "kernel.kx", 9,
                                  K16E_ABI_KIND_KERNEL, &image);
  if (error == 0) {
    enter_loaded_image(image);
  }

  set_halted(error);
  halt_forever();
}

void __k16_boot_panic(void) {
  debug_print("K16 BOOT PANIC\n");
  write_i32(CONTROL_PANIC_CODE, STATUS_PANIC);
  write_i32(CONTROL_STATUS, STATUS_PANIC);
  halt_forever();
}
