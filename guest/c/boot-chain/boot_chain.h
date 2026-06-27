#ifndef K16_BOOT_CHAIN_H
#define K16_BOOT_CHAIN_H

#define K16E_ABI_KIND_BOOTLOADER 1u
#define K16E_ABI_KIND_KERNEL 2u

struct k16_loaded_image {
  unsigned int entry_pc;
  unsigned int load_addr;
  unsigned int load_end;
};

int load_k16e_from_storage0(const char *partition_type, const char *dir_name,
                            unsigned int dir_name_len, const char *file_name,
                            unsigned int file_name_len,
                            unsigned int expected_abi_kind,
                            struct k16_loaded_image *image);
void enter_loaded_image(struct k16_loaded_image image);

#endif
