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

#define STORAGE_VERSION 0x10000400u
#define STORAGE_STATUS 0x10000404u
#define STORAGE_ERROR 0x10000408u
#define STORAGE_COMMAND 0x1000040cu
#define STORAGE_BLOCK_SIZE 0x10000410u
#define STORAGE_CAPACITY_BLOCKS_LOW 0x10000414u
#define STORAGE_CAPACITY_BLOCKS_HIGH 0x10000418u
#define STORAGE_LBA_LOW 0x1000041cu
#define STORAGE_LBA_HIGH 0x10000420u
#define STORAGE_BLOCK_COUNT 0x10000424u
#define STORAGE_BUFFER_ADDR 0x10000428u
#define STORAGE_BYTES_DONE 0x1000042cu
#define STORAGE_MEDIA_STATUS 0x10000438u
#define STORAGE_STATUS_DONE 2
#define STORAGE_ERROR_NONE 0
#define STORAGE_COMMAND_READ_BLOCKS 1
#define STORAGE_MEDIA_PRESENT 1
#define STORAGE_MEDIA_READ_ONLY 2

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

#define SCRATCH_ADDR 0x00000600u
#define BLOCK_SIZE 512u

#define STATE_PARTITION_START_LBA 0x00000200u
#define STATE_PARTITION_BLOCK_COUNT 0x00000204u
#define STATE_SUPERBLOCK_TOTAL_BLOCKS 0x00000208u
#define STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK 0x0000020cu
#define STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT 0x00000210u
#define STATE_SUPERBLOCK_ROOT_INODE_ID 0x00000214u
#define STATE_INODE_STATE 0x00000218u
#define STATE_INODE_SIZE_BYTES 0x0000021cu
#define STATE_INODE_EXTENT_COUNT 0x00000220u
#define STATE_INODE_EXTENT_START_BLOCKS 0x00000224u
#define STATE_INODE_EXTENT_BLOCK_COUNTS 0x00000234u
#define STATE_SELECTED_INODE_ID 0x0000024cu

#define K16PT_HEADER_SIZE 16u
#define K16PT_ENTRY_SIZE 32u
#define K16PT_MAX_ENTRIES 15u
#define K16FS_INODE_SIZE 64u
#define K16FS_DIRECTORY_ENTRY_SIZE 64u
#define K16FS_MAX_NAME_BYTES 56u
#define K16FS_MAX_INLINE_EXTENTS 4u
#define FIXED_K16E_V1_HEADER_SIZE 52u
#define FIXED_K16E_V1_PAYLOAD_OFFSET 52u
#define K16E_ABI_KIND_BOOTLOADER 1u

#define ERR_STORAGE_VERSION 10
#define ERR_INVALID_PARTITION_TABLE 11
#define ERR_PARTITION_NOT_FOUND 12
#define ERR_INVALID_FILESYSTEM 13
#define ERR_PATH_NOT_FOUND 14
#define ERR_INVALID_EXECUTABLE 15
#define ERR_STORAGE_TRANSFER 16
#define ERR_STORAGE_BLOCK_SIZE 17
#define ERR_STORAGE_MEDIA 18

static void write_u8(u32 address, u8 value) {
  *(volatile u8 *)address = value;
}

static u8 read_u8(u32 address) { return *(volatile u8 *)address; }

static void write_u32(u32 address, u32 value) {
  *(volatile u32 *)address = value;
}

static u32 read_u32(u32 address) { return *(volatile u32 *)address; }

static void write_i32(u32 address, int value) {
  *(volatile int *)address = value;
}

static int read_i32(u32 address) { return *(volatile int *)address; }

static u32 min_u32(u32 lhs, u32 rhs) { return lhs < rhs ? lhs : rhs; }

static int checked_add_u32(u32 lhs, u32 rhs, u32 *out) {
  u32 value = lhs + rhs;
  if (value < lhs) {
    return 0;
  }
  *out = value;
  return 1;
}

static void copy_ram_to_ram(u32 src_addr, u32 dst_addr, u32 len) {
  u32 offset = 0;
  while (offset < len) {
    write_u8(dst_addr + offset, read_u8(src_addr + offset));
    offset++;
  }
}

