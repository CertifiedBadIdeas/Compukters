#include "../boot-chain/boot_chain.h"

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;

extern void __k16_halt_once(void);

#define CONTROL_STATUS 0x10000000u
#define CONTROL_PANIC_CODE 0x10000004u
#define CONTROL_YIELD 0x1000000cu
#define STATUS_BOOTING 1
#define STATUS_HALTED 3
#define STATUS_PANIC 4

#define DEBUG_WRITE 0x10000100u

#define GPU_COMMAND 0x10000510u
#define GPU_X 0x1000051cu
#define GPU_Y 0x10000520u
#define GPU_RECT_WIDTH 0x10000524u
#define GPU_RECT_HEIGHT 0x10000528u
#define GPU_BUFFER_ADDR 0x1000052cu
#define GPU_BUFFER_STRIDE_BYTES 0x10000530u
#define GPU_COLOR 0x10000534u
#define GPU_COMMAND_CLEAR 1
#define GPU_COMMAND_BLIT_BUFFER 2
#define GPU_COMMAND_PRESENT 3

#define TIMER_GAME_TICKS_LOW 0x10000604u
#define TIMER_GAME_TICKS_HIGH 0x10000608u

static void write_u8(u32 address, u8 value) {
  *(volatile u8 *)address = value;
}

static void write_u32(u32 address, u32 value) {
  *(volatile u32 *)address = value;
}

static u32 read_u32(u32 address) { return *(volatile u32 *)address; }

static void write_i32(u32 address, int value) {
  *(volatile int *)address = value;
}

static void yield_once(void) { write_i32(CONTROL_YIELD, 1); }

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

static void clear_display(void) {
  write_i32(GPU_COLOR, 0);
  write_i32(GPU_COMMAND, GPU_COMMAND_CLEAR);
}

static const u8 GLYPH_CODES[16] = {'1', '6', 'A', 'B', 'C', 'D', 'E', 'I',
                                   'K', 'L', 'N', 'O', 'S', 'T', 'V', ' '};

static const u8 GLYPH_ROWS[16][7] = {
    {0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e},
    {0x0e, 0x10, 0x10, 0x1e, 0x11, 0x11, 0x0e},
    {0x0e, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11},
    {0x1e, 0x11, 0x11, 0x1e, 0x11, 0x11, 0x1e},
    {0x0f, 0x10, 0x10, 0x10, 0x10, 0x10, 0x0f},
    {0x1e, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1e},
    {0x1f, 0x10, 0x10, 0x1e, 0x10, 0x10, 0x1f},
    {0x1f, 0x04, 0x04, 0x04, 0x04, 0x04, 0x1f},
    {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11},
    {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1f},
    {0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11},
    {0x0e, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e},
    {0x0f, 0x10, 0x10, 0x0e, 0x01, 0x01, 0x1e},
    {0x1f, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04},
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x0a, 0x04},
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
};

static void glyph_rows(u8 byte, u8 rows[7]) {
  u32 index = 0;
  while (index < 16u) {
    if (GLYPH_CODES[index] == byte) {
      u32 row = 0;
      while (row < 7u) {
        rows[row] = GLYPH_ROWS[index][row];
        row++;
      }
      return;
    }
    index++;
  }
  rows[0] = 0x1f;
  rows[1] = 0x01;
  rows[2] = 0x02;
  rows[3] = 0x04;
  rows[4] = 0x04;
  rows[5] = 0x00;
  rows[6] = 0x04;
}

static void draw_display_glyph(int x, int y, const u8 rows[7]) {
  u16 pixels[64];
  u32 pixel_index = 0;
  while (pixel_index < 64u) {
    pixels[pixel_index] = 0u;
    pixel_index++;
  }

  u32 row = 0;
  while (row < 7u) {
    u8 bits = rows[row];
    u32 column = 0;
    while (column < 5u) {
      if ((bits & (1u << (4u - column))) != 0u) {
        pixels[row * 8u + column] = 0x07e0u;
      }
      column++;
    }
    row++;
  }

  write_i32(GPU_X, x);
  write_i32(GPU_Y, y);
  write_i32(GPU_RECT_WIDTH, 8);
  write_i32(GPU_RECT_HEIGHT, 8);
  write_u32(GPU_BUFFER_ADDR, (u32)pixels);
  write_i32(GPU_BUFFER_STRIDE_BYTES, 16);
  write_i32(GPU_COMMAND, GPU_COMMAND_BLIT_BUFFER);
}

static void draw_display_line(int x, int y, const char *text) {
  u32 column = 0;
  while (text[column] != 0) {
    u8 rows[7];
    glyph_rows((u8)text[column], rows);
    draw_display_glyph(x + (int)(column * 8u), y, rows);
    column++;
  }
}

static void present_display(void) { write_i32(GPU_COMMAND, GPU_COMMAND_PRESENT); }

static void print_bios_banner(void) {
  draw_display_line(8, 8, "K16 BIOS");
  present_display();
}

static void print_no_bootable_device(void) {
  draw_display_line(8, 24, "NO BOOTABLE DEVICE");
  present_display();
}

static void sleep_ticks(u32 ticks) {
  u32 start_low = read_u32(TIMER_GAME_TICKS_LOW);
  u32 start_high = read_u32(TIMER_GAME_TICKS_HIGH);
  u32 target_low = start_low + ticks;
  u32 target_high = start_high;
  if (target_low < start_low) {
    target_high++;
  }

  for (;;) {
    u32 current_high = read_u32(TIMER_GAME_TICKS_HIGH);
    u32 current_low = read_u32(TIMER_GAME_TICKS_LOW);
    if (current_high > target_high ||
        (current_high == target_high && current_low >= target_low)) {
      return;
    }
    yield_once();
  }
}

static void set_halted(int code) {
  write_i32(CONTROL_PANIC_CODE, code);
  write_i32(CONTROL_STATUS, STATUS_HALTED);
}

void _start(void) {
  struct k16_loaded_image image;
  int error;
  write_i32(CONTROL_STATUS, STATUS_BOOTING);
  clear_display();
  print_bios_banner();
  debug_print("K16 BIOS\n");
  sleep_ticks(20);

  error = load_k16e_from_storage0("BOOT", "boot", 4, "loader.kb", 9,
                                  K16E_ABI_KIND_BOOTLOADER, &image);
  if (error == 0) {
    enter_loaded_image(image);
  }

  print_no_bootable_device();
  debug_print("NO BOOTABLE DEVICE\n");
  set_halted(error);
  halt_forever();
}

void __k16_bios_panic(void) {
  debug_print("K16 BIOS PANIC\n");
  write_i32(CONTROL_PANIC_CODE, STATUS_PANIC);
  write_i32(CONTROL_STATUS, STATUS_PANIC);
  halt_forever();
}
