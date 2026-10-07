#!/usr/bin/env python3
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Read-only expansion of root ranges and exact comparison with #704 hashes.

Run from the repository root after regenerating the recorded inputs.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('legacy', 'docs/benchmarks/debug-paths-704-2026-10-07/verify_legacy_equivalence.py')
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)


def expand(data):
    assert hashlib.sha256(data[:-32]).digest() == data[-32:]
    entries = [struct.unpack_from('<HHIQQII', data, 64 + i * 32) for i in range(struct.unpack_from('<I', data, 16)[0])]
    markers = {}
    for kind, flags, scope, offset, length, count, reserved in entries:
        if kind == 0x112:
            assert flags == 1 and count == 1 and length == 16 and reserved == 0
            version, count, expanded_bytes = struct.unpack_from('<IIQ', data, offset)
            assert version == 1
            markers[scope] = count, expanded_bytes
    sections = []
    for kind, flags, scope, offset, length, count, reserved in entries:
        if kind == 0x112:
            continue
        payload = data[offset:offset + length]
        if kind == 0x10b and scope in markers:
            expanded = []
            for record in legacy.records(payload):
                function, block, first, run, refs, padding = struct.unpack_from('<4I2H', record)
                assert padding == 0 and run > 0 and len(record) == 20 + 4 * refs
                assert len(expanded) + run <= markers[scope][0] <= 1_000_000
                for boundary in range(first, first + run):
                    expanded.append(struct.pack('<3I2H', function, block, boundary, refs, 0) + record[20:])
            payload = legacy.indexed(expanded)
            count = len(expanded)
            assert (count, len(payload)) == markers[scope]
        sections.append((kind, flags, scope, payload, count))
    cursor = 64 + 32 * len(sections)
    directory, body = bytearray(), bytearray()
    for kind, flags, scope, payload, count in sections:
        padding = legacy.align8(cursor) - cursor
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
    baseline = json.loads(Path('docs/benchmarks/debug-paths-704-2026-10-07/size-comparison.json').read_text())
    result = []
    for row in baseline:
        group = 'system' if row['artifact'] in {'boot', 'edit', 'shell', 'kotlinc', 'vmbench', 'vmbench-agent'} else 'conformance'
        path = Path('modules/common/compiler-k2/build/generated') / group / (row['artifact'] + '.cpkt')
        data = path.read_bytes()
        expanded = expand(data)
        assert hashlib.sha256(expanded).hexdigest() == row['after_sha256'], str(path)
        assert hashlib.sha256(legacy.legacy_equivalent(expanded)).hexdigest() == row['legacy_equivalent_sha256'], str(path)
        result.append(dict(artifact=row['artifact'], before_bytes=row['after_bytes'], after_bytes=len(data),
                           saved_bytes=row['after_bytes'] - len(data), before_sha256=row['after_sha256'],
                           after_sha256=hashlib.sha256(data).hexdigest(), expanded_legacy_sha256=row['legacy_equivalent_sha256']))
    print(json.dumps(result, indent=2))
