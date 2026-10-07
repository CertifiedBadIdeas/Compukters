#!/usr/bin/env python3
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Expand DEBUG_PATHS in memory and compare with the recorded pre-change SHA-256.

Run from the repository root after regenerating the builtin and collection artifacts.
Inputs are read-only; this checks byte equivalence, not native execution validity.
"""
import hashlib
import json
from pathlib import Path
import struct


def align8(value):
    return (value + 7) & ~7


def records(payload):
    count, reserved, length = struct.unpack_from('<IIQ', payload)
    assert reserved == 0
    start = align8(16 + 4 * (count + 1))
    assert start + length == len(payload)
    offsets = struct.unpack_from('<' + 'I' * (count + 1), payload, 16)
    assert offsets[0] == 0 and offsets[-1] == length
    return [payload[start + a:start + b] for a, b in zip(offsets, offsets[1:])]


def indexed(rows):
    offsets = [0]
    for row in rows:
        offsets.append(offsets[-1] + len(row))
    prefix = struct.pack('<IIQ', len(rows), 0, offsets[-1]) + struct.pack('<' + 'I' * len(offsets), *offsets)
    return prefix + bytes(align8(len(prefix)) - len(prefix)) + b''.join(rows)


def legacy_equivalent(data):
    assert hashlib.sha256(data[:-32]).digest() == data[-32:]
    entries = [struct.unpack_from('<HHIQQII', data, 64 + i * 32) for i in range(struct.unpack_from('<I', data, 16)[0])]
    pools = {scope: records(data[offset:offset + length]) for kind, _, scope, offset, length, _, _ in entries if kind == 0x111}
    sections = []
    for kind, flags, scope, offset, length, count, reserved in entries:
        assert reserved == 0
        payload = data[offset:offset + length]
        if kind == 0x111:
            continue
        if kind == 0x110 and scope in pools:
            rows = []
            for row in records(payload):
                assert len(row) == 28
                path = pools[scope][struct.unpack_from('<I', row, 24)[0]]
                rows.append(row[:24] + struct.pack('<I', len(path)) + path)
            payload = indexed(rows)
        sections.append((kind, flags, scope, payload, count))
    cursor = 64 + 32 * len(sections)
    directory = bytearray()
    body = bytearray()
    for kind, flags, scope, payload, count in sections:
        padding = align8(cursor) - cursor
        body.extend(bytes(padding))
        cursor += padding
        directory.extend(struct.pack('<HHIQQII', kind, flags, scope, cursor, len(payload), count, 0))
        body.extend(payload)
        cursor += len(payload)
    header = bytearray(data[:64])
    struct.pack_into('<I', header, 16, len(sections))
    struct.pack_into('<Q', header, 32, cursor)
    expanded = header + directory + body
    return expanded + hashlib.sha256(expanded).digest()


if __name__ == '__main__':
    baseline = json.loads(Path('docs/benchmarks/optimization-survey-2026-10-07/artifact-size-analysis.json').read_text())
    result = []
    for row in baseline:
        if '/system/' not in row['path'] and Path(row['path']).stem not in {'kotlin-map-not-null', 'kotlin-mutable-list'}:
            continue
        data = Path(row['path']).read_bytes()
        expanded = legacy_equivalent(data)
        assert hashlib.sha256(expanded).hexdigest() == row['sha256'], row['path']
        result.append(dict(artifact=Path(row['path']).stem, before_bytes=row['artifact_bytes'], after_bytes=len(data),
                           saved_bytes=row['artifact_bytes'] - len(data), legacy_equivalent_sha256=row['sha256'],
                           after_sha256=hashlib.sha256(data).hexdigest()))
    print(json.dumps(result, indent=2))
