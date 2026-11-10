#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# Licensed under the Apache License, Version 2.0.
"""Measure normally verified mixed native fleets; no Minecraft capacity claim."""
import argparse
import csv
import fcntl
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import threading
import time


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def summarize(root):
    protocol = json.loads((root / 'protocol.json').read_text())
    assert protocol['complete']
    states = {}
    observations = []
    for run in protocol['runs']:
        directory = root / run['directory']
        fleet, = rows(directory / 'fleet.tsv')
        for key in ['population', 'programs', 'workers']:
            assert int(fleet[key]) == run[key]
        assert fleet['mode'] == run['mode'] and fleet['order'] == run['order']
        checks = rows(directory / 'checks.tsv')
        assert len(checks) == run['programs'] and sum(int(c['population']) for c in checks) == run['population']
        for check in checks:
            semantic = {k: v for k, v in check.items() if k not in ['id', 'population']}
            assert len(semantic) == 9
            assert states.setdefault(check['id'], semantic) == semantic, (run, check['id'], 'state/work mismatch')
        batches = rows(directory / 'batches.tsv')
        workers = rows(directory / 'workers.tsv')
        assert len(batches) == protocol['batches'] and len(workers) == len(batches) * run['workers']
        assert all(int(c['retired']) >= 4096 * protocol['batches'] for c in checks)
        for batch in batches:
            selected = [w for w in workers if w['batch'] == batch['batch']]
            assert sum(int(w['population']) for w in selected) == run['population']
            assert sum(int(w['cpu_ns']) for w in selected) == int(batch['cpu_ns'])
            assert int(batch['retired']) == 4096 * run['population']
        jit = rows(directory / 'jit.tsv')
        before = {s['id']: s for s in jit if s['phase'] == 'before'}
        after = {s['id']: s for s in jit if s['phase'] == 'after'}
        stable_keys = ['compiled', 'regions', 'pending', 'failures', 'code_bytes', 'mapped_bytes', 'state_bytes', 'compile_ns']
        stable = all(all(before[k][field] == after[k][field] for field in stable_keys) for k in before)
        assert set(before) == set(after) == {c['id'] for c in checks}
        if protocol.get('require_stable', False) and run['kind'] == 'timing':
            assert stable, (run, 'tier changed during timed batches')
        assert all(int(s['pending']) == 0 for s in after.values())
        if run['mode'] == 'off':
            assert all(int(s['compiled']) == int(s['hits']) == 0 for s in after.values())
        else:
            assert sum(int(s['compiled']) for s in before.values()) > 0
        hot_telemetry = any(s.get('hot_telemetry', 'true') == 'true' for s in after.values())
        if not hot_telemetry:
            assert all(int(s['hits']) == int(s['native_attempts']) == 0 for s in after.values())
        elif run['mode'] == 'on':
            assert sum(int(s['native_attempts'])-int(before[k]['native_attempts']) for k,s in after.items()) > 0
        memory = fleet['base_requested_bytes'] != ''
        assert memory == (run['kind'] == 'memory')
        observations.append(dict(run, stable=stable, hot_telemetry=hot_telemetry,
            native_retirement_fraction=sum(int(s['native_attempts'])-int(before[k]['native_attempts']) for k,s in after.items())/(run['population']*protocol['batches']*4096) if hot_telemetry else None,
            zero_native_work_programs=sum(int(s['native_attempts']) == int(before[k]['native_attempts']) for k,s in after.items()) if hot_telemetry else None,
            cpu_ns=statistics.median(int(b['cpu_ns']) for b in batches),
            wall_ns=statistics.median(int(b['wall_ns']) for b in batches),
            p95_wall_ns=sorted(int(b['wall_ns']) for b in batches)[int((len(batches)-1)*.95)],
            modules=sum(int(s['compiled']) for s in after.values()),
            code_bytes=sum(int(s['code_bytes']) for s in after.values()),
            mapped_bytes=sum(int(s['mapped_bytes']) for s in after.values()),
            state_bytes=sum(int(s['state_bytes']) for s in after.values()),
            compile_ns=sum(int(s['compile_ns']) for s in after.values()),
            zero_native_programs=sum(int(s['compiled']) == 0 for s in after.values()),
            per_program=[dict(id=s['id'], compiled=int(s['compiled']), mapped_bytes=int(s['mapped_bytes']),
                native_attempts=int(s['native_attempts']) - int(before[k]['native_attempts'])) for k,s in after.items()],
            requested_base=int(fleet['base_requested_bytes']) if memory else None,
            requested_active=int(fleet['active_requested_bytes']) if memory else None,
            requested_ready_delta=int(fleet['ready_requested_bytes'])-int(fleet['base_requested_bytes']) if memory else None,
            requested_delta=int(fleet['active_requested_bytes'])-int(fleet['base_requested_bytes']) if memory else None,
            active_rss_kib=int(next(line.split()[1] for line in (directory/'active-smaps.txt').read_text().splitlines() if line.startswith('Rss:')))))
    configs = [(n,w,'forward') for n in protocol['program_counts'] for w in protocol['worker_counts']]
    if protocol['reverse']:
        configs += [(32,w,'reverse') for w in protocol['worker_counts']]
    expected_runs = {(kind,rnd,n,w,mode,order) for kind in protocol['binaries'] for rnd in range(1,(protocol['rounds'] if kind == 'timing' else 1)+1) for n,w,order in configs for mode in ['off','on']}
    actual_runs = [(o['kind'],o['round'],o['programs'],o['workers'],o['mode'],o['order']) for o in observations]
    assert len(actual_runs) == len(set(actual_runs)) and set(actual_runs) == expected_runs
    expected = protocol['rounds'] * (18 + (6 if protocol['reverse'] else 0))
    assert sum(o['kind'] == 'timing' for o in observations) == expected
    matrix = []
    for kind in ['timing', 'memory']:
        for programs in protocol['program_counts']:
            for workers in protocol['worker_counts']:
                selected = [o for o in observations if o['kind'] == kind and o['programs'] == programs and o['workers'] == workers and o['order'] == 'forward']
                if not selected:
                    continue
                modes = {}
                for mode in ['off','on']:
                    samples = [o for o in selected if o['mode'] == mode]
                    modes[mode] = {k: statistics.median(s[k] for s in samples) for k in ['cpu_ns','wall_ns','p95_wall_ns','modules','code_bytes','mapped_bytes','compile_ns','zero_native_programs']}
                    modes[mode]['stable'] = all(s['stable'] for s in samples)
                    modes[mode]['cpu_range_ratio'] = max(s['cpu_ns'] for s in samples)/min(s['cpu_ns'] for s in samples)-1
                    modes[mode]['wall_range_ratio'] = max(s['wall_ns'] for s in samples)/min(s['wall_ns'] for s in samples)-1
                    if kind == 'memory':
                        for field in ['requested_delta','requested_base','requested_active','requested_ready_delta','active_rss_kib']:
                            modes[mode][field] = statistics.median(s[field] for s in samples)
                matrix.append(dict(kind=kind, programs=programs, workers=workers, modes=modes,
                    cpu_speedup=modes['off']['cpu_ns']/modes['on']['cpu_ns'],
                    wall_speedup=modes['off']['wall_ns']/modes['on']['wall_ns']))
    return dict(matrix=matrix, observations=observations, checked_programs=len(states), checked_processes=len(observations))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--binary',type=Path)
    p.add_argument('--memory-binary',type=Path)
    p.add_argument('--inputs',type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--population',type=int,default=2000)
    p.add_argument('--rounds',type=int,default=3)
    p.add_argument('--batches',type=int,default=20)
    p.add_argument('--lock',type=Path,action='append',default=[])
    p.add_argument('--reverse',action='store_true')
    p.add_argument('--check',action='store_true')
    args=p.parse_args()
    if args.check:
        assert summarize(args.output) == json.loads((args.output/'summary.json').read_text())
        print('PASS: matrix, process counts, CPU sums, retirement, state/work, optional allocations, telemetry')
        return
    assert args.binary and args.inputs and args.rounds >= 3 and args.batches == 20
    args.output.mkdir(parents=True,exist_ok=False)
    held=[]
    for lock in args.lock:
        stream=lock.open('rb');fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB);held.append(stream)
    binaries={'timing':args.binary.resolve()}
    if args.memory_binary: binaries['memory']=args.memory_binary.resolve()
    protocol=dict(population=args.population,rounds=args.rounds,batches=args.batches,reverse=args.reverse,
        program_counts=[1,8,32],worker_counts=[1,4,8],complete=False,require_stable=True,
        binaries={k:dict(path=str(v),sha256=sha(v)) for k,v in binaries.items()},
        inputs={p.name:sha(p) for p in sorted(args.inputs.iterdir()) if p.suffix in ['.kt','.cpkt','.tsv']},
        locks=[str(p.resolve()) for p in args.lock],runs=[])
    save(args.output/'protocol.json',protocol)
    stop=threading.Event()
    def monitor():
        with (args.output/'host-activity.jsonl').open('w') as stream:
            while not stop.is_set():
                stream.write(json.dumps(dict(epoch=time.time(),processes=subprocess.check_output(['ps','-eo','pid,pcpu,comm','--sort=-pcpu'],text=True).splitlines()[:20]))+'\n');stream.flush();stop.wait(2)
    watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
    try:
        configs=[(n,w,'forward') for n in [1,8,32] for w in [1,4,8]]
        if args.reverse: configs += [(32,w,'reverse') for w in [1,4,8]]
        for kind,binary in binaries.items():
            rounds=args.rounds if kind == 'timing' else 1
            for rnd in range(rounds):
                for index,(n,w,order) in enumerate(configs[rnd:]+configs[:rnd]):
                    for mode in (['off','on'] if (rnd+index)%2 == 0 else ['on','off']):
                        relative=f'{kind}/round-{rnd+1}/p{n}-w{w}-{mode}-{order}'
                        directory=args.output/relative;directory.parent.mkdir(parents=True,exist_ok=True)
                        cmd=[str(binary),'jit-mixed-fleet',str(args.inputs.resolve()),str(directory.resolve()),str(args.population),str(n),str(w),'65536',mode,order,str(args.batches)]
                        started_epoch=time.time()
                        with directory.with_suffix('.log').open('w') as log:
                            subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
                        telemetry=rows(directory/'jit.tsv')
                        before={s['id']:s for s in telemetry if s['phase']=='before'}
                        after={s['id']:s for s in telemetry if s['phase']=='after'}
                        stable_keys=['compiled','regions','pending','failures','code_bytes','mapped_bytes','state_bytes','compile_ns']
                        assert all(all(before[k][f]==after[k][f] for f in stable_keys) for k in before), (relative,'unstable compilation tier')
                        protocol['runs'].append(dict(kind=kind,round=rnd+1,programs=n,workers=w,population=args.population,mode=mode,order=order,directory=relative,command=cmd,elapsed_s=time.time()-started_epoch))
                        save(args.output/'protocol.json',protocol)
                        print(relative,round(time.time()-started_epoch,2),flush=True)
        protocol['complete']=True;save(args.output/'protocol.json',protocol)
        save(args.output/'summary.json',summarize(args.output))
    finally:
        stop.set();watcher.join()


if __name__ == '__main__':
    main()
