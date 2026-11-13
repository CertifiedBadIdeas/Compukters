#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# Licensed under the Apache License, Version 2.0.
"""Compare shared JIT admission revisions; native sessions, not Minecraft capacity."""
import argparse
import csv
import fcntl
import hashlib
import json
import statistics
import subprocess
import threading
import time
from pathlib import Path


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


CONFIGS = [(n, w, 'forward') for n in [1, 8, 32] for w in [1, 4, 8]] + [
    (32, w, 'reverse') for w in [1, 4, 8]]


def observe(directory, run, states):
    fleet, = rows(directory / 'fleet.tsv')
    for field in ['population', 'programs', 'workers']:
        assert int(fleet[field]) == run[field]
    assert fleet['mode'] == run['mode'] and fleet['order'] == run['order']
    checks = rows(directory / 'checks.tsv')
    assert len(checks) == run['programs']
    assert len({c['id'] for c in checks}) == len(checks)
    assert sum(int(c['population']) for c in checks) == run['population']
    for check in checks:
        semantic = {k: v for k, v in check.items() if k not in ['id', 'population']}
        assert len(semantic) == 9
        assert int(check['retired']) == 81928  # Independently observed ready=8, then 20*4096.
        assert states.setdefault(check['id'], semantic) == semantic, (run, check['id'])
    batches = rows(directory / 'batches.tsv')
    workers = rows(directory / 'workers.tsv')
    assert len(batches) == 20 and {int(b['batch']) for b in batches} == set(range(20))
    assert len(workers) == 20 * run['workers']
    cpus = {1: [6], 4: [3, 4, 5, 6], 8: list(range(8))}[run['workers']]
    for batch in batches:
        selected = [w for w in workers if w['batch'] == batch['batch']]
        assert len(selected) == run['workers']
        assert {int(w['worker']) for w in selected} == set(range(run['workers']))
        assert sorted(int(w['cpu']) for w in selected) == cpus
        assert sum(int(w['population']) for w in selected) == run['population']
        assert sum(int(w['cpu_ns']) for w in selected) == int(batch['cpu_ns'])
        assert int(batch['retired']) == 4096 * run['population']
    telemetry = rows(directory / 'jit.tsv')
    phases = {phase: {s['id']: s for s in telemetry if s['phase'] == phase}
              for phase in ['before', 'after', 'drained']}
    before, after = phases['before'], phases['after']
    assert set(before) == set(after) == {c['id'] for c in checks}
    stable_fields = ['compiled', 'regions', 'pending', 'failures', 'code_bytes',
                     'mapped_bytes', 'state_bytes', 'compile_ns']
    stable = all(all(before[k][f] == after[k][f] for f in stable_fields) for k in before)
    if run['revision'] == 'candidate':
        if run['phase'] == 'warm' and run['mode'] == 'on':
            assert 512 <= int(fleet['warm_passes']) <= 4096
            stable = stable and all(before[k]['admission_epoch'] == after[k]['admission_epoch'] for k in before)
    if run['phase'] == 'warm':
        assert stable, (run, 'tier changed during measurement')
    final = phases['drained'] or after
    assert set(final) == set(after)
    assert all(int(s['pending']) == 0 for s in final.values())
    assert all(s['hot_telemetry'] == 'false' and int(s['hits']) == int(s['native_attempts']) == 0
               for s in final.values())
    if run['mode'] == 'off':
        assert all(int(s['compiled']) == 0 for s in final.values())
    else:
        assert sum(int(s['compiled']) for s in final.values()) > 0
        if run['phase'] == 'warm':
            assert all(int(s['compiled']) > 0 for s in final.values())
    if run['revision'] == 'candidate':
        resource, = rows(directory / 'resources.tsv')
        assert int(resource['pending']) == 0
        assert int(resource['modules']) == sum(int(s['compiled']) for s in final.values()) <= 2048
        assert int(resource['reserved_bytes']) == sum(int(s['mapped_bytes']) for s in final.values()) <= 16*1024*1024
        assert all(int(s['compiled']) <= 64 and int(s['reserved_bytes']) == int(s['mapped_bytes']) <= 512*1024
                   for s in final.values())
    memory = fleet['active_requested_bytes'] != ''
    assert memory == (run['kind'] == 'memory')
    return dict(run, stable=stable, warm_passes=int(fleet.get('warm_passes', '512' if run['mode'] == 'on' else '0')),
        cpu_ns=statistics.median(int(b['cpu_ns']) for b in batches),
        wall_ns=statistics.median(int(b['wall_ns']) for b in batches),
        warm_ns=int(fleet['warm_ns']), admission_ns=int(fleet['admission_ns']),
        modules=sum(int(s['compiled']) for s in final.values()),
        zero_native_programs=sum(int(s['compiled']) == 0 for s in final.values()),
        mapped_bytes=sum(int(s['mapped_bytes']) for s in final.values()),
        code_bytes=sum(int(s['code_bytes']) for s in final.values()),
        state_bytes=sum(int(s['state_bytes']) for s in final.values()),
        compile_ns=sum(int(s['compile_ns']) for s in final.values()),
        failures=sum(int(s['failures']) for s in final.values()),
        per_program=[dict(id=s['id'], modules=int(s['compiled']), mapped_bytes=int(s['mapped_bytes']))
                     for s in final.values()],
        requested_active=int(fleet['active_requested_bytes']) if memory else None,
        requested_delta=int(fleet['active_requested_bytes'])-int(fleet['base_requested_bytes']) if memory else None,
        rss_kib=int(next(line.split()[1] for line in (directory/'active-smaps.txt').read_text().splitlines()
                         if line.startswith('Rss:'))))


