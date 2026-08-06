#include "../boot-chain/boot_chain.h"

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;

extern void __k16_halt_once(void);

#define CONTROL_STATUS 0x10000000u
#define CONTROL_PANIC_CODE 0x10000004u
#define CONTROL_YIELD 0x1000000cu
#define STATUS_BOOTING 1
#define STATUS_HALTED 3
#define STATUS_PANIC 4

#define DEBUG_WRITE 0x10000100u

#define GPU_DEVICE_ABI_VERSION 0x10000500u
#define GPU_PACKET_VERSION 0x1000050cu
#define GPU_SUBMISSION_ADDRESS 0x10000530u
#define GPU_SUBMISSION_LENGTH 0x10000534u
#define GPU_SUBMIT 0x10000538u
#define GPU_RESULT_CODE 0x1000053cu
#define GPU_COMMITTED_SEQUENCE_LOW 0x10000548u
#define GPU_COMMITTED_SEQUENCE_HIGH 0x1000054cu
#define GPU_DEVICE_ABI_VERSION_VALUE 2u
#define GPU_PACKET_VERSION_VALUE 1u

#define BIOS_FONT_MASK_ID 0xffffff01u
#define BIOS_INSTANCES_ID 0xffffff02u
#define BIOS_MAX_GLYPHS 18u
#define BIOS_INSTANCE_BYTES 24u
#define BIOS_PACKET_BYTES 1024u

#define TIMER_GAME_TICKS_LOW 0x10000604u
#define TIMER_GAME_TICKS_HIGH 0x10000608u

#define BIOS_BOOT_PARTITION "BOOT"
#define BIOS_BOOT_DIR "boot"
#define BIOS_BOOT_DIR_LEN 4u
#define BIOS_BOOTLOADER_FILE "loader.kb"
#define BIOS_BOOTLOADER_FILE_LEN 9u

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

void __k16_bios_panic(void);

struct gpu_packet_builder {
  u8 bytes[BIOS_PACKET_BYTES];
  u32 cursor;
  u32 operation_count;
};

static void put_u16(u8 *bytes, u32 offset, u16 value) {
  bytes[offset] = (u8)value;
  bytes[offset + 1u] = (u8)(value >> 8);
}

static void put_u32(u8 *bytes, u32 offset, u32 value) {
  bytes[offset] = (u8)value;
  bytes[offset + 1u] = (u8)(value >> 8);
  bytes[offset + 2u] = (u8)(value >> 16);
  bytes[offset + 3u] = (u8)(value >> 24);
}

static void put_u64(u8 *bytes, u32 offset, u64 value) {
  put_u32(bytes, offset, (u32)value);
  put_u32(bytes, offset + 4u, (u32)(value >> 32));
}

static u64 gpu_committed_sequence(void) {
  u32 low = read_u32(GPU_COMMITTED_SEQUENCE_LOW);
  u32 high = read_u32(GPU_COMMITTED_SEQUENCE_HIGH);
  return ((u64)high << 32) | (u64)low;
}

static void gpu_packet_begin(struct gpu_packet_builder *packet,
                             u64 expected_base_sequence) {
  u32 index = 0;
  while (index < BIOS_PACKET_BYTES) {
    packet->bytes[index] = 0;
    index++;
  }
  put_u32(packet->bytes, 0, 0x5550474bu);
  put_u16(packet->bytes, 4, 1u);
  put_u64(packet->bytes, 16, expected_base_sequence);
  packet->cursor = 24u;
  packet->operation_count = 0u;
}

static u32 gpu_packet_operation(struct gpu_packet_builder *packet, u16 opcode,
                                u32 body_bytes) {
  u32 operation_bytes = 8u + body_bytes;
  u32 body = packet->cursor + 8u;
  put_u16(packet->bytes, packet->cursor, opcode);
  put_u32(packet->bytes, packet->cursor + 4u, operation_bytes);
  packet->cursor += (operation_bytes + 3u) & ~3u;
  packet->operation_count++;
  return body;
}

static u32 gpu_packet_finish(struct gpu_packet_builder *packet) {
  put_u32(packet->bytes, 8, packet->cursor);
  put_u32(packet->bytes, 12, packet->operation_count);
  return packet->cursor;
}