static int scratch_eq(u32 offset, const char *bytes, u32 len) {
  u32 index = 0;
  while (index < len) {
    if (read_u8(SCRATCH_ADDR + offset + index) != (u8)bytes[index]) {
      return 0;
    }
    index++;
  }
  return 1;
}

static int scratch_bytes_eq(u32 offset, const char *bytes, u32 len) {
  return scratch_eq(offset, bytes, len);
}

static u8 scratch_u8(u32 offset) { return read_u8(SCRATCH_ADDR + offset); }

static u32 scratch_u32(u32 offset) { return read_u32(SCRATCH_ADDR + offset); }

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

static int validate_extent(u32 start_block, u32 block_count, u32 total_blocks) {
  u32 end;
  if (block_count == 0u || !checked_add_u32(start_block, block_count, &end) ||
      end > total_blocks) {
    return ERR_INVALID_FILESYSTEM;
  }
  return 0;
}

static int read_storage_block(u32 lba) {
  int media;
  if (read_i32(STORAGE_VERSION) != 1) {
    return ERR_STORAGE_VERSION;
  }
  if (read_i32(STORAGE_BLOCK_SIZE) != (int)BLOCK_SIZE) {
    return ERR_STORAGE_BLOCK_SIZE;
  }
  media = read_i32(STORAGE_MEDIA_STATUS);
  if (media != STORAGE_MEDIA_PRESENT && media != STORAGE_MEDIA_READ_ONLY) {
    return ERR_STORAGE_MEDIA;
  }

  write_u32(STORAGE_LBA_LOW, lba);
  write_u32(STORAGE_LBA_HIGH, 0);
  write_u32(STORAGE_BLOCK_COUNT, 1);
  write_u32(STORAGE_BUFFER_ADDR, SCRATCH_ADDR);
  write_i32(STORAGE_COMMAND, STORAGE_COMMAND_READ_BLOCKS);

  if (read_i32(STORAGE_STATUS) != STORAGE_STATUS_DONE ||
      read_i32(STORAGE_ERROR) != STORAGE_ERROR_NONE ||
      read_u32(STORAGE_BYTES_DONE) != BLOCK_SIZE) {
    return ERR_STORAGE_TRANSFER;
  }
  return 0;
}

static int read_fs_block(u32 block) {
  u32 lba;
  if (block >= read_u32(STATE_PARTITION_BLOCK_COUNT) ||
      !checked_add_u32(read_u32(STATE_PARTITION_START_LBA), block, &lba)) {
    return ERR_INVALID_FILESYSTEM;
  }
  return read_storage_block(lba);
}

static int read_partition(const char *partition_type) {
  int error = read_storage_block(0);
  if (error != 0) {
    return error;
  }

  if (!scratch_eq(0, "K16PT", 5) || scratch_u8(5) != 1u ||
      scratch_u8(7) != 0u) {
    return ERR_INVALID_PARTITION_TABLE;
  }
  u8 entry_count = scratch_u8(6);
  if (entry_count > K16PT_MAX_ENTRIES || scratch_u32(8) != 0u ||
      scratch_u32(12) != 1u) {
    return ERR_INVALID_PARTITION_TABLE;
  }
  if (read_u32(STORAGE_CAPACITY_BLOCKS_HIGH) != 0u) {
    return ERR_INVALID_PARTITION_TABLE;
  }
  u32 capacity_low = read_u32(STORAGE_CAPACITY_BLOCKS_LOW);

  u32 index = 0;
  while (index < (u32)entry_count) {
    u32 offset = K16PT_HEADER_SIZE + index * K16PT_ENTRY_SIZE;
    u32 start_lba = scratch_u32(offset + 8u);
    u32 block_count = scratch_u32(offset + 12u);
    u32 end_lba;
    if (scratch_u32(offset + 4u) != 0u || start_lba < 1u ||
        block_count == 0u ||
        !checked_add_u32(start_lba, block_count, &end_lba) ||
        end_lba > capacity_low) {
      return ERR_INVALID_PARTITION_TABLE;
    }
    if (scratch_eq(offset, partition_type, 4)) {
      write_u32(STATE_PARTITION_START_LBA, start_lba);
      write_u32(STATE_PARTITION_BLOCK_COUNT, block_count);
      return 0;
    }
    index++;
  }
  return ERR_PARTITION_NOT_FOUND;
}

