# RUX Storage Volume v1

## Status

Status: experimental.

`RUXVOL` is the current block-media container used by `storage0` test and
tooling flows. The VM exposes the payload bytes as the storage device media;
the 16-byte host file header is not visible to the guest.

All multi-byte fields are little-endian.

## Host File Header

```text
offset  size  name
0x00    6     magic
0x06    2     version
0x08    8     payload_size
```

Field values for v1:

```text
magic    "RUXVOL"
version  1
```

`payload_size` is the exact number of guest-visible media bytes after the host
file header. The file length must be `16 + payload_size`.

## Guest-Visible Layout

The current layout reserves fixed media LBAs for the BIOS-to-bootloader and
bootloader-to-kernel chain:

```text
LBA 0   RUXB bootloader record
LBA 1   bootloader payload bytes
LBA 16  RUXK kernel record
LBA 17  kernel payload bytes
```

Each LBA is 512 bytes. Tooling must reject artifacts that do not fit these
fixed regions. It must not relocate records or payloads implicitly.

## RUXB Bootloader Record

`rux volume put-boot` accepts only `RUXE` artifacts with ABI kind `bootloader`
and writes this record at LBA 0.

```text
offset  size  name
0x00    4     magic
0x04    4     entry_pc
0x08    4     load_addr
0x0C    4     block_count
0x10    4     start_lba
```

Field values:

```text
magic      "RUXB"
start_lba  1
```

The bootloader payload bytes are copied from the `RUXE` load section to LBA 1.

## RUXK Kernel Record

`rux volume put-kernel` accepts only `RUXE` artifacts with ABI kind `kernel`
and writes this record at LBA 16.

```text
offset  size  name
0x00    4     magic
0x04    4     entry_pc
0x08    4     load_addr
0x0C    4     block_count
0x10    4     start_lba
```

Field values:

```text
magic      "RUXK"
start_lba  17
```

The kernel payload bytes are copied from the `RUXE` load section to LBA 17.

## Boot Chain

The current boot chain is:

1. BIOS reads `RUXB` from LBA 0.
2. BIOS copies the bootloader payload from LBA 1 to `load_addr`.
3. BIOS jumps to `entry_pc`.
4. Bootloader reads `RUXK` from LBA 16.
5. Bootloader copies the kernel payload from LBA 17 to `load_addr`.
6. Bootloader jumps to `entry_pc`.

This is a fixed pre-filesystem layout. Future filesystem-backed loading should
replace the fixed `RUXK` location rather than adding fallback guesses.
