#!/usr/bin/env python3
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Read-only size accounting for CPKT v3; estimates are not a new codec."""

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import struct


NAMES = {
    1: 'MANIFEST', 2: 'MODULES', 3: 'CAPABILITIES', 0x100: 'STRINGS',
    0x101: 'TYPES', 0x102: 'CONSTANTS', 0x103: 'IMPORTS', 0x104: 'EXPORTS',
    0x105: 'FIELDS', 0x106: 'FUNCTIONS', 0x107: 'BLOCKS', 0x108: 'CODE',
    0x109: 'EXCEPTIONS', 0x10a: 'UTF16_LITERALS', 0x10b: 'SAFEPOINT_ROOTS',
    0x110: 'DEBUG', 0x111: 'DEBUG_PATHS', 0x8001: 'DEBUG_SOURCE_POSITIONS',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def align8(value):
    return (value + 7) & ~7


def indexed(payload, expected_count):
    require(len(payload) >= 24, 'indexed section too short')
    count, reserved, length = struct.unpack_from('<IIQ', payload)
    require(count == expected_count and reserved == 0, 'indexed header mismatch')
    start = align8(16 + 4 * (count + 1))
    require(start <= len(payload) and start + length == len(payload), 'indexed length mismatch')
    offsets = struct.unpack_from('<' + str(count + 1) + 'I', payload, 16)
    require(offsets[0] == 0 and offsets[-1] == length, 'indexed endpoints mismatch')
    require(all(a <= b for a, b in zip(offsets, offsets[1:])), 'unordered indexed offsets')
    return [payload[start + a:start + b] for a, b in zip(offsets, offsets[1:])]


def analyze(path):
    data = path.read_bytes()
    require(len(data) >= 96 and data[:4] == b'CPKT', 'not a CPKT container')
    major, minor, abi_major, abi_minor, header, stride = struct.unpack_from('<6H', data, 4)
    require(major == 3 and header == 64 and stride == 32, 'unsupported CPKT layout')
    count = struct.unpack_from('<I', data, 16)[0]
    directory, payload_end = struct.unpack_from('<QQ', data, 24)
    require(directory == 64 and payload_end + 32 == len(data), 'container length mismatch')
    require(64 + 32 * count <= payload_end, 'directory out of bounds')
    require(hashlib.sha256(data[:payload_end]).digest() == data[payload_end:], 'digest mismatch')
    sections, tables = [], {}
    previous_end = align8(64 + 32 * count)
    for i in range(count):
        kind, flags, scope, offset, length, elements, reserved = struct.unpack_from('<HHIQQII', data, 64 + i * 32)
        require(reserved == 0 and offset >= previous_end and offset % 8 == 0, 'invalid section range')
        require(offset + length <= payload_end, 'section out of bounds')
        previous_end = offset + length
        name = NAMES.get(kind, hex(kind))
        sections.append(dict(kind=name, scope=scope, flags=flags, bytes=length, records=elements))
        if kind in {0x110, 0x111, 0x8001, 0x10b}:
            tables[(scope, kind)] = indexed(data[offset:offset + length], elements)
    sizes, records = Counter(), Counter()
    for section in sections:
        sizes[section['kind']] += section['bytes']
        records[section['kind']] += section['records']

    paths = Counter()
    path_bytes = intern_saving = debug_coalesce_saving = 0
    debug_coalesce_count = root_empty = root_duplicate_count = 0
    root_sets = set()
    for scope in {scope for scope, _ in tables}:
        debug = tables.get((scope, 0x110), [])
        positions = {}
        for row in tables.get((scope, 0x8001), []):
            require(len(row) == 12, 'invalid source position record')
            idx, line, column = struct.unpack('<III', row)
            require(idx < len(debug) and idx not in positions, 'invalid debug position index')
            positions[idx] = (line, column)
        pool = tables.get((scope, 0x111))
        if pool is not None:
            require(pool and debug, 'orphan or empty path pool')
            require(len(set(pool)) == len(pool), 'duplicate pool path')
            path_bytes += sum(map(len, pool))
        parsed, scope_paths = [], Counter()
        for row in debug:
            require(len(row) >= 28, 'invalid debug record')
            function, block, instruction, start, end, parent, size = struct.unpack_from('<7I', row)
            if pool is None:
                require(28 + size == len(row), 'invalid debug path length')
                source = row[28:].decode('utf-8')
                path_bytes += size
            else:
                require(len(row) == 28 and size < len(pool), 'invalid debug path ID')
                source = pool[size].decode('utf-8')
            scope_paths[source] += 1
            parsed.append((function, block, instruction, start, end, parent, source))
        paths.update(scope_paths)
        # Model: replace repeated path bytes with a u32 ID (reusing length's
        # field), plus a new indexed UTF-8 pool and one 32-byte directory entry.
        if scope_paths and pool is None:
            pool = align8(16 + 4 * (len(scope_paths) + 1)) + sum(len(p.encode()) for p in scope_paths)
            intern_saving += sum(len(p.encode()) * n for p, n in scope_paths.items()) - pool - 32
        parents = {row[5] for row in parsed if row[5] != 0xffffffff}
        previous = None
        for idx, row in enumerate(parsed):
            signature = (row[0], row[1], *row[3:], positions.get(idx))
            if signature == previous and idx not in parents:
                debug_coalesce_count += 1
                debug_coalesce_saving += len(debug[idx]) + 4
                if idx in positions:
                    debug_coalesce_saving += 16  # 12-byte record + 4-byte index
            else:
                previous = signature
        previous = None
        for row in tables.get((scope, 0x10b), []):
            require(len(row) >= 16, 'invalid root record')
            function, block, instruction, refs, reserved = struct.unpack_from('<IIIHH', row)
            require(reserved == 0 and len(row) == 16 + 4 * refs, 'invalid root components')
            values = tuple(struct.unpack_from('<HH', row, 16 + 4 * i) for i in range(refs))
            root_empty += refs == 0
            root_sets.add((scope, values))
            signature = (function, block, values)
            root_duplicate_count += signature == previous
            previous = signature
    overhead = len(data) - sum(sizes.values())
    require(overhead >= 64 + 32 * count + 32, 'invalid byte accounting')
    return dict(
        path=str(path), sha256=hashlib.sha256(data).hexdigest(), artifact_bytes=len(data),
        format=[major, minor], minimum_runtime_abi=[abi_major, abi_minor],
        entry_module=struct.unpack_from('<I', data, 40)[0],
        sections=sections, section_bytes=dict(sizes), section_records=dict(records),
        header_directory_digest_padding_bytes=overhead,
        debug_path_bytes=path_bytes, debug_unique_paths=len(paths), debug_paths=dict(paths),
        modeled_path_intern_saving_bytes=intern_saving,
        conservative_debug_coalesce_records=debug_coalesce_count,
        modeled_debug_coalesce_saving_bytes=debug_coalesce_saving,
        empty_root_maps=root_empty, distinct_root_sets=len(root_sets),
        consecutive_same_block_root_map_duplicates=root_duplicate_count,
        gzip9_bytes=len(gzip.compress(data, compresslevel=9, mtime=0)),
        estimate_notes='Path pool and debug coalescing estimates are separate and must not be added. Coalescing keeps referenced inline parents; lookup semantics require behavioral validation. gzip9 is an offline storage experiment, not a supported executable format. This tool checks container accounting, not full VM validity.',
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([analyze(path) for path in args.artifacts], indent=2))


if __name__ == '__main__':
    main()
