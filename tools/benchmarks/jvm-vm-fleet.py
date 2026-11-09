#!/usr/bin/env python3
"""Run equal-work production-carrier populations in fresh standalone JVMs.

The probe uses real core/FFM/Rust ownership with a controlled 4096-instruction grant.
Batch barriers are measurement-only; this is not a Minecraft TPS or default-governor test.
"""
import argparse
import csv
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

from jvm_memory_metrics import histogram_footprint


def table(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def validate_trial(path, population):
    frames = table(path / 'frames.tsv')
    checks = {r['check']: (int(r['computers']), int(r['value'])) for r in table(path / 'checks.tsv')}
    mixed = (population + 9) // 10
    assert len(checks) == 7
    assert checks['idle-zero-permits'] == (population, 0)
    assert checks['distinct-burst-echo'] == (population, 10)
    for mode, active in [('compute', population), ('mixed', mixed), ('idle', 0)]:
        selected = [r for r in frames if r['mode'] == mode]
        assert len(selected) == 100 and {int(r['sample']) for r in selected} == set(range(100))
        assert all(int(r['active']) == active and int(r['retired']) == active * 4096 for r in selected)
        if active:
            assert checks[mode + '-equal-progress'] == (active, 409600)
            assert checks[mode + '-checksum'] == (active, 9999999)
    wakes = table(path / 'wakes.tsv')
    assert len(wakes) == 10 * population
    for wave in range(10):
        selected = [r for r in wakes if int(r['wave']) == wave]
        assert len(selected) == population and {int(r['computer']) for r in selected} == set(range(population))
        assert all(0 < int(r['latency_ns']) <= int(r['wave_wall_ns']) for r in selected)
        assert any(r['mode'] == f'burst-{wave}' for r in frames)
    assert {r['mode'] for r in table(path / 'phases.tsv')} == {'idle', 'burst', 'compute', 'mixed'}


def host_monitor(stop, path, phase):
    previous = {}
    with path.open('w', buffering=1) as stream:
        while not stop.is_set():
            current = {}
            active = []
            for proc in Path('/proc').glob('[0-9]*'):
                try:
                    stat = (proc / 'stat').read_text()
                    fields = stat[stat.rfind(')') + 2:].split()
                    name = stat[stat.find('(') + 1:stat.rfind(')')]
                    key = (int(proc.name), int(fields[19]))
                    ticks = int(fields[11]) + int(fields[12])
                    current[key] = ticks
                    delta = ticks - previous.get(key, ticks)
                    if delta or name in ['java', 'cargo', 'rustc', 'gradlew']:
                        active.append(dict(pid=key[0], name=name, ticks=delta))
                except (OSError, ValueError):
                    pass
            previous = current
            stream.write(json.dumps(dict(time=time.time(), phase=phase[0],
                pressure=Path('/proc/pressure/cpu').read_text(),
                cpus=[s for s in Path('/proc/stat').read_text().splitlines() if s.startswith('cpu')],
                processes=sorted(active, key=lambda r: r['ticks'], reverse=True)[:40])) + '\n')
            stop.wait(1)


def trial(args, population, workers, execution, phase):
    output = execution / 'output'
    assert not output.exists()
    argv = ['taskset', '-c', args.affinity, str(args.jdk / 'bin/java'), '-Xms128m', '-Xmx2g',
            '-XX:+UseG1GC', '--enable-native-access=ALL-UNNAMED', '--illegal-native-access=deny',
            '-cp', args.classpath.read_text().strip(),
            'ru.lazyhat.compukters.core.device.runtime.fleet.JvmVmFleetProbe', str(population),
            str(workers), str(args.library), str(args.image), str(output)]
    rows = []
    started = time.time()
    with (execution / 'stderr.log').open('w') as errors, (execution / 'stdout.log').open('w', buffering=1) as log:
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True)
        watchdog = threading.Timer(300, process.terminate)
        watchdog.start()
        complete = False
        try:
            for line in process.stdout:
                log.write(line)
                words = line.rstrip().split('\t')
                if words[0] == 'PHASE':
                    name = words[1]
                    phase[0] = f'population-{population}/workers-{workers}/memory-{name}'
                    jcmd = [str(args.jdk / 'bin/jcmd'), str(process.pid), 'GC.class_histogram']
                    result = subprocess.run(jcmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
                    assert result.returncode == 0, result.stdout
                    (execution / f'{name}-histogram.txt').write_text(result.stdout)
                    footprint = histogram_footprint(result.stdout)
                    smaps = Path(f'/proc/{process.pid}/smaps_rollup').read_text()
                    (execution / f'{name}-smaps.txt').write_text(smaps)
                    resident = {key: int(value) * 1024 for key, value in re.findall(
                        r'^(Rss|Pss|Private_Clean|Private_Dirty):\s+(\d+) kB', smaps, re.M)}
                    classes = {name: int(count) for count, name in re.findall(
                        r'^\s*\d+:\s+(\d+)\s+\d+\s+(\S+)', result.stdout, re.M)}
                    carriers = classes.get('ru.lazyhat.compukters.core.device.computer.ActorProgramComputer', 0)
                    hosts = classes.get('ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost', 0)
                    expected = 0 if name in ['baseline', 'closed'] else population
                    assert carriers == hosts == expected, (name, carriers, hosts, expected)
                    counters = dict(zip(['hosts', 'guest_heap_used', 'guest_heap_quota',
                                         'admitted_storage_charge', 'filesystem_logical_bytes', 'instructions'],
                                        map(int, words[2:]), strict=True))
                    assert counters['hosts'] == expected
                    rows.append(dict(phase=name, **counters, **footprint, **resident,
                                     carrier_objects=carriers, host_objects=hosts))
                    if name == 'baseline' and args.store_template:
                        # The child is paused before creating its Fleet/store. Each trial gets
                        # independent files from a closed fixture; never copy an active store.
                        shutil.copytree(args.store_template, output / 'fleet')
                    process.stdin.write('continue\n')
                    process.stdin.flush()
                    phase[0] = f'population-{population}/workers-{workers}/execution-after-{name}'
                elif words[0] == 'HEAP':
                    assert rows[-1]['phase'] == words[1]
                    rows[-1].update(heap_used=int(words[2]), heap_committed=int(words[3]))
                elif words[0] == 'COMPLETE':
                    assert list(map(int, words[1:])) == [population, workers]
                    complete = True
            assert process.wait(timeout=30) == 0, (execution / 'stderr.log').read_text()[-4000:]
            assert complete and [r['phase'] for r in rows] == ['baseline', 'prompt', 'compute', 'mixed', 'resumed', 'closed']
            validate_trial(output, population)
            (execution / 'memory.json').write_text(json.dumps(rows, indent=2) + '\n')
            return dict(argv=argv, pid=process.pid, started=started, ended=time.time(),
                        population=population, workers=workers, memory=rows, exit_code=process.returncode)
        finally:
            watchdog.cancel()
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', type=Path, required=True, help='Frozen standalone classpath file')
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New evidence directory')
    parser.add_argument('--jdk', type=Path, default=Path('/usr/lib/jvm/java-25-openjdk'))
    parser.add_argument('--populations', default='256,1000,2000')
    parser.add_argument('--workers', default='1,4,8')
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--affinity', default='0-15')
    parser.add_argument('--store-template', type=Path, help='Closed disposable preinstalled store copied before Fleet creation')
    parser.add_argument('--lock', type=Path, action='append', default=[])
    args = parser.parse_args()
    for name in ['classpath', 'library', 'image', 'output', 'jdk']:
        setattr(args, name, getattr(args, name).resolve())
    if args.store_template:
        args.store_template = args.store_template.resolve()
        assert args.store_template.is_dir()
    populations = [int(x) for x in args.populations.split(',')]
    workers = [int(x) for x in args.workers.split(',')]
    assert 1 <= args.rounds <= 20 and all(1 <= n <= 4096 for n in populations) and all(1 <= n <= 64 for n in workers)
    args.output.mkdir(parents=True)
    locks = []
    for path in args.lock:
        handle = path.open('rb')
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locks.append(handle)
    phase = ['startup']
    stop = threading.Event()
    monitor = threading.Thread(target=host_monitor, args=(stop, args.output / 'host-activity.jsonl', phase))
    monitor.start()
    metadata = dict(controller_pid=os.getpid(), pid_namespace=os.readlink('/proc/self/ns/pid'),
                    arguments={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items() if k != 'lock'},
                    image_sha256=hashlib.sha256(args.image.read_bytes()).hexdigest(),
                    library_sha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
                    aa_policy='Two byte-identical JVM/classpath/library labels per case/round; retain every observation and report paired variation.')
    (args.output / 'protocol.json').write_text(json.dumps(metadata, indent=2) + '\n')
    records = []
    try:
        for rnd in range(1, args.rounds + 1):
            cases = [(n, w) for n in populations for w in workers]
            for index, (population, worker_count) in enumerate(cases[::1 if rnd % 2 else -1]):
                for label in ['a', 'b'][::1 if (rnd + index) % 2 else -1]:
                    execution = args.output / 'execution'
                    execution.mkdir()
                    record = trial(args, population, worker_count, execution, phase)
                    destination = args.output / f'round-{rnd}' / f'p{population}-w{worker_count}' / label
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    execution.rename(destination)
                    record.update(round=rnd, label=label, directory=str(destination.relative_to(args.output)))
                    records.append(record)
                    (args.output / 'executions.json').write_text(json.dumps(records, indent=2) + '\n')
                    print(f'round={rnd} population={population} workers={worker_count} label={label} seconds={record["ended"]-record["started"]:.2f}', flush=True)
        (args.output / 'complete.json').write_text(json.dumps(dict(trials=len(records), ended=time.time()), indent=2) + '\n')
    finally:
        stop.set()
        monitor.join()
        for handle in reversed(locks):
            handle.close()


if __name__ == '__main__':
    main()
