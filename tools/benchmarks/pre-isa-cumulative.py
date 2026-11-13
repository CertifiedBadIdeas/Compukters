#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Fresh four-arm historical/current comparison; --check never executes a VM."""
import argparse
import csv
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import threading
import time

ARMS = {'old-old': ('old', 'old', 'untraced'),
        'old-current': ('old', 'current', 'untraced'),
        'current-current': ('current', 'current', 'untraced'),
        'jit-current': ('jit', 'current', 'jit-untraced')}
SUITES = [('transient-allocations', 16384), ('collection-reuse', 262144), ('typed-execution', 65536)]
COUNTERS = ['hot_' + name for name in ['fixed_units', 'dynamic_units', 'maintenance_units',
            'blocks', 'executed_instructions', 'retired_instructions', 'requests', 'responses']]


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def analyze(root):
    protocol = json.loads((root / 'protocol.json').read_text())
    for rel, digest in protocol['inputs'].items():
        assert sha(root / rel) == digest, rel
    observations = []
    identities = set()
    case_metadata = {case: (suite, heap) for suite, heap, case in protocol['cases']}
    for run in protocol['runs']:
        assert run['status'] == 0
        identity = (run['round'], run['case'], run['arm'], run['replica'])
        assert identity not in identities
        identities.add(identity)
        assert run['round'] in range(protocol['rounds']) and run['replica'] in ['a', 'b']
        runtime, inputs, mode = ARMS[run['arm']]
        suite, heap = case_metadata[run['case']]
        assert run['sha256'] == protocol['binaries'][runtime]
        assert run['input_sha256'] == protocol['inputs'][f'inputs/{inputs}/{suite}/{run["case"]}.cpkt']
        directory = root / run['directory']
        samples = rows(directory / 'samples.tsv')
        summary, = rows(directory / 'measurements.tsv')
        assert len(samples) == protocol['samples']
        assert {x['id'] for x in samples} == {run['case']}
        assert [int(x['sample']) for x in samples] == list(range(1, protocol['samples'] + 1))
        assert all(x['mode'] == mode and int(x['heap_bytes']) == heap and int(x['hot_cpu_ns']) > 0 for x in samples)
        cpu = statistics.median(int(x['hot_cpu_ns']) for x in samples)
        assert cpu == int(summary['hot_cpu_median_ns'])
        observation = dict(run, cpu_ns=cpu, work={k: int(summary[k]) for k in COUNTERS})
        if run['arm'] == 'jit-current':
            before, after = rows(directory / 'jit.tsv')
            assert before['phase'] == 'warmup' and after['phase'] == 'measured'
            for name in ['compile_ns', 'compiled', 'code_bytes', 'mapped_bytes_upper_bound', 'state_bytes']:
                assert before[name] == after[name], (run, name, 'tier changed')
            assert int(after['pending']) == int(after['failures']) == 0
            assert int(after['compiled']) > 0
            assert after['hot_telemetry'] == 'false'
            assert int(after['hits']) == int(after['native_attempts']) == 0
            observation['jit'] = after
        observations.append(observation)
    expected = protocol['rounds'] * len(protocol['cases']) * len(ARMS) * 2
    assert len(observations) == expected, (len(observations), expected)
    result = {}
    for _, _, case in protocol['cases']:
        selected = [x for x in observations if x['case'] == case]
        for arm in ARMS:
            work = [x['work'] for x in selected if x['arm'] == arm]
            assert all(x == work[0] for x in work), (case, arm)
        same_artifact = [x['work'] for x in selected if x['arm'] != 'old-old']
        assert all(x == same_artifact[0] for x in same_artifact), (case, 'runtime accounting')
        medians = {a: statistics.median(x['cpu_ns'] for x in selected if x['arm'] == a) for a in ARMS}
        comparisons = {}
        for name, base, candidate in [('total', 'old-old', 'current-current'),
                                      ('runtime', 'old-current', 'current-current'),
                                      ('compiler', 'old-old', 'old-current'),
                                      ('total-jit', 'old-old', 'jit-current'),
                                      ('jit', 'current-current', 'jit-current')]:
            paired = []
            for rnd in range(protocol['rounds']):
                group = {(x['arm'], x['replica']): x['cpu_ns'] for x in selected if x['round'] == rnd}
                b = statistics.median(group[base, rep] for rep in ['a', 'b'])
                c = statistics.median(group[candidate, rep] for rep in ['a', 'b'])
                aa = max(abs(group[arm, 'a'] / group[arm, 'b'] - 1) for arm in [base, candidate])
                paired.append(dict(change=c / b - 1, duplicate_variation=aa))
            change = medians[candidate] / medians[base] - 1
            qualified = all(p['duplicate_variation'] <= .10 and abs(p['change']) > p['duplicate_variation']
                            and p['change'] * change > 0 for p in paired)
            comparisons[name] = dict(change=change, speedup=medians[base] / medians[candidate], qualified=qualified, rounds=paired)
        result[case] = dict(cpu_ns=medians, comparisons=comparisons,
                            work_by_arm={a: next(x['work'] for x in selected if x['arm'] == a) for a in ARMS},
                            jit=[x['jit'] for x in selected if x['arm'] == 'jit-current'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--stand', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    if args.check:
        assert analyze(root) == json.loads((root / 'summary.json').read_text())
        print('PASS: input hashes, all sample medians, accounting, stable JIT tiers and comparison gates')
        return
    assert args.stand and not (root / 'protocol.json').exists(), 'Never overwrite an experiment'
    stand = args.stand.resolve()
    locks = []
    for rel in ['.toolchain/gradle-build.lock', '.git/modules/.toolchain/gradle-build.lock']:
        stream = (stand / rel).open('rb')
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locks.append(stream)
    cases = [(suite, heap, row['id']) for suite, heap in SUITES for row in rows(root / 'inputs/current' / suite / 'manifest.tsv')]
    assert len(cases) == 15
    for suite, _, case in cases:
        assert (root / 'inputs/old' / suite / (case + '.kt')).read_bytes() == (root / 'inputs/current' / suite / (case + '.kt')).read_bytes()
    protocol = dict(rounds=3, samples=7, cpu=6, arms=ARMS, cases=cases, runs=[],
                    qualification='All three round effects exceed the larger arm duplicate variation, variation <=10%, consistent direction; empirical gate, not a confidence interval.',
                    inputs=json.loads((root / 'inputs-sha256.json').read_text()),
                    binaries=json.loads((root / 'binary-sha256.json').read_text()), started=time.time())
    save(root / 'protocol.json', protocol)
    images = {name: (root / 'binaries' / name).read_bytes() for name in ['old', 'current', 'jit']}
    for name in images:
        assert hashlib.sha256(images[name]).hexdigest() == protocol['binaries'][name]
    stop = threading.Event()

    def monitor():
        previous = {}
        with (root / 'host-activity.jsonl').open('w', buffering=1) as log:
            while not stop.is_set():
                current, activity = {}, []
                for proc in Path('/proc').glob('[0-9]*'):
                    try:
                        value = (proc / 'stat').read_text()
                        fields = value[value.rfind(')') + 2:].split()
                        key = (int(proc.name), int(fields[19]))
                        ticks = int(fields[11]) + int(fields[12])
                        delta = ticks - previous.get(key, ticks)
                        current[key] = ticks
                        if delta:
                            activity.append(dict(pid=key[0], name=value[value.find('(')+1:value.rfind(')')], ticks=delta))
                    except (OSError, ValueError):
                        pass
                previous = current
                log.write(json.dumps(dict(epoch=time.time(), processes=activity, pressure=Path('/proc/pressure/cpu').read_text()))+'\n')
                stop.wait(1)

    watcher = threading.Thread(target=monitor)
    watcher.start()
    try:
        for rnd in range(3):
            execution = root / f'execution-{rnd}'
            execution.mkdir()
            binary, output = execution / 'runner', execution / 'output'
            binary.write_bytes(images['old'])
            binary.chmod(0o755)
            inode = binary.stat().st_ino
            for index, (suite, heap, case) in enumerate(cases[::1 if rnd % 2 == 0 else -1]):
                arms = list(ARMS)
                shift = (rnd + index) % len(arms)
                arms = arms[shift:] + arms[:shift]
                schedule = [(a, 'a') for a in arms] + [(a, 'b') for a in arms[::-1]]
                for arm, replica in schedule:
                    runtime, inputs, mode = ARMS[arm]
                    with binary.open('r+b') as stream:
                        stream.write(images[runtime])
                        stream.truncate()
                        stream.flush()
                        os.fsync(stream.fileno())
                    assert binary.stat().st_ino == inode
                    assert sha(binary) == protocol['binaries'][runtime]
                    target = execution / 'inputs'
                    if target.exists():
                        shutil.rmtree(target)
                    shutil.copytree(root / 'inputs' / inputs / suite, target)
                    relative = f'trials/round-{rnd}/{case}/{arm}-{replica}'
                    destination = root / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    argv = ['setarch','x86_64','-R','taskset','-c','6',str(binary),str(target),str(output),'7',str(heap),mode,case]
                    start = time.time()
                    with destination.with_suffix('.log').open('w') as log:
                        process = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT)
                    record = dict(round=rnd, case=case, arm=arm, replica=replica, inode=inode,
                                  sha256=sha(binary), input_sha256=sha(target / (case+'.cpkt')),
                                  directory=relative, argv=argv, status=process.returncode, start=start, end=time.time())
                    protocol['runs'].append(record)
                    save(root / 'protocol.json', protocol)
                    assert process.returncode == 0, (relative, destination.with_suffix('.log'))
                    output.rename(destination)
                print(f'round {rnd+1}/3: {case}', flush=True)
        assert len({r['inode'] for r in protocol['runs']}) == 3
        save(root / 'summary.json', analyze(root))
        save(root / 'complete.json', dict(ended=time.time(), launches=len(protocol['runs'])))
    finally:
        stop.set()
        watcher.join()
        for stream in reversed(locks):
            stream.close()


if __name__ == '__main__':
    main()
