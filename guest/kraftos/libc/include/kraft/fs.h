#ifndef KRAFT_FS_H
#define KRAFT_FS_H

#define KRAFT_FILE_TYPE_REGULAR 1
#define KRAFT_FILE_TYPE_DIRECTORY 2
#define KRAFT_ERROR_READ_ONLY 0xffffffe2u

#define KRAFT_READ_DIR_REQUEST_MAGIC 0x52494452u
#define KRAFT_RENAME_REQUEST_MAGIC 0x4d414e52u
#define KRAFT_MAX_READ_DIR_PATH_BYTES 228
#define KRAFT_MAX_READ_DIR_REQUEST_BYTES 244
#define KRAFT_READ_DIR_ENTRY_FILE_TYPE_OFFSET 0
#define KRAFT_READ_DIR_ENTRY_NAME_LEN_OFFSET 4
#define KRAFT_READ_DIR_ENTRY_NAME_OFFSET 8
#define KRAFT_READ_DIR_ENTRY_SIZE_BYTES 4
#define KRAFT_READ_DIR_ENTRY_FIXED_BYTES 12
#define KRAFT_MAX_STAT_PATH_BYTES 228
#define KRAFT_MAX_RENAME_PATH_BYTES 228
#define KRAFT_MAX_RENAME_REQUEST_BYTES 468
#define KRAFT_STAT_METADATA_BYTES 16

struct kraft_stat {
  unsigned int file_type;
  unsigned int size_bytes;
  unsigned int reserved0;
  unsigned int reserved1;
};

int kraft_read_dir(const char *path, char *out, unsigned int out_len);
int kraft_stat(const char *path, struct kraft_stat *metadata);
int kraft_rename(const char *old_path, const char *new_path);

#define read_dir(path, out, out_len) kraft_read_dir((path), (out), (out_len))
#define stat(path, metadata) kraft_stat((path), (metadata))
#define rename(old_path, new_path) kraft_rename((old_path), (new_path))

#endif
