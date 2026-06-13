#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ARTIFACT="$ROOT/modules/v1_21_1/v1_21_1-neoforge/build/generated/k16-firmware-artifacts/display-ok.kx"
ARTIFACT="${1:-$DEFAULT_ARTIFACT}"

KERNEL_LOAD_ADDR=0x00004000
KERNEL_LIMIT_BYTES=16384
MIN_HEADROOM_BYTES="${K16_KERNEL_MIN_HEADROOM_BYTES:-1024}"

K16E_HEADER_SIZE=32
K16E_SECTION_TABLE_OFFSET=32
K16E_SECTION_RECORD_OFFSET=32
K16E_PAYLOAD_OFFSET=52
K16E_ABI_KIND_KERNEL=2
K16E_SECTION_KIND_LOAD=1

fail() {
    printf 'error=%s\n' "$*" >&2
    exit 1
}

read_u16() {
    od -An -tu2 -j "$2" -N 2 "$1" | tr -d '[:space:]'
}

read_u32() {
    od -An -tu4 -j "$2" -N 4 "$1" | tr -d '[:space:]'
}

read_magic() {
    LC_ALL=C dd if="$1" bs=1 count=4 status=none
}

[[ -f "$ARTIFACT" ]] || fail "artifact not found: $ARTIFACT"
[[ "$(read_magic "$ARTIFACT")" == "K16E" ]] || fail "invalid K16E magic"

version="$(read_u16 "$ARTIFACT" 4)"
header_size="$(read_u16 "$ARTIFACT" 6)"
section_table_offset="$(read_u32 "$ARTIFACT" 16)"
section_count="$(read_u32 "$ARTIFACT" 20)"
abi_kind="$(read_u32 "$ARTIFACT" 24)"

[[ "$version" == "1" ]] || fail "unsupported K16E version: $version"
[[ "$header_size" == "$K16E_HEADER_SIZE" ]] || fail "unsupported K16E header size: $header_size"
[[ "$section_table_offset" == "$K16E_SECTION_TABLE_OFFSET" ]] ||
    fail "unsupported K16E section table offset: $section_table_offset"
[[ "$section_count" == "1" ]] || fail "unsupported K16E section count: $section_count"
[[ "$abi_kind" == "$K16E_ABI_KIND_KERNEL" ]] || fail "expected kernel ABI kind $K16E_ABI_KIND_KERNEL, got $abi_kind"

section_kind="$(read_u32 "$ARTIFACT" "$K16E_SECTION_RECORD_OFFSET")"
load_addr="$(read_u32 "$ARTIFACT" 36)"
file_offset="$(read_u32 "$ARTIFACT" 40)"
payload_bytes="$(read_u32 "$ARTIFACT" 44)"
memory_size="$(read_u32 "$ARTIFACT" 48)"

[[ "$section_kind" == "$K16E_SECTION_KIND_LOAD" ]] || fail "unsupported K16E section kind: $section_kind"
[[ "$load_addr" == "$((KERNEL_LOAD_ADDR))" ]] ||
    fail "expected load_addr $KERNEL_LOAD_ADDR, got 0x$(printf '%08x' "$load_addr")"
[[ "$file_offset" == "$K16E_PAYLOAD_OFFSET" ]] || fail "expected payload offset $K16E_PAYLOAD_OFFSET, got $file_offset"
[[ "$memory_size" == "$payload_bytes" ]] || fail "memory size $memory_size differs from payload bytes $payload_bytes"

headroom_bytes=$((KERNEL_LIMIT_BYTES - payload_bytes))

printf 'artifact=%s\n' "$ARTIFACT"
printf 'abi=kernel\n'
printf 'load_addr=0x%08x\n' "$load_addr"
printf 'payload_bytes=%s\n' "$payload_bytes"
printf 'limit_bytes=%s\n' "$KERNEL_LIMIT_BYTES"
printf 'headroom_bytes=%s\n' "$headroom_bytes"
printf 'min_headroom_bytes=%s\n' "$MIN_HEADROOM_BYTES"

if (( payload_bytes > KERNEL_LIMIT_BYTES )); then
    printf 'status=OVERSIZED\n'
    exit 1
fi

if (( headroom_bytes < MIN_HEADROOM_BYTES )); then
    printf 'status=LOW_HEADROOM\n'
    exit 1
fi

printf 'status=OK\n'