static void gpu_submit_packet(const u8 *packet, u32 packet_bytes) {
  if (read_u32(GPU_DEVICE_ABI_VERSION) != GPU_DEVICE_ABI_VERSION_VALUE ||
      read_u32(GPU_PACKET_VERSION) != GPU_PACKET_VERSION_VALUE) {
    __k16_bios_panic();
  }
  write_u32(GPU_SUBMISSION_ADDRESS, (u32)packet);
  write_u32(GPU_SUBMISSION_LENGTH, packet_bytes);
  write_u32(GPU_SUBMIT, 1u);
  if (read_u32(GPU_RESULT_CODE) != 0u) {
    __k16_bios_panic();
  }
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

static u16 glyph_index(u8 byte) {
  u16 index = 0;
  while (index < 16u) {
    if (GLYPH_CODES[index] == byte) {
      return index;
    }
    index++;
  }
  return 15u;
}

static void build_bios_font_mask(u8 *font_mask) {
  u32 glyph = 0;
  while (glyph < BIOS_MAX_GLYPHS) {
    u32 row = 0;
    while (row < 8u) {
      font_mask[glyph * 8u + row] =
          row < 7u && glyph < 16u ? (u8)(GLYPH_ROWS[glyph][row] << 3) : 0u;
      row++;
    }
    glyph++;
  }
}

static void encode_instance(u8 *target, u8 byte, u16 x, u16 y) {
  u16 source_y = glyph_index(byte) * 8u;
  put_u16(target, 0, 0u);
  put_u16(target, 2, source_y);
  put_u16(target, 4, 8u);
  put_u16(target, 6, 8u);
  put_u16(target, 8, x);
  put_u16(target, 10, y);
  put_u16(target, 12, 8u);
  put_u16(target, 14, 8u);
  put_u16(target, 16, 0x07e0u);
  put_u16(target, 18, 0u);
  put_u16(target, 20, 1u);
  put_u16(target, 22, 0u);
}

static void build_instance_line(u8 *instance_records, const char *text, u16 y) {
  u32 index = 0;
  int ended = 0;
  while (index < BIOS_MAX_GLYPHS) {
    u8 byte = (u8)' ';
    if (!ended) {
      byte = (u8)text[index];
      if (byte == 0u) {
        ended = 1;
        byte = (u8)' ';
      }
    }
    encode_instance(instance_records + index * BIOS_INSTANCE_BYTES, byte,
                    (u16)(8u + index * 8u), y);
    index++;
  }
}

static void copy_bytes(u8 *target, const u8 *source, u32 byte_count) {
  u32 index = 0;
  while (index < byte_count) {
    target[index] = source[index];
    index++;
  }
}

static void print_bios_banner(void) {
  struct gpu_packet_builder packet;
  u8 font_mask[BIOS_MAX_GLYPHS * 8u];
  u8 instance_records[BIOS_MAX_GLYPHS * BIOS_INSTANCE_BYTES];
  u32 body;
  build_bios_font_mask(font_mask);
  build_instance_line(instance_records, "K16 BIOS", 8u);
  gpu_packet_begin(&packet, gpu_committed_sequence());

  body = gpu_packet_operation(&packet, 0x0002u, 8u + sizeof(font_mask));
  put_u32(packet.bytes, body, BIOS_FONT_MASK_ID);
  put_u16(packet.bytes, body + 4u, 8u);
  put_u16(packet.bytes, body + 6u, BIOS_MAX_GLYPHS * 8u);
  copy_bytes(packet.bytes + body + 8u, font_mask, sizeof(font_mask));

  body = gpu_packet_operation(&packet, 0x0003u, 8u + sizeof(instance_records));
  put_u32(packet.bytes, body, BIOS_INSTANCES_ID);
  put_u16(packet.bytes, body + 4u, BIOS_MAX_GLYPHS);
  copy_bytes(packet.bytes + body + 8u, instance_records,
             sizeof(instance_records));

  body = gpu_packet_operation(&packet, 0x0030u, 8u + 24u);
  put_u16(packet.bytes, body, 0u);
  put_u32(packet.bytes, body + 4u, 1u);
  put_u16(packet.bytes, body + 8u, 0x0022u);
  put_u32(packet.bytes, body + 12u, 24u);
  put_u32(packet.bytes, body + 16u, BIOS_FONT_MASK_ID);
  put_u32(packet.bytes, body + 20u, BIOS_INSTANCES_ID);
  put_u16(packet.bytes, body + 24u, 0u);
  put_u16(packet.bytes, body + 26u, BIOS_MAX_GLYPHS);
  put_u16(packet.bytes, body + 28u, 0u);
  put_u16(packet.bytes, body + 30u, 0u);

  gpu_submit_packet(packet.bytes, gpu_packet_finish(&packet));
}

static void print_no_bootable_device(void) {
  struct gpu_packet_builder packet;
  u8 instance_records[BIOS_MAX_GLYPHS * BIOS_INSTANCE_BYTES];
  u32 body;
  build_instance_line(instance_records, "NO BOOTABLE DEVICE", 24u);
  gpu_packet_begin(&packet, gpu_committed_sequence());
  body = gpu_packet_operation(&packet, 0x0012u, 8u + sizeof(instance_records));
  put_u32(packet.bytes, body, BIOS_INSTANCES_ID);
  put_u16(packet.bytes, body + 4u, 0u);
  put_u16(packet.bytes, body + 6u, BIOS_MAX_GLYPHS);
  copy_bytes(packet.bytes + body + 8u, instance_records,
             sizeof(instance_records));
  gpu_submit_packet(packet.bytes, gpu_packet_finish(&packet));
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
  print_bios_banner();
  debug_print("K16 BIOS\n");
  sleep_ticks(20);

  error = load_k16e_from_storage0(BIOS_BOOT_PARTITION, BIOS_BOOT_DIR,
                                  BIOS_BOOT_DIR_LEN, BIOS_BOOTLOADER_FILE,
                                  BIOS_BOOTLOADER_FILE_LEN,
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
