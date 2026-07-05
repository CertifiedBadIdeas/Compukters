#include "boot_chain.h"

typedef unsigned char u8;
typedef unsigned int u32;

extern void __k16_halt_once(void);

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
#define KFS_INODE_SIZE 64u
#define KFS_DIRECTORY_ENTRY_SIZE 64u
#define KFS_MAX_NAME_BYTES 56u
#define KFS_MAX_INLINE_EXTENTS 4u
#define FIXED_K16E_V1_HEADER_SIZE 52u
#define FIXED_K16E_V1_PAYLOAD_OFFSET 52u

#define ERR_STORAGE_VERSION 10
#define ERR_INVALID_PARTITION_TABLE 11
#define ERR_PARTITION_NOT_FOUND 12
#define ERR_INVALID_FILESYSTEM 13
#define ERR_PATH_NOT_FOUND 14
#define ERR_INVALID_EXECUTABLE 15
#define ERR_STORAGE_TRANSFER 16
#define ERR_STORAGE_BLOCK_SIZE 17
#define ERR_STORAGE_MEDIA 18

static void write_u8(u32 address, u8 value) { *(volatile u8 *)address = value; }

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

static int checked_mul_u32(u32 lhs, u32 rhs, u32 *out) {
  if (lhs != 0u && rhs > 0xffffffffu / lhs) {
    return 0;
  }
  *out = lhs * rhs;
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

static u8 scratch_u8(u32 offset) { return read_u8(SCRATCH_ADDR + offset); }

static u32 scratch_u32(u32 offset) { return read_u32(SCRATCH_ADDR + offset); }

static int validate_extent(u32 start_block, u32 block_count, u32 total_blocks) {
  u32 end;
  if (block_count == 0u || !checked_add_u32(start_block, block_count, &end) ||
      end > total_blocks) {
    return ERR_INVALID_FILESYSTEM;
  }
  return 0;
}

static int validate_storage_device(void) {
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
  return 0;
}

static int read_storage_blocks_to_ram(u32 lba, u32 block_count, u32 dst_addr) {
  u32 bytes_done;
  int error;
  if (block_count == 0u) {
    return 0;
  }
  if (!checked_mul_u32(block_count, BLOCK_SIZE, &bytes_done)) {
    return ERR_STORAGE_TRANSFER;
  }
  error = validate_storage_device();
  if (error != 0) {
    return error;
  }

  write_u32(STORAGE_LBA_LOW, lba);
  write_u32(STORAGE_LBA_HIGH, 0);
  write_u32(STORAGE_BLOCK_COUNT, block_count);
  write_u32(STORAGE_BUFFER_ADDR, dst_addr);
  write_i32(STORAGE_COMMAND, STORAGE_COMMAND_READ_BLOCKS);

  if (read_i32(STORAGE_STATUS) != STORAGE_STATUS_DONE ||
      read_i32(STORAGE_ERROR) != STORAGE_ERROR_NONE ||
      read_u32(STORAGE_BYTES_DONE) != bytes_done) {
    return ERR_STORAGE_TRANSFER;
  }
  return 0;
}

static int read_storage_block(u32 lba) {
  return read_storage_blocks_to_ram(lba, 1u, SCRATCH_ADDR);
}

static int read_fs_block(u32 block) {
  u32 lba;
  if (block >= read_u32(STATE_PARTITION_BLOCK_COUNT) ||
      !checked_add_u32(read_u32(STATE_PARTITION_START_LBA), block, &lba)) {
    return ERR_INVALID_FILESYSTEM;
  }
  return read_storage_block(lba);
}

static int read_fs_blocks_to_ram(u32 start_block, u32 block_count,
                                 u32 dst_addr) {
  u32 end_block;
  u32 lba;
  if (block_count == 0u) {
    return 0;
  }
  if (!checked_add_u32(start_block, block_count, &end_block) ||
      end_block > read_u32(STATE_PARTITION_BLOCK_COUNT) ||
      !checked_add_u32(read_u32(STATE_PARTITION_START_LBA), start_block,
                       &lba)) {
    return ERR_INVALID_FILESYSTEM;
  }
  return read_storage_blocks_to_ram(lba, block_count, dst_addr);
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
  u32 inodes_per_block = BLOCK_SIZE / KFS_INODE_SIZE;
  u32 inode_capacity =
      read_u32(STATE_SUPERBLOCK_INODE_TABLE_BLOCK_COUNT) * inodes_per_block;
  if (inode_id >= inode_capacity) {
    return ERR_INVALID_FILESYSTEM;
  }

  u32 inode_block = read_u32(STATE_SUPERBLOCK_INODE_TABLE_START_BLOCK) +
                    inode_id / inodes_per_block;
  u32 inode_offset = (inode_id % inodes_per_block) * KFS_INODE_SIZE;
  int error = read_fs_block(inode_block);
  if (error != 0) {
    return error;
  }

  u8 extent_count = scratch_u8(inode_offset + 0x10u);
  if (scratch_u32(inode_offset + 0x0cu) != 0u ||
      extent_count > KFS_MAX_INLINE_EXTENTS) {
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
  if (!scratch_eq(0, "KFS\0\0", 5) || scratch_u8(5) != 1u ||
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
  if (name_len == 0u || name_len > KFS_MAX_NAME_BYTES ||
      read_u32(STATE_INODE_SIZE_BYTES) % KFS_DIRECTORY_ENTRY_SIZE != 0u) {
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
          if (entry_name_len == 0u || entry_name_len > KFS_MAX_NAME_BYTES ||
              scratch_u8(offset + 2u) != 0u ||
              scratch_u8(offset + 3u) != 0u) {
            return ERR_INVALID_FILESYSTEM;
          }
          if ((u32)entry_name_len == name_len &&
              scratch_eq(offset + 8u, name, name_len)) {
            *inode_id = scratch_u32(offset + 4u);
            return 0;
          }
        } else if (state != 0u && state != 2u) {
          return ERR_INVALID_FILESYSTEM;
        }
        remaining -= KFS_DIRECTORY_ENTRY_SIZE;
        offset += KFS_DIRECTORY_ENTRY_SIZE;
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

static int find_file_inode(const char *dir_name, u32 dir_name_len,
                           const char *file_name, u32 file_name_len) {
  u32 inode_id = read_u32(STATE_SUPERBLOCK_ROOT_INODE_ID);
  int error = read_inode(inode_id);
  if (error != 0) {
    return error;
  }
  if ((u8)read_u32(STATE_INODE_STATE) != 2u) {
    return ERR_PATH_NOT_FOUND;
  }

  error = find_directory_entry(dir_name, dir_name_len, &inode_id);
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

  error = find_directory_entry(file_name, file_name_len, &inode_id);
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
        if (block_offset == 0u) {
          u32 full_block_count = (copy_end - cursor) / BLOCK_SIZE;
          if (full_block_count > 0u) {
            u32 batch_bytes;
            u32 block;
            u32 dst;
            if (!checked_mul_u32(full_block_count, BLOCK_SIZE, &batch_bytes) ||
                !checked_add_u32(extent_start_block, block_delta, &block) ||
                !checked_add_u32(dst_addr, copied, &dst)) {
              return ERR_INVALID_FILESYSTEM;
            }
            int error = read_fs_blocks_to_ram(block, full_block_count, dst);
            if (error != 0) {
              return error;
            }
            copied += batch_bytes;
            cursor += batch_bytes;
            continue;
          }
        }
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

int load_k16e_from_storage0(const char *partition_type, const char *dir_name,
                            u32 dir_name_len, const char *file_name,
                            u32 file_name_len, u32 expected_abi_kind,
                            struct k16_loaded_image *image) {
  u32 file_size;
  u32 memory_size;
  int error = read_partition(partition_type);
  if (error != 0) {
    return error;
  }
  error = read_superblock();
  if (error != 0) {
    return error;
  }
  error = find_file_inode(dir_name, dir_name_len, file_name, file_name_len);
  if (error != 0) {
    return error;
  }

  error = copy_selected_file_range_to_ram(0u, SCRATCH_ADDR,
                                          FIXED_K16E_V1_HEADER_SIZE);
  if (error != 0) {
    return error;
  }
  error = parse_fixed_k16e_v1(expected_abi_kind,
                              read_u32(STATE_INODE_SIZE_BYTES),
                              &image->entry_pc, &image->load_addr, &file_size,
                              &memory_size);
  if (error != 0) {
    return error;
  }
  error = copy_selected_file_range_to_ram(FIXED_K16E_V1_PAYLOAD_OFFSET,
                                          image->load_addr, file_size);
  if (error != 0) {
    return error;
  }
  zero_fill_ram(image->load_addr + file_size, memory_size - file_size);
  image->load_end = image->load_addr + memory_size;
  return 0;
}

void enter_loaded_image(struct k16_loaded_image image) {
  void (*entry)(void) = (void (*)(void))image.entry_pc;
  entry();
  for (;;) {
    __k16_halt_once();
  }
}
