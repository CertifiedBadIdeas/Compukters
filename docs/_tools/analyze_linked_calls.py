#!/usr/bin/env python3
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Read-only call inventory and conservative reachability models for linked CPKT v3."""
import argparse
from collections import Counter, defaultdict
from functools import lru_cache
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
from analyze_artifact_size import analyze as check_container, indexed, require

CALLS = {0x40: 'direct', 0x41: 'virtual', 0x42: 'interface', 0xe5: 'suspend', 0x50: 'spawn'}
NONE = 0xffffffff


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        require(offset < len(data), 'truncated ULEB')
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            require(value <= NONE, 'ULEB overflow')
            return value, offset
    raise ValueError('ULEB overflow')


def inventory(path):
    checked = check_container(path)
    data = path.read_bytes()
    tables = {}
    for i in range(struct.unpack_from('<I', data, 16)[0]):
        kind, _, scope, offset, length, count, _ = struct.unpack_from('<HHIQQII', data, 64 + i * 32)
        if kind in {2, 0x100, 0x101, 0x103, 0x104, 0x106, 0x107, 0x108, 0x109, 0x10b, 0x110, 0x8001}:
            tables[0 if kind == 2 else scope - 1, kind] = indexed(data[offset:offset + length], count)
    modules = tables[0, 2]
    strings = {m: [r.decode('utf-8') for r in tables[m, 0x100]] for m in range(len(modules))}
    names = {m: strings[m][struct.unpack_from('<I', r)[0]] for m, r in enumerate(modules)}

    @lru_cache(None)
    def resolve(m, ref, kind):
        if ref & 0x80000000 == 0:
            return m, ref
        row = tables[m, 0x103][ref & 0x7fffffff]
        require(row[0] == kind, 'import kind mismatch')
        target, name, signature = struct.unpack_from('<III', row, 4)
        require(row[16:48] == modules[target][8:40], 'import module hash mismatch')
        matching = [r for r in tables[target, 0x104]
                    if r[0] == kind and strings[target][struct.unpack_from('<I', r, 4)[0]] == strings[m][name]]
        if kind == 1:
            matching = [r for r in matching if signature_key(m, signature) == signature_key(target, struct.unpack_from('<I', r, 12)[0])]
        require(len(matching) == 1, f'ambiguous import {m}:{ref}: {len(matching)}')
        return target, struct.unpack_from('<I', matching[0], 8)[0]

    def value_key(m, row):
        kind, nullable, reserved, nominal = struct.unpack('<BBHI', row)
        require(reserved == 0, 'value reserved bits')
        identity = None
        if kind in {7, 8}:
            identity = resolve(m, nominal, 0)
            ty = tables[identity[0], 0x101][identity[1]]
            if ty[0] == 2:
                identity = ('array', ty[1] >> 1, value_key(identity[0], ty[8:16]))
        return kind, nullable, identity

    def signature_key(m, ref):
        owner, index = resolve(m, ref, 0)
        row = tables[owner, 0x101][index]
        require(row[0] == 3, 'function signature is not a function type')
        count, suspend = struct.unpack_from('<HH', row, 8)
        require(len(row) == 20 + 8 * count, 'function signature size')
        return (suspend, value_key(owner, row[12:20]),
                tuple(value_key(owner, row[20 + i * 8:28 + i * 8]) for i in range(count)))

    functions, initializers = {}, set()
    for m in names:
        for i, row in enumerate(tables[m, 0x101]):
            if row[0] == 0:
                interfaces = struct.unpack_from('<I', row, 12)[0]
                normal_length = 32 + 4 * interfaces
                if len(row) == normal_length + 4:
                    initializers.add((m, struct.unpack_from('<I', row, normal_length)[0]))
                else:
                    require(len(row) == normal_length, 'class record size')
        for i, row in enumerate(tables[m, 0x106]):
            owner, name, signature, flags, values, params, first, blocks, _, _ = struct.unpack_from('<4I2H4I', row)
            layouts, pos = [], 36
            for _ in range(values):
                kind, nullable, reserved, nominal = struct.unpack_from('<BBHI', row, pos)
                components, padding = struct.unpack_from('<HH', row, pos + 8)
                require(reserved == padding == 0, 'value layout reserved bits')
                layouts.append((kind, nullable, nominal))
                pos += 12 + components
            require(pos == len(row), 'function record size')
            owner_key = None if owner == NONE else resolve(m, owner, 0)
            functions[m, i] = dict(name=strings[m][name], flags=flags, params=params, first=first,
                                   blocks=blocks, owner=owner_key, values=layouts, bytes=len(row))

    calls, edges, declarations = [], defaultdict(set), set()
    counts = Counter()
    for key, fn in functions.items():
        m, _ = key
        for block in range(fn['first'], fn['first'] + fn['blocks']):
            row = tables[m, 0x108][block]
            owner, code, expected = struct.unpack_from('<III', tables[m, 0x107][block])
            require(owner == key[1] and code == block, 'block owner/index mismatch')
            pos, number = 0, 0
            while pos < len(row):
                opcode, form, length = struct.unpack_from('<BBH', row, pos)
                require(length >= 4 and pos + length <= len(row), 'instruction frame bounds')
                operand = row[pos + 4:pos + length]
                if opcode in CALLS:
                    require(form == 0, 'unsupported call form')
                    ref, cursor = uleb(operand, 2)
                    target = resolve(m, ref, 1)
                    require(target in functions, 'call target missing')
                    arguments, cursor = uleb(operand, cursor)
                    require(cursor + arguments * 2 + (0 if opcode != 0xe5 else 1) <= len(operand), 'call arguments bounds')
                    registers = struct.unpack_from('<' + str(arguments) + 'H', operand, cursor)
                    cursor += 2 * arguments
                    if opcode == 0xe5:
                        _, cursor = uleb(operand, cursor)
                    require(cursor == len(operand), 'trailing call operands')
                    counts[CALLS[opcode]] += 1
                    edges[key].add(target)
                    item = dict(caller=list(key), block=block, instruction=number, kind=CALLS[opcode],
                                target=list(target), target_name=functions[target]['name'])
                    if opcode in {0x41, 0x42}:
                        declarations.add(target)
                        target_owner = functions[target]['owner']
                        ty = None if target_owner is None else tables[target_owner[0], 0x101][target_owner[1]]
                        item['final_target_owner'] = bool(ty and ty[0] == 0 and ty[1] & 2 and not functions[target]['flags'] & 8)
                        require(registers, 'dynamic call lacks receiver')
                        kind, nullable, nominal = fn['values'][registers[0]]
                        item['receiver_nonnull'] = kind == 7 and nullable == 0
                        receiver = resolve(m, nominal, 0) if kind == 7 else None
                        rt = None if receiver is None else tables[receiver[0], 0x101][receiver[1]]
                        item['final_receiver'] = bool(rt and rt[0] == 0 and rt[1] & 2)
                    calls.append(item)
                pos += length
                number += 1
            require(number == expected, 'instruction count mismatch')

    # Debug inline parents can keep another function's metadata reachable.
    for m in names:
        rows = tables.get((m, 0x110), [])
        for row in rows:
            owner = struct.unpack_from('<I', row)[0]
            parent = struct.unpack_from('<I', row, 20)[0]
            if parent != NONE:
                require(parent < len(rows), 'debug inline parent missing')
                parent_owner = struct.unpack_from('<I', rows[parent])[0]
                edges[m, owner].add((m, parent_owner))

    # Deliberately overapproximate dispatch: ignore receiver ancestry and signature
    # details except name and arity. Even syntactically dead dynamic sites contribute.
    def shape(key):
        return functions[key]['name'], functions[key]['params']

    declared = {k for k, f in functions.items() if f['flags'] & 4 or
                (f['owner'] is not None and tables[f['owner'][0], 0x101][f['owner'][1]][0] == 1)}
    entry = tuple(struct.unpack_from('<II', data, 40))

    def closure(dispatch_declarations):
        shapes = {shape(k) for k in dispatch_declarations}
        retained = {k for k in functions if shape(k) in shapes} | initializers | {entry}
        pending = list(retained)
        while pending:
            current = pending.pop()
            targets = set(edges[current])
            # Admission builds dispatch for every retained virtual/interface
            # declaration, even when only a direct call keeps it alive.
            if current in declared:
                targets.update(k for k in functions if shape(k) == shape(current))
            for target in targets - retained:
                retained.add(target)
                pending.append(target)
        return retained

    def describe_unretained(retained):
        removed = set(functions) - retained
        # Account only owned payload/index entries. Shared strings, types, constants,
        # imports, paths, alignment and module/directory changes are excluded.
        payload = Counter()
        for m, f in removed:
            fn = functions[m, f]
            payload['FUNCTIONS'] += fn['bytes'] + 4
            for b in range(fn['first'], fn['first'] + fn['blocks']):
                for kind, name in [(0x107, 'BLOCKS'), (0x108, 'CODE')]:
                    payload[name] += len(tables[m, kind][b]) + 4
        debug_ids = set()
        for m in names:
            for kind, name in [(0x109, 'EXCEPTIONS'), (0x10b, 'SAFEPOINT_ROOTS'), (0x110, 'DEBUG')]:
                for i, row in enumerate(tables.get((m, kind), [])):
                    if (m, struct.unpack_from('<I', row)[0]) in removed:
                        payload[name] += len(row) + 4
                        if kind == 0x110:
                            debug_ids.add((m, i))
            for row in tables.get((m, 0x8001), []):
                if (m, struct.unpack_from('<I', row)[0]) in debug_ids:
                    payload['DEBUG_SOURCE_POSITIONS'] += len(row) + 4
        return dict(functions=len(removed), owned_payload_and_index_bytes=sum(payload.values()),
                    by_section=dict(payload), by_module=dict(Counter(names[m] for m, _ in removed)),
                    examples=[dict(module=names[m], function=f, name=functions[m, f]['name'], flags=functions[m, f]['flags'])
                              for m, f in sorted(removed)][:30])

    dynamic = [c for c in calls if c['kind'] in {'virtual', 'interface'}]
    owner_final = [c for c in dynamic if c['final_target_owner'] and c['receiver_nonnull']]
    receiver_final = [c for c in dynamic if c['final_receiver'] and c['receiver_nonnull']]
    return dict(path=str(path), sha256=checked['sha256'], artifact_bytes=len(data), functions=len(functions),
                functions_by_module=dict(Counter(names[m] for m, _ in functions)), call_sites=dict(counts),
                final_owner_nonnull_dynamic_sites=len(owner_final), final_receiver_nonnull_dynamic_sites=len(receiver_final),
                final_owner_targets=dict(Counter(c['target_name'] for c in owner_final)),
                final_receiver_targets=dict(Counter(c['target_name'] for c in receiver_final)),
                preserved_dispatch_declarations_model=describe_unretained(closure(declared)),
                callsite_dispatch_model=describe_unretained(closure(declarations)),
                notes='Static call sites, not execution frequency. Models overapproximate dynamic targets by name/arity. They are investigation estimates, not a linker/verifier proof or exact artifact savings. Shared metadata pruning is not modeled; debug parent owners are retained; callsite model requires coordinated pruning of unused declarations and method ranges; retained declarations keep all name/arity candidates.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps([inventory(path) for path in args.artifacts], indent=2))


if __name__ == '__main__':
    main()
