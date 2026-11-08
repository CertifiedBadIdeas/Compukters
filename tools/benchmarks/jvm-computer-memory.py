#!/usr/bin/env python3
"""Sample settled production-host populations in fresh JVMs, outside the Gradle process.

Requires Linux, JDK 25 jcmd, and the classpath from :core:prepareJvmMemoryProbe.
Each phase is a stdin barrier: class_histogram performs full GC before NMT and smaps.
Native Rust malloc is absent from NMT; never add NMT and RSS as independent totals.
"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

from jvm_memory_metrics import histogram_footprint


def run(command, output):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    output.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"Command failed: {command}; see {output}")
    return result.stdout


def trial(args, name, classpath_file, count, directory):
    directory.mkdir(parents=True)
    command = [str(args.jdk / 'bin/java'), '-Xms128m', '-Xmx1g', '-XX:+UseSerialGC',
               '-XX:NativeMemoryTracking=summary', '--enable-native-access=ALL-UNNAMED',
               '--illegal-native-access=deny', '-cp', classpath_file.read_text().strip(),
               'ru.lazyhat.compukters.core.device.runtime.memory.JvmComputerMemoryProbe',
               str(count), str(args.library), str(args.image), str(directory / 'store')]
    (directory / 'command.json').write_text(json.dumps(command, indent=2))
    rows = []
    started = time.monotonic()
    with (directory / 'stderr.log').open('w') as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True)
        try:
            for line in process.stdout:
                with (directory / 'stdout.log').open('a') as log:
                    log.write(line)
                words = line.rstrip().split('\t')
                if words[0] == 'PHASE':
                    phase = words[1]
                    assert phase not in {row['phase'] for row in rows}
                    jcmd = [str(args.jdk / 'bin/jcmd'), str(process.pid)]
                    histogram = run(jcmd + ['GC.class_histogram'], directory / f'{phase}-histogram.txt')
                    run(jcmd + ['VM.native_memory', 'summary', 'scale=KB'], directory / f'{phase}-nmt.txt')
                    smaps = Path(f'/proc/{process.pid}/smaps_rollup').read_text()
                    (directory / f'{phase}-smaps.txt').write_text(smaps)
                    resident = {k: int(v) * 1024 for k, v in re.findall(r'^(Rss|Pss|Private_Clean|Private_Dirty):\s+(\d+) kB', smaps, re.M)}
                    footprint = histogram_footprint(histogram)
                    keys = ['hosts', 'available', 'guest_heap_used', 'guest_heap_quota', 'mutable_quota',
                            'filesystem_logical_bytes', 'filesystem_nodes', 'fixed_units', 'instructions']
                    row = dict(variant=name, population=count, phase=phase, elapsed_s=time.monotonic() - started,
                               **footprint, **resident,
                               **dict(zip(keys, map(int, words[2:]), strict=True)))
                    rows.append(row)
                    process.stdin.write('continue\n')
                    process.stdin.flush()
                elif words[0] == 'HEAP':
                    assert rows[-1]['phase'] == words[1]
                    rows[-1].update(heap_used=int(words[2]), heap_committed=int(words[3]))
            assert process.wait(timeout=30) == 0, (directory / 'stderr.log').read_text()
            assert [row['phase'] for row in rows] == ['baseline', 'hosts', 'admitted', 'prompt', 'observed', 'resumed', 'closed', 'dropped', 'store_closed']
            (directory / 'rows.json').write_text(json.dumps(rows, indent=2))
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', action='append', required=True, help='name=classpath-file; repeat for a paired comparison')
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--image', type=Path, required=True, help='Production CPKTSYS distribution image')
    parser.add_argument('--output', type=Path, required=True, help='Fresh evidence directory')
    parser.add_argument('--jdk', type=Path, default=Path('/usr/lib/jvm/java-25-openjdk'))
    parser.add_argument('--populations', default='100,500,1000')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--lock', action='append', type=Path, default=[], help='Existing managed-build lock; EX held over every trial')
    args = parser.parse_args()
    assert 1 <= args.rounds <= 20
    populations = [int(value) for value in args.populations.split(',')]
    assert all(1 <= value <= 4096 for value in populations)
    variants = [value.split('=', 1) for value in args.variant]
    assert len({name for name, _ in variants}) == len(variants)
    args.output = args.output.resolve()
    args.library = args.library.resolve(strict=True)
    args.image = args.image.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=False)
    locks = []
    try:
        for path in args.lock:
            lock = path.open('a+')
            print(f'Waiting for build lock: {path}', flush=True)
            fcntl.flock(lock, fcntl.LOCK_EX)
            locks.append(lock)
        metadata = dict(rounds=args.rounds, populations=populations, variants=variants,
                        image_sha256=hashlib.sha256(args.image.read_bytes()).hexdigest(),
                        library_sha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
                        jvm='JDK25, SerialGC, Xms128m, Xmx1g, NMT summary', locks=[str(p) for p in args.lock])
        (args.output / 'provenance.json').write_text(json.dumps(metadata, indent=2))
        results = []
        for round_index in range(1, args.rounds + 1):
            for count in populations[::1 if round_index % 2 else -1]:
                for name, classpath in variants[::1 if (round_index + populations.index(count)) % 2 else -1]:
                    print(f'round={round_index} population={count} variant={name}', flush=True)
                    directory = args.output / f'round-{round_index}' / str(count) / name
                    results.extend(trial(args, name, Path(classpath), count, directory))
                    (args.output / 'rows.json').write_text(json.dumps(results, indent=2))
        print(f'Completed {len(results) // 9} fresh JVMs, {len(results)} phase snapshots', flush=True)
    finally:
        for lock in reversed(locks):
            lock.close()


if __name__ == '__main__':
    main()