static int read_inode(u32 inode_id) {
  u32 inodes_per_block = BLOCK_SIZE / K16FS_INODE_SIZE;
  u32 inode_capacity = read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) *
                       inodes_per_block;
  if (inode_id >= inode_capacity) {
    return ERR_INVALID_FILESYSTEM;
  }

  u32 inode_block = read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) +
                    inode_id / inodes_per_block;
  u32 inode_offset = (inode_id % inodes_per_block) * K16FS_INODE_SIZE;
  int error = read_fs_block(inode_block);
  if (error != 0) {
    return error;
  }

  u8 extent_count = scratch_u8(inode_offset + 0x10u);
  if (scratch_u32(inode_offset + 0x0cu) != 0u ||
      extent_count > K16FS_MAX_INLINE_EXTENTS) {
    return ERR_INVALID_FILESYSTEM;
  }

  write_u32(STATE_SELECTED_INODE_ID, inode_id);
  write_u32(STATE_INODE_STATE, (u32)scratch_u8(inode_offset));
  write_u32(STATE_INODE_SIZE_BYTES, scratch_u32(inode_offset + 0x08u));
  write_u32(STATE_INODE_EXTENT_COUNT, (u32)extent_count);

  u32 index = 0;
  while (index < (u32)extent_count) {
    u32 offset = inode_offset + 0x20u + index * 8u;
    u32 start_block = scratch_u32(offset);
    u32 block_count = scratch_u32(offset + 4u);
    error = validate_extent(start_block, block_count,
                            read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS));
    if (error != 0) {
      return error;
    }
    write_u32(STATE_INODE_EXTENT_START_BLOCKS + index * 4u, start_block);
    write_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + index * 4u, block_count);
    index++;
  }
  return 0;
}

static int read_superblock(void) {
  int error = read_fs_block(0);
  if (error != 0) {
    return error;
  }
  if (!scratch_eq(0, "K16FS", 5) || scratch_u8(5) != 1u ||
      scratch_u8(6) != 0u || scratch_u8(7) != 0u ||
      read_u32(SCRATCH_ADDR + 0x08u) != BLOCK_SIZE) {
    return ERR_INVALID_FILESYSTEM;
  }

  u32 total_blocks = read_u32(SCRATCH_ADDR + 0x0cu);
  if (total_blocks == 0u ||
      total_blocks > read_u32(STATE_PARTITION_BLOCK_COUNT)) {
    return ERR_INVALID_FILESYSTEM;
  }

  write_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS, total_blocks);
  write_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK,
            read_u32(SCRATCH_ADDR + 0x18u));
  write_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT,
            read_u32(SCRATCH_ADDR + 0x1cu));
  write_u32(STATE_SUPERBLOCK_ROOT_INODE_ID, read_u32(SCRATCH_ADDR + 0x20u));

  error = read_inode(read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID));
  if (error != 0) {
    return error;
  }
  if ((u8)read_u32(STATE_INODE_STATE) != 2u) {
    return ERR_INVALID_FILESYSTEM;
  }
  return 0;
}

static int find_directory_entry(const char *name, u32 name_len,
                                u32 *inode_id) {
  if (name_len == 0u || name_len > K16FS_MAX_NAME_BYTES ||
      read_u32(STATE_INODE_SIZE_BYTES) % K16FS_DIRECTORY_ENTRY_SIZE != 0u) {
    return ERR_INVALID_FILESYSTEM;
  }

  u32 remaining = read_u32(STATE_INODE_SIZE_BYTES);
  u32 extent_index = 0;
  while (extent_index < read_u32(STATE_INODE_EXTENT_COUNT)) {
    u32 extent_start_block =
        read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index * 4u);
    u32 extent_block_count =
        read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index * 4u);
    int error = validate_extent(extent_start_block, extent_block_count,
                                read_u32(STATE_SUPERBLOCK_TOTAL_BLOCKS));
    if (error != 0) {
      return error;
    }

    u32 block_index = 0;
    while (block_index < extent_block_count) {
      error = read_fs_block(extent_start_block + block_index);
      if (error != 0) {
        return error;
      }

      u32 offset = 0;
      while (offset < BLOCK_SIZE && remaining > 0u) {
        u8 state = scratch_u8(offset);
        if (state == 1u) {
          u8 entry_name_len = scratch_u8(offset + 1u);
          if (entry_name_len == 0u || entry_name_len > K16FS_MAX_NAME_BYTES ||
              scratch_u8(offset + 2u) != 0u ||
              scratch_u8(offset + 3u) != 0u) {
            return ERR_INVALID_FILESYSTEM;
          }
          if ((u32)entry_name_len == name_len &&
              scratch_bytes_eq(offset + 8u, name, name_len)) {
            *inode_id = scratch_u32(offset + 4u);
            return 0;
          }
        } else if (state != 0u && state != 2u) {
          return ERR_INVALID_FILESYSTEM;
        }
        remaining -= K16FS_DIRECTORY_ENTRY_SIZE;
        offset += K16FS_DIRECTORY_ENTRY_SIZE;
      }
      block_index++;
    }
    extent_index++;
  }

  if (remaining != 0u) {
    return ERR_INVALID_FILESYSTEM;
  }
  return ERR_PATH_NOT_FOUND;
}