def summarize(root):
    protocol = json.loads((root / 'protocol.json').read_text())
    assert protocol['complete'] and protocol['rounds'] >= 3
    states, observations, identities = {}, [], set()
    for run in protocol['runs']:
        key = (run['kind'], run['phase'], run['round'], run['programs'], run['workers'],
               run['order'], run['revision'], run['mode'], run['position'])
        assert key not in identities
        identities.add(key)
        if run.get('recovered'):
            assert json.loads((root/run['directory']/'process-completed.json').read_text())['returncode'] == 0
        observations.append(observe(root/run['directory'], run, states))
    expected = set()
    for rnd in range(protocol['rounds']):
        for n, w, order in CONFIGS:
            sequence = ['baseline', 'candidate', 'candidate', 'baseline'] if rnd % 2 == 0 else [
                'candidate', 'baseline', 'baseline', 'candidate']
            for pos, revision in enumerate(sequence):
                expected.add(('timing', 'warm', rnd, n, w, order, revision, 'on', pos))
    for n, w, order in CONFIGS:
        for revision in ['baseline', 'candidate']:
            for mode in ['off', 'on']:
                expected.add(('memory', 'warm', 0, n, w, order, revision, mode, 0))
        for mode in ['off', 'on']:
            expected.add(('timing', 'cold', 0, n, w, order, 'candidate', mode, 0))
    assert identities == expected, (len(identities), len(expected))
    matrix = []
    for n, w, order in CONFIGS:
        rounds = []
        for rnd in range(protocol['rounds']):
            selected = [o for o in observations if o['kind'] == 'timing' and o['phase'] == 'warm'
                        and (o['programs'], o['workers'], o['order'], o['round']) == (n, w, order, rnd)]
            pair = {r: [o['cpu_ns'] for o in selected if o['revision'] == r]
                    for r in ['baseline', 'candidate']}
            a, b = (statistics.median(pair[r]) for r in ['baseline', 'candidate'])
            variation = max(max(v)/min(v)-1 for v in pair.values())
            change = b/a-1
            rounds.append(dict(round=rnd, baseline_ns=a, candidate_ns=b,
                               change=change, duplicate_variation=variation,
                               resolved=abs(change) > variation and variation < .1))
        a = statistics.median(r['baseline_ns'] for r in rounds)
        b = statistics.median(r['candidate_ns'] for r in rounds)
        qualified = all(r['resolved'] and (r['change'] > 0) == (b > a) for r in rounds)
        matrix.append(dict(programs=n, workers=w, order=order, baseline_ns=a, candidate_ns=b,
                           change=b/a-1, qualified=qualified, rounds=rounds))
    return dict(processes=len(observations), matrix=matrix, observations=observations)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['baseline', 'candidate', 'baseline-memory', 'candidate-memory', 'inputs']:
        parser.add_argument('--'+name, type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--lock', type=Path, action='append', default=[])
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    if args.check:
        assert summarize(args.output) == json.loads((args.output/'summary.json').read_text())
        print('PASS: full matrix, duplicate controls, checkpoints, work, resources and memory')
        return
    assert args.rounds >= 3
    if not args.resume:
        args.output.mkdir(parents=True, exist_ok=False)
    held = []
    for lock in args.lock:
        stream = lock.open('rb')
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        held.append(stream)
    binaries = {name: getattr(args, name.replace('-', '_')).resolve() for name in [
        'baseline', 'candidate', 'baseline-memory', 'candidate-memory']}
    protocol = dict(complete=False, rounds=args.rounds, population=2000,
        binaries={k: dict(path=str(v), sha256=sha(v)) for k, v in binaries.items()},
        inputs={p.name: sha(p) for p in sorted(args.inputs.iterdir()) if p.suffix in ['.kt', '.cpkt', '.tsv']},
        controller_sha256=sha(Path(__file__)), locks=[str(p.resolve()) for p in args.lock], runs=[])
    if args.resume:
        previous = json.loads((args.output/'protocol.json').read_text())
        assert not previous['complete']
        for key in ['rounds', 'population', 'binaries', 'inputs', 'locks']:
            assert previous[key] == protocol[key], ('resume input changed', key)
        protocol = previous
        protocol['resume_controller_sha256'] = sha(Path(__file__))
    save(args.output/'protocol.json', protocol)
    stop = threading.Event()
    def monitor():
        with (args.output/'host-activity.jsonl').open('a' if args.resume else 'w') as stream:
            while not stop.is_set():
                stream.write(json.dumps(dict(epoch=time.time(), processes=subprocess.check_output(
                    ['ps', '-eo', 'pid,pcpu,comm', '--sort=-pcpu'], text=True).splitlines()[:20]))+'\n')
                stream.flush()
                stop.wait(2)
    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    def run(kind, phase, rnd, n, w, order, revision, mode, pos):
        relative = f'{kind}/{phase}/r{rnd}/p{n}-w{w}-{order}-{revision}-{mode}-{pos}'
        directory = args.output/relative
        directory.parent.mkdir(parents=True, exist_ok=True)
        binary = binaries[revision + ('-memory' if kind == 'memory' else '')]
        command = [str(binary), 'jit-mixed-fleet', str(args.inputs.resolve()), str(directory.resolve()),
                   '2000', str(n), str(w), '65536', mode, order, '20']
        if phase == 'cold':
            command.append('cold')
        entry = dict(kind=kind, phase=phase, round=rnd, programs=n, workers=w, order=order,
                     population=2000, revision=revision, mode=mode, position=pos, directory=relative)
        existing = [r for r in protocol['runs'] if r['directory'] == relative]
        if existing:
            assert args.resume and len(existing) == 1
            assert all(existing[0][key] == value for key, value in entry.items())
            observe(directory, existing[0], {})
            return
        if directory.exists():
            assert args.resume, ('existing unrecorded process', relative)
            assert json.loads((directory/'process-completed.json').read_text())['returncode'] == 0
            # A completed child may have been rejected by the previous analyzer.
            # Recover its exact observations; never substitute a fresh timing.
            entry.update(elapsed_s=None, recovered=True)
        else:
            started = time.time()
            with directory.with_suffix('.log').open('w') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
            save(directory/'process-completed.json', dict(returncode=0, command=command))
            entry['elapsed_s'] = time.time()-started
        observe(directory, entry, {})
        protocol['runs'].append(entry)
        save(args.output/'protocol.json', protocol)
        print(relative, 'recovered' if entry.get('recovered') else round(entry['elapsed_s'], 2), flush=True)
    try:
        for rnd in range(args.rounds):
            for n, w, order in CONFIGS[rnd:]+CONFIGS[:rnd]:
                sequence = ['baseline', 'candidate', 'candidate', 'baseline'] if rnd % 2 == 0 else [
                    'candidate', 'baseline', 'baseline', 'candidate']
                for pos, revision in enumerate(sequence):
                    run('timing', 'warm', rnd, n, w, order, revision, 'on', pos)
        for n, w, order in CONFIGS:
            for revision in ['baseline', 'candidate']:
                for mode in ['off', 'on']:
                    run('memory', 'warm', 0, n, w, order, revision, mode, 0)
            for mode in ['off', 'on']:
                run('timing', 'cold', 0, n, w, order, 'candidate', mode, 0)
        protocol['complete'] = True
        save(args.output/'protocol.json', protocol)
        save(args.output/'summary.json', summarize(args.output))
    finally:
        stop.set()
        watcher.join()


if __name__ == '__main__':
    main()
