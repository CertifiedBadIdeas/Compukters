#!/usr/bin/env python3
"""Compare fleet-collections native Guest execution with an already compiled JVM fixture.

Compile unchanged guest/fleet-collections.kt and the two KotlinJvm*.java helpers first.
The classpath must contain these classes and the matching Kotlin stdlib. The agent JAR
needs Premain-Class: KotlinJvmSizeAgent. See the checked-in experiment report for build
commands and interpretation; retained data graphs are not runnable isolated computers.
"""
import argparse
import csv
import fcntl
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def run(command, output):
    started = time.monotonic_ns()
    with output.open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    assert result.returncode == 0, (command, output.read_text()[-4000:])
    return dict(argv=command, process_wall_ns=time.monotonic_ns() - started)


def memory(args, output):
    command = [*args.jvm, '-javaagent:' + str(args.agent),
        '--add-opens=java.base/java.util=ALL-UNNAMED', 'KotlinJvmProbe', 'memory']
    rows = []
    with (output / 'stdout.log').open('w') as log, (output / 'stderr.log').open('w') as errors:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stdin=subprocess.PIPE, stderr=errors, text=True)
        try:
            for line in process.stdout:
                log.write(line); log.flush()
                words = line.rstrip().split('\t')
                if words[0] != 'MEMORY':
                    continue
                population, graph_bytes, objects, root_bytes = map(int, words[1:])
                assert objects == population * 67
                index = len(rows)
                histogram = subprocess.run([str(args.jdk / 'bin/jcmd'), str(process.pid), 'GC.class_histogram'],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
                assert histogram.returncode == 0, histogram.stdout
                (output / f'{index}-{population}-histogram.txt').write_text(histogram.stdout)
                particle = re.search(r'^\s*\d+:\s+(\d+)\s+\d+\s+Particle\s', histogram.stdout, re.M)
                assert (int(particle[1]) if particle else 0) == population * 64
                smaps = Path(f'/proc/{process.pid}/smaps_rollup').read_text()
                (output / f'{index}-{population}-smaps.txt').write_text(smaps)
                resident = {key: int(value) * 1024 for key, value in re.findall(
                    r'^(Rss|Pss|Private_Clean|Private_Dirty):\s+(\d+) kB', smaps, re.M)}
                total = re.search(r'^Total\s+(\d+)\s+(\d+)', histogram.stdout, re.M)
                assert total is not None
                rows.append(dict(population=population, graph_bytes=graph_bytes, graph_objects=objects,
                    roots_bytes=root_bytes, java_live_objects=int(total[1]), java_live_bytes=int(total[2]), **resident))
                process.stdin.write('continue\n'); process.stdin.flush()
            assert process.wait(timeout=30) == 0
        finally:
            if process.poll() is None:
                process.kill(); process.wait()
    assert [x['population'] for x in rows] == [0, 32, 2000, 0]
    save(output / 'memory.json', rows)
    return dict(argv=command, rows=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath', type=Path, required=True)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--lock', type=Path, action='append', default=[])
    parser.add_argument('--affinity', default='6')
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    assert args.rounds > 0
    locks = []
    for path in args.lock:
        stream = path.open('rb'); fcntl.flock(stream, fcntl.LOCK_EX); locks.append(stream)
    args.output.mkdir(parents=True, exist_ok=False)
    # -cp deliberately follows all other JVM options, so the memory-only agent can
    # be inserted without enabling instrumentation during timing.
    args.jvm = ['taskset', '-c', args.affinity, str(args.jdk / 'bin/java'),
        '-Xms128m', '-Xmx2g', '-XX:+UseG1GC', '-cp', args.classpath.read_text().strip()]
    records = []
    save(args.output / 'inputs.json', dict(native_sha256=hashlib.sha256(args.native.read_bytes()).hexdigest(),
        classpath=args.classpath.read_text(), args={k: str(v) for k, v in vars(args).items()}))
    for round_index in range(args.rounds):
        labels = ['A', 'A-repeat'] if round_index % 2 == 0 else ['A-repeat', 'A']
        for label in labels:
            order = ['native', 'jvm'] if (round_index + (label == 'A-repeat')) % 2 == 0 else ['jvm', 'native']
            for arm in order:
                path = args.output / f'round-{round_index + 1}-{label}-{arm}'; path.mkdir()
                if arm == 'jvm':
                    command = [*args.jvm, 'KotlinJvmProbe', 'timing', '20', '21', '500']
                else:
                    command = ['taskset', '-c', args.affinity, str(args.native), str(args.inputs),
                        str(path / 'measurements'), '31', '65536', 'untraced']
                record = dict(round=round_index + 1, label=label, arm=arm, output=str(path),
                    **run(command, path / 'stdout.log'))
                if arm == 'jvm':
                    text = (path / 'stdout.log').read_text()
                    assert text.endswith('CHECKSUM\t-1947596288\n')
                    rows = list(csv.DictReader(text[text.index('phase\t'):text.index('CHECKSUM\t')].splitlines(), delimiter='\t'))
                    assert sum(x['phase'] == 'measured' for x in rows) == 21
                records.append(record); save(args.output / 'executions.json', records)
                print(f'completed {path.name}', flush=True)
    for repeat in range(args.rounds):
        path = args.output / f'cold-{repeat + 1}'; path.mkdir()
        records.append(dict(arm='cold', repeat=repeat + 1, **run(
            [*args.jvm, 'KotlinJvmProbe', 'cold'], path / 'stdout.log')))
        path = args.output / f'memory-{repeat + 1}'; path.mkdir()
        records.append(dict(arm='memory', repeat=repeat + 1, **memory(args, path)))
        save(args.output / 'executions.json', records)
        print(f'completed cold/memory {repeat + 1}', flush=True)
    save(args.output / 'complete.json', dict(timing_processes=args.rounds * 4,
        cold_processes=args.rounds, memory_processes=args.rounds))


if __name__ == '__main__':
    main()