static int find_file_inode_boot_loader(void) {
  u32 inode_id = read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID);
  int error = read_inode(inode_id);
  if (error != 0) {
    return error;
  }
  if ((u8)read_u32(STATE_INODE_STATE) != 2u) {
    return ERR_PATH_NOT_FOUND;
  }

  error = find_directory_entry("boot", 4u, &inode_id);
  if (error != 0) {
    return error;
  }
  error = read_inode(inode_id);
  if (error != 0) {
    return error;
  }
  if ((u8)read_u32(STATE_INODE_STATE) != 2u) {
    return ERR_PATH_NOT_FOUND;
  }

  error = find_directory_entry("loader.kb", 9u, &inode_id);
  if (error != 0) {
    return error;
  }
  error = read_inode(inode_id);
  if (error != 0) {
    return error;
  }
  if ((u8)read_u32(STATE_INODE_STATE) != 1u) {
    return ERR_PATH_NOT_FOUND;
  }
  return 0;
}

static int open_file_from_storage0(const char *partition_type) {
  int error = read_partition(partition_type);
  if (error != 0) {
    return error;
  }
  error = read_superblock();
  if (error != 0) {
    return error;
  }
  return find_file_inode_boot_loader();
}

static int copy_selected_file_range_to_ram(u32 file_offset, u32 dst_addr,
                                           u32 len) {
  u32 range_end;
  if (!checked_add_u32(file_offset, len, &range_end) ||
      range_end > read_u32(STATE_INODE_SIZE_BYTES)) {
    return ERR_INVALID_FILESYSTEM;
  }

  u32 copied = 0;
  u32 extent_file_start = 0;
  u32 extent_index = 0;
  while (extent_index < read_u32(STATE_INODE_EXTENT_COUNT) && copied < len) {
    u32 extent_start_block =
        read_u32(STATE_INODE_EXTENT_START_BLOCKS + extent_index * 4u);
    u32 extent_block_count =
        read_u32(STATE_INODE_EXTENT_BLOCK_COUNTS + extent_index * 4u);
    u32 extent_bytes = extent_block_count * BLOCK_SIZE;
    u32 extent_file_end;
    if (!checked_add_u32(extent_file_start, extent_bytes, &extent_file_end)) {
      return ERR_INVALID_FILESYSTEM;
    }

    if (range_end > extent_file_start && file_offset < extent_file_end) {
      u32 copy_start =
          file_offset > extent_file_start ? file_offset : extent_file_start;
      u32 copy_end = min_u32(range_end, extent_file_end);
      u32 cursor = copy_start;
      while (cursor < copy_end) {
        u32 within_extent = cursor - extent_file_start;
        u32 block_delta = within_extent / BLOCK_SIZE;
        u32 block_offset = within_extent % BLOCK_SIZE;
        u32 available = min_u32(BLOCK_SIZE - block_offset, copy_end - cursor);
        int error = read_fs_block(extent_start_block + block_delta);
        if (error != 0) {
          return error;
        }
        copy_ram_to_ram(SCRATCH_ADDR + block_offset, dst_addr + copied,
                        available);
        copied += available;
        cursor += available;
      }
    }

    extent_file_start = extent_file_end;
    extent_index++;
  }

  if (copied != len) {
    return ERR_INVALID_FILESYSTEM;
  }
  return 0;
}

