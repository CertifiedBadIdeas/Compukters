#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# Licensed under the Apache License, Version 2.0.
"""Generate compiled variants using CompileProbe.java from the specialization report."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def collection_sum(seed):
    position = [i + seed for i in range(64)]
    velocity = [i % 7 + 1 for i in range(64)]
    totals = [0] * 64
    checksum = 0
    for _ in range(1024):
        for i in range(64):
            position[i] += velocity[i]
            velocity[i] = (velocity[i] + 1) & 7
            totals[i] += position[i]
            checksum = (checksum + totals[i] * (i % 5 + 1)) & 0xffffffff
    return checksum - (1 << 32) if checksum >= 1 << 31 else checksum


def vector_sum(seed):
    target = [10. + seed, 20., -5.]
    position, velocity, integral, previous = ([0.] * 3 for _ in range(4))
    for _ in range(20000):
        error = [target[i] - position[i] for i in range(3)]
        integral = [integral[i] + error[i] * .125 for i in range(3)]
        derivative = [(error[i] - previous[i]) * 8. for i in range(3)]
        force = [(error[i] * .5 + integral[i] * .01) + derivative[i] * .125 for i in range(3)]
        velocity = [velocity[i] * .875 + force[i] * .125 for i in range(3)]
        position = [position[i] + velocity[i] * .125 for i in range(3)]
        previous = error
    return int(((((position[0] + position[1]) + position[2]) + velocity[0]) + velocity[1] + velocity[2]) * 1000000.)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'collections', 'vec3', 'compiler-classpath', 'worker-jar', 'probe-classpath']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = args.output.resolve()
    output = root / 'inputs'
    output.mkdir(parents=True, exist_ok=False)
    collection, vector = args.collections.read_text(), args.vec3.read_text()
    assert collection.count('Particle(index,') == 1
    assert vector.count('Vec3(10.0, 20.0, -5.0)') == 1
    rows = []
    commands = []
    for seed in range(16):
        variants = [
            ('collections', collection.replace('Particle(index,', f'Particle(index + {seed},'), collection_sum(seed)),
            ('vec3', vector.replace('Vec3(10.0, 20.0, -5.0)', f'Vec3({10 + seed}.0, 20.0, -5.0)'), vector_sum(seed)),
        ]
        for name, source, checksum in variants:
            case = f'{name}-{seed:02}'
            src, destination = output / f'{case}.kt', root / 'compilations' / case
            src.write_text(source)
            command = [args.java, '-Xms128m', '-Xmx2g',
                '-Dcompukters.worker.jar=' + str(args.worker_jar.resolve()),
                '-cp', str(args.probe_classpath.resolve()) + ':' + args.compiler_classpath.read_text().strip(),
                'CompileProbe', str(src), str(destination), '1']
            with (output / f'{case}.compile.log').open('w') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
            if (destination / 'failure.txt').exists():
                raise RuntimeError((destination / 'failure.txt').read_text())
            shutil.copy2(destination / 'program.cpkt', output / f'{case}.cpkt')
            rows.append((case, checksum))
            commands.append(command)
            print(case, checksum, flush=True)
    (output / 'manifest.tsv').write_text('id\tchecksum\n' + ''.join(f'{case}\t{checksum}\n' for case, checksum in rows))
    (root / 'inputs-sha256.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(output.iterdir()) if p.suffix in ['.kt', '.cpkt', '.tsv']}, indent=2) + '\n')
    (root / 'compiler-commands.json').write_text(json.dumps(commands, indent=2) + '\n')


if __name__ == '__main__':
    main()