static int parse_fixed_k16e_v1(u32 expected_abi_kind, u32 inode_size,
                               u32 *entry_pc, u32 *load_addr, u32 *file_size,
                               u32 *memory_size) {
  if (!scratch_eq(0, "K16E", 4) || read_u8(SCRATCH_ADDR + 4u) != 1u ||
      read_u8(SCRATCH_ADDR + 5u) != 0u ||
      read_u8(SCRATCH_ADDR + 6u) != 32u ||
      read_u8(SCRATCH_ADDR + 7u) != 0u ||
      read_u8(SCRATCH_ADDR + 8u) != 1u ||
      read_u8(SCRATCH_ADDR + 9u) != 0u ||
      read_u8(SCRATCH_ADDR + 10u) != 0u ||
      read_u8(SCRATCH_ADDR + 11u) != 0u ||
      read_u32(SCRATCH_ADDR + 16u) != 32u ||
      read_u32(SCRATCH_ADDR + 20u) != 1u ||
      read_u32(SCRATCH_ADDR + 24u) != expected_abi_kind ||
      read_u32(SCRATCH_ADDR + 28u) != 0u ||
      read_u32(SCRATCH_ADDR + 32u) != 1u ||
      read_u32(SCRATCH_ADDR + 40u) != FIXED_K16E_V1_PAYLOAD_OFFSET) {
    return ERR_INVALID_EXECUTABLE;
  }

  *entry_pc = read_u32(SCRATCH_ADDR + 12u);
  *load_addr = read_u32(SCRATCH_ADDR + 36u);
  *file_size = read_u32(SCRATCH_ADDR + 44u);
  *memory_size = read_u32(SCRATCH_ADDR + 48u);

  u32 load_end;
  u32 file_end;
  if (*file_size == 0u || *memory_size < *file_size ||
      (*file_size % 2u) != 0u || (*memory_size % 2u) != 0u ||
      !checked_add_u32(*load_addr, *memory_size, &load_end) ||
      *entry_pc < *load_addr || *entry_pc >= load_end ||
      (*entry_pc % 2u) != 0u ||
      !checked_add_u32(FIXED_K16E_V1_PAYLOAD_OFFSET, *file_size, &file_end) ||
      file_end > inode_size) {
    return ERR_INVALID_EXECUTABLE;
  }
  return 0;
}

static void zero_fill_ram(u32 dst_addr, u32 len) {
  u32 offset = 0;
  while (offset < len) {
    write_u8(dst_addr + offset, 0u);
    offset++;
  }
}

static int load_k16e_from_storage0(u32 *entry_pc) {
  u32 load_addr;
  u32 file_size;
  u32 memory_size;
  int error = open_file_from_storage0("BOOT");
  if (error != 0) {
    return error;
  }

  error = copy_selected_file_range_to_ram(0u, SCRATCH_ADDR,
                                          FIXED_K16E_V1_HEADER_SIZE);
  if (error != 0) {
    return error;
  }
  error = parse_fixed_k16e_v1(K16E_ABI_KIND_BOOTLOADER,
                              read_u32(STATE_INODE_SIZE_BYTES), entry_pc,
                              &load_addr, &file_size, &memory_size);
  if (error != 0) {
    return error;
  }
  error = copy_selected_file_range_to_ram(FIXED_K16E_V1_PAYLOAD_OFFSET,
                                          load_addr, file_size);
  if (error != 0) {
    return error;
  }
  zero_fill_ram(load_addr + file_size, memory_size - file_size);
  return 0;
}

static void enter_loaded_image(u32 entry_pc) {
  void (*entry)(void) = (void (*)(void))entry_pc;
  entry();
  halt_forever();
}

static void set_halted(int code) {
  write_i32(CONTROL_PANIC_CODE, code);
  write_i32(CONTROL_STATUS, STATUS_HALTED);
}

void _start(void) {
  u32 entry_pc = 0;
  int error;
  write_i32(CONTROL_STATUS, STATUS_BOOTING);
  clear_display();
  print_bios_banner();
  debug_print("K16 BIOS\n");
  sleep_ticks(20);

  error = load_k16e_from_storage0(&entry_pc);
  if (error == 0) {
    enter_loaded_image(entry_pc);
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
